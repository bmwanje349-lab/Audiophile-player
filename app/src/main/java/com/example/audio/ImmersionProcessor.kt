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

private class ImmersionRoom(sampleRate: Int) {
    private val preDelay = FloatArray(max(1, (sampleRate * 0.014).toInt()))
    private val left = FloatArray(max(8, (sampleRate * 0.021).toInt()))
    private val right = FloatArray(max(8, (sampleRate * 0.027).toInt()))
    private var prePos = 0
    private var leftPos = 0
    private var rightPos = 0
    private var dampL = 0f
    private var dampR = 0f
    var outL = 0f
        private set
    var outR = 0f
        private set

    fun process(input: Float) {
        val delayed = preDelay[prePos]
        preDelay[prePos] = input
        prePos = (prePos + 1) % preDelay.size
        outL = left[leftPos]
        outR = right[rightPos]
        dampL = outL * 0.55f + dampL * 0.45f
        dampR = outR * 0.55f + dampR * 0.45f
        left[leftPos] = delayed * 0.035f + dampL * 0.70f
        right[rightPos] = delayed * 0.032f + dampR * 0.68f
        leftPos = (leftPos + 1) % left.size
        rightPos = (rightPos + 1) % right.size
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
    private var blockCount = 0

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
        sideShelf = ImmersionBiquad().highShelf(sr, 1000.0, 0.0)
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
        blockCount = 0
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

            // Re-design only coefficients, not filter state, in the audio thread.
            if ((blockCount++ and 0x3F) == 0) {
                sideShelf.highShelf(sr = configuredRate, f = 1000.0, gainDb = 5.0 * sSpace)
            }
            val sideHp = sideHighPass.process(side)
            val enhancedSide = side + sideShelf.process(sideHp) - sideHp
            var outL = mid + side + (enhancedSide - side) * sSpace
            var outR = mid - side - (enhancedSide - side) * sSpace

            // Short, damped room reflections; filter state continues even when bypassed.
            val roomIn = roomLowPass.process(roomHighPass.process(mid + 0.5 * side))
            room.process(roomIn.toFloat())
            outL += room.outL * sAmbience * 0.35
            outR += room.outR * sAmbience * 0.35

            // Restrained even-harmonic air with fast envelope normalisation.
            val bandL = airLowPassL.process(airHighPassL.process(outL))
            envL = max(abs(bandL), envL * envCoef)
            val airL = airOutputHighPassL.process((bandL * bandL) / (envL + 1.0e-3))
            val bandR = airLowPassR.process(airHighPassR.process(outR))
            envR = max(abs(bandR), envR * envCoef)
            val airR = airOutputHighPassR.process((bandR * bandR) / (envR + 1.0e-3))
            outL += airL * sAir * 0.35
            outR += airR * sAir * 0.35

            // Mono bass harmonics add punch/translation without boosting sub-bass energy.
            val bassFundamental = bassLowPass.process(mid)
            bassEnv = max(abs(bassFundamental), bassEnv * bassEnvCoef)
            val second = bassFundamental * bassFundamental / (bassEnv + 1.0e-3)
            val third = tanh(6.0 * bassFundamental) / 6.0
            val bassHarmonic = bassLowPassOut.process(bassHighPassOut.process(0.8 * second + 0.6 * third))
            outL += bassHarmonic * sBass * 0.45
            outR += bassHarmonic * sBass * 0.45

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
