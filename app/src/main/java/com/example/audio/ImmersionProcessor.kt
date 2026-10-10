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
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

object ImmersionSettings {
    @Volatile var enabled = true
    @Volatile var space = 0.35f
    @Volatile var ambience = 0.30f
    @Volatile var air = 0.30f
    @Volatile var bass = 0.30f
    private const val PREFS = "immersion_settings"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", true)
        space = p.getFloat("space", 0.35f).coerceIn(0f, 1f)
        ambience = p.getFloat("ambience", 0.30f).coerceIn(0f, 1f)
        air = p.getFloat("air", 0.30f).coerceIn(0f, 1f)
        bass = p.getFloat("bass", 0.30f).coerceIn(0f, 1f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putFloat("space", space)
            .putFloat("ambience", ambience)
            .putFloat("air", air)
            .putFloat("bass", bass)
            .apply()
    }
}

private class ImmersionBiquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    fun highPass(sr: Int, f: Double, q: Double = 0.7071): ImmersionBiquad {
        val w = 2.0 * PI * f / sr
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        set((1.0 + c) / 2.0, -(1.0 + c), (1.0 + c) / 2.0, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
        return this
    }

    fun lowPass(sr: Int, f: Double, q: Double = 0.7071): ImmersionBiquad {
        val w = 2.0 * PI * f / sr
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        set((1.0 - c) / 2.0, 1.0 - c, (1.0 - c) / 2.0, 1.0 + alpha, -2.0 * c, 1.0 - alpha)
        return this
    }

    fun highShelf(sr: Int, f: Double, gainDb: Double): ImmersionBiquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * f / sr
        val c = cos(w)
        val s = sin(w)
        val alpha = s / (2.0 * 0.7071)
        val sq = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1.0) + (a - 1.0) * c + sq),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * c),
            a * ((a + 1.0) + (a - 1.0) * c - sq),
            (a + 1.0) - (a - 1.0) * c + sq,
            2.0 * ((a - 1.0) - (a + 1.0) * c),
            (a + 1.0) - (a - 1.0) * c - sq
        )
        return this
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/**
 * Small-room reflection and reverb network.
 *
 * Early taps establish space; four damped feedback combs per side and two
 * all-pass diffusers create a smoother late tail than a single feedback delay.
 * A symmetric mono-mid room send avoids biasing the left or right channel.
 */
private class ImmersionRoom(sampleRate: Int) {
    private val sr = sampleRate.coerceAtLeast(8000)
    private val reflectionBuffer =
        FloatArray(max(2, (sr * 0.034).toInt() + 1))
    private var reflectionWrite = 0

    private fun frames(ms: Double): Int =
        max(1, (sr * ms / 1000.0).toInt())

    private val tapDelaysL = intArrayOf(
        frames(7.1), frames(13.9), frames(21.1), frames(29.7),
    )
    private val tapDelaysR = intArrayOf(
        frames(8.3), frames(15.7), frames(23.3), frames(31.3),
    )
    private val tapWeights = floatArrayOf(0.42f, 0.28f, 0.18f, 0.12f)

    private fun comb(ms: Double): ImmersionComb {
        val delay = frames(ms)
        // About 0.62 s nominal T60, based on the actual delay of each line.
        val feedback =
            10.0.pow(-3.0 * delay.toDouble() / sr.toDouble() / 0.62).toFloat()
        return ImmersionComb(delay, feedback, 0.24f)
    }

    private val combsL = arrayOf(
        comb(29.7), comb(37.1), comb(41.1), comb(43.7),
    )
    private val combsR = arrayOf(
        comb(31.1), comb(38.3), comb(42.7), comb(46.1),
    )
    private val diffusersL = arrayOf(
        ImmersionAllPass(frames(5.1), 0.48f),
        ImmersionAllPass(frames(1.7), 0.32f),
    )
    private val diffusersR = arrayOf(
        ImmersionAllPass(frames(5.5), 0.48f),
        ImmersionAllPass(frames(1.9), 0.32f),
    )

    var outL = 0f
        private set
    var outR = 0f
        private set

    private fun tap(delay: Int): Float {
        var index = reflectionWrite - delay
        if (index < 0) index += reflectionBuffer.size
        return reflectionBuffer[index]
    }

    fun process(input: Float) {
        reflectionBuffer[reflectionWrite] = input

        var earlyL = 0f
        var earlyR = 0f
        for (i in tapWeights.indices) {
            earlyL += tap(tapDelaysL[i]) * tapWeights[i]
            earlyR += tap(tapDelaysR[i]) * tapWeights[i]
        }

        var lateL = 0f
        var lateR = 0f
        for (comb in combsL) lateL += comb.process(input)
        for (comb in combsR) lateR += comb.process(input)
        lateL *= 0.25f
        lateR *= 0.25f
        for (diffuser in diffusersL) lateL = diffuser.process(lateL)
        for (diffuser in diffusersR) lateR = diffuser.process(lateR)

        // Conservative wet gains keep ambience below the direct signal.
        outL = earlyL * 0.28f + lateL * 0.30f
        outR = earlyR * 0.28f + lateR * 0.30f

        reflectionWrite++
        if (reflectionWrite >= reflectionBuffer.size) reflectionWrite = 0
    }
}

private class ImmersionComb(
    delaySamples: Int,
    private val feedback: Float,
    private val damping: Float,
) {
    private val buffer = FloatArray(max(1, delaySamples))
    private var position = 0
    private var dampingState = 0f

    fun process(input: Float): Float {
        val delayed = buffer[position]
        dampingState += damping * (delayed - dampingState)
        buffer[position] = input + dampingState * feedback
        position++
        if (position >= buffer.size) position = 0
        return delayed
    }
}

private class ImmersionAllPass(
    delaySamples: Int,
    private val coefficient: Float,
) {
    private val buffer = FloatArray(max(1, delaySamples))
    private var position = 0

    fun process(input: Float): Float {
        val delayed = buffer[position]
        val output = delayed - coefficient * input
        buffer[position] = input + coefficient * output
        position++
        if (position >= buffer.size) position = 0
        return output
    }
}

@UnstableApi
class ImmersionProcessor : BaseAudioProcessor() {
    private var configuredRate = 44100
    private lateinit var sideHighPass: ImmersionBiquad
    private lateinit var sideShelf: ImmersionBiquad
    private lateinit var roomHighPass: ImmersionBiquad
    private lateinit var roomLowPass: ImmersionBiquad
    private lateinit var room: ImmersionRoom
    private lateinit var airHighPassL: ImmersionBiquad
    private lateinit var airLowPassL: ImmersionBiquad
    private lateinit var airOutputHighPassL: ImmersionBiquad
    private lateinit var airHighPassR: ImmersionBiquad
    private lateinit var airLowPassR: ImmersionBiquad
    private lateinit var airOutputHighPassR: ImmersionBiquad
    private lateinit var bassLowPass: ImmersionBiquad
    private lateinit var bassHighPassOut: ImmersionBiquad
    private lateinit var bassLowPassOut: ImmersionBiquad
    private var envL = 0.0
    private var envR = 0.0
    private var envCoef = 0.0
    private var bassEnv = 0.0
    private var bassEnvCoef = 0.0
    private var smooth = 0.0
    private var sSpace = 0.0
    private var sAmbience = 0.0
    private var sAir = 0.0
    private var sBass = 0.0
    private var sOn = 0.0
    
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredRate = inputAudioFormat.sampleRate
        return if (inputAudioFormat.channelCount == 2) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush() {
        val sr = configuredRate
        sideHighPass = ImmersionBiquad().highPass(sr, 140.0)
        sideShelf = ImmersionBiquad().highShelf(sr, 1000.0, 3.0)
        roomHighPass = ImmersionBiquad().highPass(sr, 220.0)
        roomLowPass = ImmersionBiquad().lowPass(sr, 6500.0)
        room = ImmersionRoom(sr)
        airHighPassL = ImmersionBiquad().highPass(sr, 2500.0)
        airLowPassL = ImmersionBiquad().lowPass(sr, 5000.0)
        airOutputHighPassL = ImmersionBiquad().highPass(sr, 6500.0)
        airHighPassR = ImmersionBiquad().highPass(sr, 2500.0)
        airLowPassR = ImmersionBiquad().lowPass(sr, 5000.0)
        airOutputHighPassR = ImmersionBiquad().highPass(sr, 6500.0)
        bassLowPass = ImmersionBiquad().lowPass(sr, 100.0)
        bassHighPassOut = ImmersionBiquad().highPass(sr, 120.0)
        bassLowPassOut = ImmersionBiquad().lowPass(sr, 320.0)
        envL = 0.0; envR = 0.0
        envCoef = exp(-1.0 / (0.005 * sr))
        bassEnv = 0.0
        bassEnvCoef = exp(-1.0 / (0.020 * sr))
        smooth = 1.0 - exp(-1.0 / (0.050 * sr))
        sSpace = 0.0; sAmbience = 0.0; sAir = 0.0; sBass = 0.0; sOn = 0.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / 8
        if (frames <= 0) return
        val base = inputBuffer.position()
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(frames * 8).order(ByteOrder.nativeOrder())
        val settings = ImmersionSettings
        val onTarget = if (settings.enabled) 1.0 else 0.0
        val tSpace = settings.space.toDouble().coerceIn(0.0, 1.0) * onTarget
        val tAmb = settings.ambience.toDouble().coerceIn(0.0, 1.0) * onTarget
        val tAir = settings.air.toDouble().coerceIn(0.0, 1.0) * onTarget
        val tBass = settings.bass.toDouble().coerceIn(0.0, 1.0) * onTarget

        for (frame in 0 until frames) {
            sOn += (onTarget - sOn) * smooth
            sSpace += (tSpace - sSpace) * smooth
            sAmbience += (tAmb - sAmbience) * smooth
            sAir += (tAir - sAir) * smooth
            sBass += (tBass - sBass) * smooth

            var l = input.getFloat(base + frame * 8).toDouble()
            var r = input.getFloat(base + frame * 8 + 4).toDouble()
            if (!l.isFinite()) l = 0.0
            if (!r.isFinite()) r = 0.0
            val mid = 0.5 * (l + r)
            val side = 0.5 * (l - r)

            // Blend toward a fixed, gentle +3 dB shelf. Fixed coefficients
            // avoid trigonometric filter redesigns on the audio thread.
            val sideHp = sideHighPass.process(side)
            val shelfDifference = sideShelf.process(sideHp) - sideHp
            val enhancedSide = side + sSpace * shelfDifference
            var outL = mid + enhancedSide
            var outR = mid - enhancedSide

            // Short, damped room reflections; filter state continues even when bypassed.
            val roomIn = roomLowPass.process(roomHighPass.process(mid))
            room.process(roomIn.toFloat())
            outL += room.outL * sAmbience
            outR += room.outR * sAmbience

            // Restrained even-harmonic air with fast envelope normalisation.
            val bandL = airLowPassL.process(airHighPassL.process(outL))
            envL = max(abs(bandL), envL * envCoef)
            val airL = airOutputHighPassL.process((bandL * bandL) / (envL + 1.0e-3))
            val bandR = airLowPassR.process(airHighPassR.process(outR))
            envR = max(abs(bandR), envR * envCoef)
            val airR = airOutputHighPassR.process((bandR * bandR) / (envR + 1.0e-3))
            outL += airL * sAir * 0.22
            outR += airR * sAir * 0.22

            // Mono bass harmonics add punch/translation without boosting sub-bass energy.
            val bassFundamental = bassLowPass.process(mid)
            bassEnv = max(abs(bassFundamental), bassEnv * bassEnvCoef)
            val second = bassFundamental * bassFundamental / (bassEnv + 1.0e-3)
            val third = tanh(6.0 * bassFundamental) / 6.0
            val bassHarmonic = bassLowPassOut.process(bassHighPassOut.process(0.8 * second + 0.6 * third))
            outL += bassHarmonic * sBass * 0.35
            outR += bassHarmonic * sBass * 0.35

            // Still process every filter/delay while disabled; output dry to avoid stale state on re-enable.
            if (sOn < 1.0e-4 && onTarget == 0.0) {
                output.putFloat(l.toFloat())
                output.putFloat(r.toFloat())
            } else {
                output.putFloat(outL.toFloat())
                output.putFloat(outR.toFloat())
            }
        }
        inputBuffer.position(base + frames * 8)
        output.flip()
    }
}
