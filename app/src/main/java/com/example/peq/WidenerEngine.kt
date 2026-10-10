package com.example.peq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WidenerNative {
    init {
        System.loadLibrary("widener")
    }

    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun prepare(handle: Long, sampleRate: Int)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun setEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun setWidth(handle: Long, width: Float)
    @JvmStatic external fun setLowCrossoverHz(handle: Long, hz: Float)
    @JvmStatic external fun setHighCrossoverHz(handle: Long, hz: Float)
    @JvmStatic external fun setBassMonoFrequencyHz(handle: Long, hz: Float)
    @JvmStatic external fun setHaasDelayMs(handle: Long, ms: Float)
    @JvmStatic external fun setHaasMix(handle: Long, mix: Float)
    @JvmStatic external fun setDryWet(handle: Long, mix: Float)
    @JvmStatic external fun setOutputGainDb(handle: Long, db: Float)
    @JvmStatic external fun setOutputCeilingDb(handle: Long, db: Float)
    @JvmStatic external fun setLimiterSafetyMarginDb(handle: Long, db: Float)
    @JvmStatic external fun setAutoLevel(handle: Long, enabled: Boolean)
    @JvmStatic external fun latencyFrames(handle: Long): Int
    @JvmStatic external fun pendingFrames(handle: Long): Int
    @JvmStatic external fun process(
        handle: Long,
        buffer: ByteBuffer,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int
    @JvmStatic external fun drain(
        handle: Long,
        buffer: ByteBuffer,
        maxFrames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int
}

/** Kotlin control facade for ProfessionalStereoWidenerDSP_v10.h. */
class WidenerEngine : AutoCloseable {
    @Volatile private var handle: Long = WidenerNative.create()

    var enabled: Boolean = true
        set(value) {
            field = value
            if (handle != 0L) WidenerNative.setEnabled(handle, value)
        }

    var width: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 2.5f)
            if (handle != 0L) WidenerNative.setWidth(handle, field)
        }

    var lowCrossoverHz: Float = 180f
        set(value) {
            field = value.coerceIn(40f, 400f)
            if (handle != 0L) WidenerNative.setLowCrossoverHz(handle, field)
        }

    var highCrossoverHz: Float = 3200f
        set(value) {
            field = value.coerceIn(1000f, 10000f)
            if (handle != 0L) WidenerNative.setHighCrossoverHz(handle, field)
        }

    var bassMonoFrequencyHz: Float = 80f
        set(value) {
            field = value.coerceIn(20f, 250f)
            if (handle != 0L) WidenerNative.setBassMonoFrequencyHz(handle, field)
        }

    var haasDelayMs: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 20f)
            if (handle != 0L) WidenerNative.setHaasDelayMs(handle, field)
        }

    var haasMix: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (handle != 0L) WidenerNative.setHaasMix(handle, field)
        }

    var dryWet: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (handle != 0L) WidenerNative.setDryWet(handle, field)
        }

    var outputGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(-12f, 6f)
            if (handle != 0L) WidenerNative.setOutputGainDb(handle, field)
        }

    var outputCeilingDb: Float = -1f
        set(value) {
            field = value.coerceIn(-12f, 0f)
            if (handle != 0L) WidenerNative.setOutputCeilingDb(handle, field)
        }

    var limiterSafetyMarginDb: Float = 0.25f
        set(value) {
            field = value.coerceIn(0f, 3f)
            if (handle != 0L) WidenerNative.setLimiterSafetyMarginDb(handle, field)
        }

    var autoLevel: Boolean = true
        set(value) {
            field = value
            if (handle != 0L) WidenerNative.setAutoLevel(handle, value)
        }

    val latencyFrames: Int
        get() = if (handle != 0L) WidenerNative.latencyFrames(handle) else 0

    init {
        if (handle != 0L) {
            applyAll()
        }
    }

    private fun applyAll() {
        WidenerNative.setEnabled(handle, enabled)
        WidenerNative.setWidth(handle, width)
        WidenerNative.setLowCrossoverHz(handle, lowCrossoverHz)
        WidenerNative.setHighCrossoverHz(handle, highCrossoverHz)
        WidenerNative.setBassMonoFrequencyHz(handle, bassMonoFrequencyHz)
        WidenerNative.setHaasDelayMs(handle, haasDelayMs)
        WidenerNative.setHaasMix(handle, haasMix)
        WidenerNative.setDryWet(handle, dryWet)
        WidenerNative.setOutputGainDb(handle, outputGainDb)
        WidenerNative.setOutputCeilingDb(handle, outputCeilingDb)
        WidenerNative.setLimiterSafetyMarginDb(handle, limiterSafetyMarginDb)
        WidenerNative.setAutoLevel(handle, autoLevel)
    }

    internal fun setSampleRate(sampleRate: Int) {
        if (handle != 0L) {
            WidenerNative.prepare(handle, sampleRate)
            applyAll()
        }
    }

    internal fun reset() {
        if (handle != 0L) WidenerNative.reset(handle)
    }

    internal fun process(
        buf: ByteBuffer,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int {
        return if (handle != 0L) {
            WidenerNative.process(handle, buf, frames, channels, isFloat)
        } else frames
    }

    internal fun pendingFrames(stereo: Boolean): Int {
        return if (stereo && handle != 0L) WidenerNative.pendingFrames(handle) else 0
    }

    internal fun drain(
        buf: ByteBuffer,
        maxFrames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int {
        return if (handle != 0L) {
            WidenerNative.drain(handle, buf, maxFrames, channels, isFloat)
        } else 0
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) WidenerNative.destroy(h)
    }
}

@UnstableApi
class WidenerAudioProcessor(
    private val engine: WidenerEngine,
) : BaseAudioProcessor() {
    private var isFloat = false
    private var channels = 2
    private var bytesPerSample = 2
    private var activeStereo = false

    override fun onConfigure(format: AudioFormat): AudioFormat {
        isFloat = when (format.encoding) {
            C.ENCODING_PCM_16BIT -> {
                bytesPerSample = 2
                false
            }
            C.ENCODING_PCM_FLOAT -> {
                bytesPerSample = 4
                true
            }
            else -> throw UnhandledAudioFormatException(format)
        }

        channels = format.channelCount
        activeStereo = channels == 2
        engine.setSampleRate(format.sampleRate)
        return format
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return

        val frameSize = channels * bytesPerSample
        if (frameSize <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val wholeBytes = size - (size % frameSize)
        if (wholeBytes <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val frames = wholeBytes / frameSize
        val out = replaceOutputBuffer(wholeBytes)
        out.order(ByteOrder.nativeOrder())
        out.position(0)
        out.limit(wholeBytes)

        val src = inputBuffer.slice().order(ByteOrder.nativeOrder())
        src.limit(wholeBytes)
        out.put(src)
        inputBuffer.position(inputBuffer.position() + wholeBytes)
        out.flip()

        val produced = if (activeStereo) {
            engine.process(out, frames, channels, isFloat)
        } else {
            frames
        }

        out.position(0)
        out.limit(produced * frameSize)
    }

    override fun onQueueEndOfStream() {
        if (!activeStereo) return

        val pending = engine.pendingFrames(activeStereo)
        if (pending <= 0) return

        val frameSize = channels * bytesPerSample
        val out = replaceOutputBuffer(pending * frameSize)
        out.order(ByteOrder.nativeOrder())
        out.position(0)
        out.limit(pending * frameSize)

        val produced = engine.drain(
            buf = out,
            maxFrames = pending,
            channels = channels,
            isFloat = isFloat,
        )

        out.position(0)
        out.limit(produced * frameSize)
    }

    override fun onFlush() {
        engine.reset()
    }

    override fun onReset() {
        engine.reset()
    }
}
