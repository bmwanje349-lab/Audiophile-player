package com.example.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * User-facing loudness settings (Poweramp-style "preamp" + smart loudness + final limiter).
 * Read from the audio thread, so every field is @Volatile.
 */
object LoudnessSettings {
    @Volatile var enabled: Boolean = true
    /** Fixed pre-amplification, dB (0..+12). */
    @Volatile var preampDb: Float = 0f
    /** Smart loudness: lifts quiet tracks toward targetLufs. */
    @Volatile var smartEnabled: Boolean = true
    @Volatile var targetLufs: Float = -12f
    /** Maximum boost smart loudness may apply, dB. */
    @Volatile var maxBoostDb: Float = 9f
    /** Final output ceiling, dBFS. */
    @Volatile var ceilingDb: Float = -0.3f

    private const val PREFS = "loudness_settings"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", true)
        preampDb = p.getFloat("preamp", 0f).coerceIn(-12f, 12f)
        smartEnabled = p.getBoolean("smart", true)
        targetLufs = p.getFloat("target", -12f).coerceIn(-24f, -6f)
        maxBoostDb = p.getFloat("maxboost", 9f).coerceIn(0f, 15f)
        ceilingDb = p.getFloat("ceiling", -0.3f).coerceIn(-6f, 0f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putFloat("preamp", preampDb)
            .putBoolean("smart", smartEnabled)
            .putFloat("target", targetLufs)
            .putFloat("maxboost", maxBoostDb)
            .putFloat("ceiling", ceilingDb)
            .apply()
    }
}

/**
 * Converts any PCM input (16/24/32-bit int or float) to 32-bit float so the whole DSP chain
 * (PEQ, widener, loudness) runs without 16-bit quantisation or intermediate clipping.
 */
@UnstableApi
class ToFloatProcessor : BaseAudioProcessor() {
    private var enc = C.ENCODING_INVALID

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        enc = inputAudioFormat.encoding
        return when (enc) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT ->
                AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
            C.ENCODING_PCM_FLOAT -> AudioFormat.NOT_SET // already float: stay inactive
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val n = inputBuffer.remaining() / bytes
        if (n <= 0) return
        val out = replaceOutputBuffer(n * 4).order(ByteOrder.nativeOrder())
        val inp = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        var pos = inputBuffer.position()
        for (i in 0 until n) {
            val v: Float = when (enc) {
                C.ENCODING_PCM_16BIT -> inp.getShort(pos).toInt() / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val b0 = inp.get(pos).toInt() and 0xFF
                    val b1 = inp.get(pos + 1).toInt() and 0xFF
                    val b2 = inp.get(pos + 2).toInt() // sign extends
                    ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                }
                else -> (inp.getInt(pos).toDouble() / 2147483648.0).toFloat()
            }
            out.putFloat(v)
            pos += bytes
        }
        inputBuffer.position(pos)
        out.flip()
    }
}

/**
 * Final stage: preamp -> smart loudness (K-weighted, slow, boost-only) -> look-ahead brickwall
 * limiter with guaranteed ceiling.  Expects float input (placed after [ToFloatProcessor]).
 *
 * Limiter: peak over a sliding window of N frames -> per-frame minimum gain -> release-smoothed
 * -> boxcar-averaged over N frames, output delayed by N-1 frames.  This construction provably keeps
 * every output sample at or below the ceiling (sample-peak) with a smooth, click-free gain curve.
 */
@UnstableApi
class LoudnessProcessor : BaseAudioProcessor() {
    private var ch = 2
    private var sr = 44100
    private var n = 1               // window length (frames)
    private lateinit var delay: FloatArray      // n * ch
    private var delayPos = 0
    private var dly = 1             // output delay in frames = n-1
    private lateinit var peakVal: FloatArray    // monotonic deque (values)
    private lateinit var peakIdx: LongArray     // monotonic deque (indices)
    private var dqHead = 0
    private var dqTail = 0
    private var dqCap = 0
    private lateinit var gRing: FloatArray
    private var gRingPos = 0
    private var gSum = 0.0
    private var relState = 1f
    private var frameIdx = 0L
    private var relCoef = 0.001f

    // K-weighting-ish measurement filters (per channel)
    private lateinit var hp: DoubleArray        // biquad state x1 x2 y1 y2 per channel
    private lateinit var sh: DoubleArray
    private var hpB = DoubleArray(3); private var hpA = DoubleArray(2)
    private var shB = DoubleArray(3); private var shA = DoubleArray(2)
    private var msEnv = 0.0
    private var msCoef = 0.0
    private var smartGain = 1f
    private var slowPeak = 0f
    private var peakDecay = 0f
    private var smartDownCoef = 0f
    private var smartUpCoef = 0f
    private var preampSmoothed = 1f
    private var flushing = false
    private var tailFrames = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw UnhandledAudioFormatException(inputAudioFormat)
        ch = inputAudioFormat.channelCount
        sr = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun onFlush() {
        n = max(2, (0.004 * sr).toInt())
        dly = n - 1
        delay = FloatArray(dly * ch); delayPos = 0
        dqCap = n + 2
        peakVal = FloatArray(dqCap); peakIdx = LongArray(dqCap); dqHead = 0; dqTail = 0
        gRing = FloatArray(n) { 1f }; gRingPos = 0; gSum = n.toDouble()
        relState = 1f; frameIdx = 0L
        relCoef = (1.0 - exp(-1.0 / (0.120 * sr))).toFloat()
        hp = DoubleArray(4 * ch); sh = DoubleArray(4 * ch)
        designFilters()
        msEnv = 0.0
        msCoef = exp(-1.0 / (0.400 * sr))
        smartGain = 1f
        slowPeak = 0f
        peakDecay = exp(-1.0 / (6.0 * sr)).toFloat()
        smartDownCoef = (1.0 - exp(-1.0 / (0.25 * sr))).toFloat()
        smartUpCoef = (1.0 - exp(-1.0 / (3.0 * sr))).toFloat()
        preampSmoothed = 1f
        flushing = false
        tailFrames = 0
    }

    private fun designFilters() {
        // High-pass 38 Hz (RLB-like), Butterworth Q
        run {
            val f0 = 38.0; val q = 0.5
            val w0 = 2 * PI * f0 / sr; val al = sin(w0) / (2 * q); val c = cos(w0)
            val b0 = (1 + c) / 2; val b1 = -(1 + c); val b2 = (1 + c) / 2
            val a0 = 1 + al; val a1 = -2 * c; val a2 = 1 - al
            hpB = doubleArrayOf(b0 / a0, b1 / a0, b2 / a0); hpA = doubleArrayOf(a1 / a0, a2 / a0)
        }
        // High shelf +4 dB @ 1.68 kHz (head-related boost of BS.1770)
        run {
            val f0 = 1681.0; val gain = 4.0; val q = 0.707
            val a = 10.0.pow(gain / 40.0)
            val w0 = 2 * PI * f0 / sr; val c = cos(w0); val s = sin(w0)
            val al = s / (2 * q); val sq = 2 * sqrt(a) * al
            val b0 = a * ((a + 1) + (a - 1) * c + sq)
            val b1 = -2 * a * ((a - 1) + (a + 1) * c)
            val b2 = a * ((a + 1) + (a - 1) * c - sq)
            val a0 = (a + 1) - (a - 1) * c + sq
            val a1 = 2 * ((a - 1) - (a + 1) * c)
            val a2 = (a + 1) - (a - 1) * c - sq
            shB = doubleArrayOf(b0 / a0, b1 / a0, b2 / a0); shA = doubleArrayOf(a1 / a0, a2 / a0)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (4 * ch)
        if (frames <= 0) return
        val out = replaceOutputBuffer(frames * ch * 4).order(ByteOrder.nativeOrder())
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val s = LoudnessSettings
        val on = s.enabled
        val ceilLin = 10.0.pow(min(0.0, s.ceilingDb.toDouble()) / 20.0).toFloat()
        val preTarget = if (on) 10.0.pow(s.preampDb.toDouble() / 20.0).toFloat() else 1f
        val smartOn = on && s.smartEnabled
        val pos0 = inputBuffer.position()
        val frame = FloatArray(ch)
        for (f in 0 until frames) {
            var pk = 0f
            var msSum = 0.0
            for (c in 0 until ch) {
                var x = inp.getFloat(pos0 + (f * ch + c) * 4)
                if (!x.isFinite()) x = 0f
                frame[c] = x
                if (smartOn) {
                    val y = biquad(biquad(x.toDouble(), hpB, hpA, hp, c * 4), shB, shA, sh, c * 4)
                    msSum += y * y
                }
            }
            preampSmoothed += (preTarget - preampSmoothed) * 0.0005f
            if (smartOn) {
                msEnv = msCoef * msEnv + (1 - msCoef) * msSum   // channel sum, as BS.1770
                updateSmartGain(frame, s)
            } else {
                smartGain += (1f - smartGain) * smartUpCoef
            }
            val g = preampSmoothed * smartGain
            for (c in 0 until ch) {
                val v = frame[c] * g
                frame[c] = v
                val a = abs(v)
                if (a > pk) pk = a
            }
            emit(frame, pk, ceilLin, out)
        }
        inputBuffer.position(pos0 + frames * ch * 4)
        out.flip()
    }

    private fun biquad(x: Double, b: DoubleArray, a: DoubleArray, st: DoubleArray, o: Int): Double {
        val y = b[0] * x + b[1] * st[o] + b[2] * st[o + 1] - a[0] * st[o + 2] - a[1] * st[o + 3]
        st[o + 1] = st[o]; st[o] = x
        st[o + 3] = st[o + 2]; st[o + 2] = y
        return y
    }

    private var smartCounter = 0
    private fun updateSmartGain(frame: FloatArray, s: LoudnessSettings) {
        var pk = 0f
        for (c in 0 until ch) pk = max(pk, abs(frame[c]))
        slowPeak = max(pk, slowPeak * peakDecay)
        if ((smartCounter++ and 0xFF) != 0) return
        val loudness = -0.691 + 10.0 * log10(msEnv + 1e-12)
        var target = 1f
        if (loudness > -55.0) { // gate silence so fades don't pump up the noise floor
            var boostDb = (s.targetLufs - loudness).coerceIn(0.0, s.maxBoostDb.toDouble())
            // Do not ask for more than ~6 dB of limiting given this material's recent peaks.
            val peakDb = 20.0 * log10(max(slowPeak, 1e-6f).toDouble())
            val headroom = s.ceilingDb - peakDb + 6.0
            boostDb = min(boostDb, max(0.0, headroom))
            target = 10.0.pow(boostDb / 20.0).toFloat()
        } else {
            target = smartGain
        }
        val coef = if (target < smartGain) smartDownCoef else smartUpCoef
        smartGain += (target - smartGain) * min(1f, coef * 256f)
    }

    /** Push one frame (already gained) through the look-ahead limiter and write the delayed frame. */
    private fun emit(frame: FloatArray, peak: Float, ceil: Float, out: ByteBuffer) {
        // sliding window max over the last n frames
        while (dqTail > dqHead && peakVal[(dqTail - 1) % dqCap] <= peak) dqTail--
        peakVal[dqTail % dqCap] = peak; peakIdx[dqTail % dqCap] = frameIdx; dqTail++
        while (peakIdx[dqHead % dqCap] <= frameIdx - n) dqHead++
        val wmax = peakVal[dqHead % dqCap]
        var gmin = if (wmax > ceil) ceil / wmax else 1f
        // release smoothing (rises slowly, falls instantly) -> never above gmin
        val rising = relState + (1f - relState) * relCoef
        relState = min(gmin, rising)
        gmin = relState
        // boxcar over n values
        gSum += gmin - gRing[gRingPos]
        gRing[gRingPos] = gmin
        gRingPos = (gRingPos + 1) % n
        var gs = (gSum / n).toFloat()
        if (gs > 1f) gs = 1f
        // delayed output (n-1 frames)
        val base = delayPos * ch
        for (c in 0 until ch) {
            val d = delay[base + c]
            delay[base + c] = frame[c]
            var y = d * gs
            if (y > ceil) y = ceil else if (y < -ceil) y = -ceil // belt and braces
            out.putFloat(y)
        }
        delayPos = (delayPos + 1) % dly
        frameIdx++
    }

    override fun onQueueEndOfStream() {
        // Drain the look-ahead tail by feeding silence for n frames.
        val frames = dly
        val out = replaceOutputBuffer(frames * ch * 4).order(ByteOrder.nativeOrder())
        val zero = FloatArray(ch)
        val ceil = 10.0.pow(min(0.0, LoudnessSettings.ceilingDb.toDouble()) / 20.0).toFloat()
        for (i in 0 until frames) emit(zero, 0f, ceil, out)
        out.flip()
    }
}
