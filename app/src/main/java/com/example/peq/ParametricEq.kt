package com.example.peq

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PeqNative {
    init {
        System.loadLibrary("peq")
    }

    @JvmStatic external fun create(numBands: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun setSampleRate(handle: Long, sampleRate: Int)
    @JvmStatic external fun setBand(
        handle: Long,
        index: Int,
        type: Int,
        freq: Float,
        gainDb: Float,
        q: Float,
        enabled: Boolean,
    )
    @JvmStatic external fun setPeqEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun setGraphicEqEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun setGraphicBand(
        handle: Long,
        index: Int,
        gainDb: Float,
        q: Float,
        enabled: Boolean,
    )
    @JvmStatic external fun setGraphicBands(
        handle: Long,
        gainsDb: FloatArray,
        q: Float,
    )
    @JvmStatic external fun setInputGainDb(handle: Long, db: Float)
    @JvmStatic external fun setOutputGainDb(handle: Long, db: Float)
    @JvmStatic external fun setBypass(handle: Long, bypass: Boolean)
    @JvmStatic external fun setLimiter(handle: Long, enabled: Boolean)
    @JvmStatic external fun setLimiterCeilingDb(handle: Long, db: Float)
    @JvmStatic external fun setLimiterSafetyDb(handle: Long, db: Float)
    @JvmStatic external fun setLimiterLookaheadMs(handle: Long, ms: Float)
    @JvmStatic external fun setLimiterReleaseMs(handle: Long, ms: Float)
    @JvmStatic external fun setFilterSmoothingMs(handle: Long, ms: Float)
    @JvmStatic external fun setGainSmoothingMs(handle: Long, ms: Float)
    @JvmStatic external fun setAutoGainEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun setAutoGainAmount(handle: Long, amount: Float)
    @JvmStatic external fun autoMakeupGainDb(handle: Long): Float
    @JvmStatic external fun setLinearPhaseEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun setLinearPhaseTaps(handle: Long, taps: Int)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun process(
        handle: Long,
        buffer: ByteBuffer,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int
    @JvmStatic external fun pendingFrames(handle: Long): Int
    @JvmStatic external fun latencyFrames(handle: Long): Int
    @JvmStatic external fun drain(
        handle: Long,
        buffer: ByteBuffer,
        maxFrames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int
    @JvmStatic external fun responseDb(handle: Long, freqs: FloatArray, out: FloatArray)
}

enum class FilterType(
    val id: Int,
    val label: String,
    val hasGain: Boolean,
    val hasQ: Boolean,
) {
    PEAKING(0, "Bell", true, true),
    LOW_SHELF(1, "Low shelf", true, false),
    HIGH_SHELF(2, "High shelf", true, false),
    HIGH_PASS(3, "High-pass", false, true),
    LOW_PASS(4, "Low-pass", false, true),
    NOTCH(5, "Notch", false, true),
}

data class PeqBand(
    val type: FilterType = FilterType.PEAKING,
    val freq: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
)

object PeqDefaults {
    val bands: List<PeqBand> = listOf(
        PeqBand(FilterType.LOW_SHELF, 60f, 0f, 0.707f),
        PeqBand(FilterType.PEAKING, 170f, 0f, 1.0f),
        PeqBand(FilterType.PEAKING, 400f, 0f, 1.0f),
        PeqBand(FilterType.PEAKING, 1000f, 0f, 1.0f),
        PeqBand(FilterType.PEAKING, 2500f, 0f, 1.0f),
        PeqBand(FilterType.PEAKING, 5000f, 0f, 1.0f),
        PeqBand(FilterType.PEAKING, 9000f, 0f, 1.0f),
        PeqBand(FilterType.HIGH_SHELF, 14000f, 0f, 0.707f),
    )
}

object PeqPresets {
    val all: Map<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass boost" to floatArrayOf(6f, 4f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Treble boost" to floatArrayOf(0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f),
        "Vocal clarity" to floatArrayOf(-2f, -1f, -2f, 1f, 3f, 3f, 1f, 0f),
        "Rock" to floatArrayOf(4f, 2f, -2f, -1f, 1f, 3f, 4f, 4f),
    )
}

class PeqEngine(
    requestedNumBands: Int = 16,
) : AutoCloseable {

    val numBands: Int = requestedNumBands.coerceIn(1, 16)

    @Volatile
    private var handle: Long = PeqNative.create(numBands)

    private val _bands = MutableList(numBands) {
        PeqDefaults.bands.getOrNull(it) ?: PeqBand(enabled = false)
    }

    val bands: List<PeqBand>
        get() = _bands

    /** Advanced parametric EQ stage. Independent from the 10-band Graphic EQ. */
    var peqEnabled: Boolean = true
        set(value) {
            field = value
            if (handle != 0L) PeqNative.setPeqEnabled(handle, value)
        }

    /** Standard 10-band Graphic EQ stage. Independent from the PEQ. */
    var graphicEqEnabled: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) PeqNative.setGraphicEqEnabled(handle, value)
        }

    var bypass: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) PeqNative.setBypass(handle, value)
        }

    var limiterEnabled: Boolean = true
        set(value) {
            field = value
            if (handle != 0L) PeqNative.setLimiter(handle, value)
        }

    // User-facing output ceiling. The native limiter also subtracts limiterSafetyDb.
    var limiterCeilingDb: Float = -1.0f
        set(value) {
            field = value.coerceIn(-24f, -0.01f)
            if (handle != 0L) PeqNative.setLimiterCeilingDb(handle, field)
        }

    // Additional TP protection margin. 0.10 dB is a conservative default for a
    // 4x true-peak estimator and floating-point implementation differences.
    var limiterSafetyDb: Float = 0.10f
        set(value) {
            field = value.coerceIn(0f, 2f)
            if (handle != 0L) PeqNative.setLimiterSafetyDb(handle, field)
        }

    var limiterLookaheadMs: Float = 2.0f
        set(value) {
            field = value.coerceIn(0f, 42f)
            if (handle != 0L) PeqNative.setLimiterLookaheadMs(handle, field)
        }

    var limiterReleaseMs: Float = 180f
        set(value) {
            field = value.coerceIn(10f, 2000f)
            if (handle != 0L) PeqNative.setLimiterReleaseMs(handle, field)
        }

    var filterSmoothingMs: Float = 8f
        set(value) {
            field = value.coerceIn(0f, 250f)
            if (handle != 0L) PeqNative.setFilterSmoothingMs(handle, field)
        }

    var gainSmoothingMs: Float = 5f
        set(value) {
            field = value.coerceIn(0f, 250f)
            if (handle != 0L) PeqNative.setGainSmoothingMs(handle, field)
        }

    /** Program-independent EQ curve makeup. This is intentionally not marketed as BS.1770 loudness normalization. */
    var autoGainEnabled: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) PeqNative.setAutoGainEnabled(handle, value)
        }

    /** 0 = no makeup, 1 = full curve compensation. */
    var autoGainAmount: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (handle != 0L) PeqNative.setAutoGainAmount(handle, field)
        }

    val autoMakeupGainDb: Float
        get() = if (handle != 0L) PeqNative.autoMakeupGainDb(handle) else 0f

    /** Optional causal symmetric FIR. Adds (taps - 1) / 2 frames of linear-phase delay. */
    var linearPhaseEnabled: Boolean = false
        set(value) {
            field = value
            if (handle != 0L) PeqNative.setLinearPhaseEnabled(handle, value)
        }

    var linearPhaseTaps: Int = 513
        set(value) {
            val clamped = value.coerceIn(257, 1025)
            field = if ((clamped and 1) == 0) clamped + 1 else clamped
            if (handle != 0L) PeqNative.setLinearPhaseTaps(handle, field)
        }

    var inputGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(-24f, 24f)
            if (handle != 0L) PeqNative.setInputGainDb(handle, field)
        }

    var outputGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(-24f, 24f)
            if (handle != 0L) PeqNative.setOutputGainDb(handle, field)
        }

    val latencyFrames: Int
        get() = if (handle != 0L) PeqNative.latencyFrames(handle) else 0

    init {
        if (handle != 0L) {
            PeqNative.setPeqEnabled(handle, peqEnabled)
            PeqNative.setGraphicEqEnabled(handle, graphicEqEnabled)
            PeqNative.setLimiter(handle, limiterEnabled)
            PeqNative.setLimiterCeilingDb(handle, limiterCeilingDb)
            PeqNative.setLimiterSafetyDb(handle, limiterSafetyDb)
            PeqNative.setLimiterLookaheadMs(handle, limiterLookaheadMs)
            PeqNative.setLimiterReleaseMs(handle, limiterReleaseMs)
            PeqNative.setFilterSmoothingMs(handle, filterSmoothingMs)
            PeqNative.setGainSmoothingMs(handle, gainSmoothingMs)
            PeqNative.setAutoGainEnabled(handle, autoGainEnabled)
            PeqNative.setAutoGainAmount(handle, autoGainAmount)
            PeqNative.setLinearPhaseTaps(handle, linearPhaseTaps)
            PeqNative.setLinearPhaseEnabled(handle, linearPhaseEnabled)
        }
        _bands.forEachIndexed { i, band -> push(i, band) }
    }

    fun setBand(index: Int, band: PeqBand) {
        if (index !in _bands.indices) return

        val frequency = if (band.freq.isFinite()) band.freq.coerceIn(10f, 20000f) else 1000f
        val gain = if (band.gainDb.isFinite()) band.gainDb.coerceIn(-36f, 36f) else 0f
        val qValue = if (band.q.isFinite()) band.q else 1f
        val clean = band.copy(
            freq = frequency,
            gainDb = gain,
            q = if (band.type == FilterType.LOW_SHELF || band.type == FilterType.HIGH_SHELF) {
                qValue.coerceIn(0.1f, 2f)
            } else {
                qValue.coerceIn(0.05f, 20f)
            },
        )
        _bands[index] = clean
        push(index, clean)
    }

    fun applyPreset(name: String): Boolean {
        val gains = PeqPresets.all[name] ?: return false
        for (i in 0 until numBands) {
            val defaultBand = PeqDefaults.bands.getOrNull(i)
            if (defaultBand != null) {
                setBand(i, defaultBand.copy(
                    gainDb = gains.getOrElse(i) { 0f },
                    enabled = true,
                ))
            } else {
                setBand(i, PeqBand(enabled = false))
            }
        }
        return true
    }

    fun responseDb(freqs: FloatArray, out: FloatArray) {
        require(out.size >= freqs.size) { "out must contain at least freqs.size elements" }
        if (handle != 0L) {
            PeqNative.responseDb(handle, freqs, out)
        } else {
            out.fill(0f)
        }
    }

    internal fun setGraphicBand(
        index: Int,
        gainDb: Float,
        q: Float,
        enabled: Boolean,
    ) {
        if (handle != 0L) {
            PeqNative.setGraphicBand(handle, index, gainDb, q, enabled)
        }
    }

    internal fun setGraphicBands(gainsDb: FloatArray, q: Float) {
        if (handle != 0L) {
            PeqNative.setGraphicBands(handle, gainsDb, q)
        }
    }


    internal fun setSampleRate(sr: Int) {
        if (handle != 0L) PeqNative.setSampleRate(handle, sr)
    }

    internal fun reset() {
        if (handle != 0L) PeqNative.reset(handle)
    }

    internal fun process(
        buf: ByteBuffer,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int {
        return if (handle != 0L) {
            PeqNative.process(handle, buf, frames, channels, isFloat)
        } else 0
    }

    internal fun pendingFrames(): Int {
        return if (handle != 0L) PeqNative.pendingFrames(handle) else 0
    }

    internal fun drain(
        buf: ByteBuffer,
        maxFrames: Int,
        channels: Int,
        isFloat: Boolean,
    ): Int {
        return if (handle != 0L) {
            PeqNative.drain(handle, buf, maxFrames, channels, isFloat)
        } else 0
    }

    private fun push(index: Int, band: PeqBand) {
        if (handle != 0L) {
            PeqNative.setBand(
                handle,
                index,
                band.type.id,
                band.freq,
                band.gainDb,
                band.q,
                band.enabled,
            )
        }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) PeqNative.destroy(h)
    }
}

@UnstableApi
class PeqAudioProcessor(
    private val engine: PeqEngine,
) : BaseAudioProcessor() {

    private var isFloat = false
    private var channels = 2
    private var bytesPerSample = 2

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

        val producedFrames = engine.process(
            buf = out,
            frames = frames,
            channels = channels,
            isFloat = isFloat,
        )

        out.position(0)
        out.limit(producedFrames * frameSize)
    }

    override fun onQueueEndOfStream() {
        val pending = engine.pendingFrames()
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
