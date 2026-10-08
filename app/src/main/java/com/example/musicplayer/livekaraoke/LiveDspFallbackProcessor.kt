package com.example.musicplayer.livekaraoke

import com.bmwanje.audiophile.vocalremover.NativeVocalRemover
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import kotlin.math.min

/**
 * Low-latency, bounded fallback for devices that cannot sustain 9482 neural
 * inference in real time.
 */
internal class LiveDspFallbackProcessor(
    sampleRate: Int,
    settings: LiveKaraokeSettingsSnapshot,
    private val emit: (VocalSeparatorCore.Stereo) -> Unit,
    private val profiler: LiveKaraokeStageProfiler? = null,
) : AutoCloseable {

    companion object {
        private const val BLOCK = 8192
        private const val QUEUE_INITIAL = 32_768
        private const val QUEUE_MAX = 65_536
        private const val FLUSH_EXTRA_SAMPLES = 4096
    }

    private val native = NativeVocalRemover(sampleRate)
    private val workL = FloatArray(BLOCK)
    private val workR = FloatArray(BLOCK)
    private val zeroL = FloatArray(BLOCK)
    private val zeroR = FloatArray(BLOCK)
    private val outL = FloatArray(BLOCK)
    private val outR = FloatArray(BLOCK)
    private val delayed =
        StereoDelayQueue(
            initialCapacity = QUEUE_INITIAL,
            maxCapacity = QUEUE_MAX,
        )

    private var sourceSamples = 0L
    private var emittedSamples = 0L
    private var finished = false

    init {
        native.reset()
        native.setNeuralStemMode(false)
        native.setDepth(settings.depth)
        native.setFocus(settings.focus)
        native.setTransientProtection(settings.transientProtection)
        native.setDryWet(settings.dryWet)
        native.setStemGainDb(settings.stemGainDb)
        native.setOutputGainDb(settings.outputGainDb)
        native.setCeilingDb(settings.ceilingDb)
    }

    fun push(block: VocalSeparatorCore.Stereo) {
        check(!finished) { "Live DSP fallback is already finished" }
        require(block.size > 0)

        var offset = 0
        while (offset < block.size) {
            val n = min(BLOCK, block.size - offset)
            System.arraycopy(block.left, offset, workL, 0, n)
            System.arraycopy(block.right, offset, workR, 0, n)

            val dspStart = System.nanoTime()
            native.processBlock(
                mixL = workL,
                mixR = workR,
                vocalL = zeroL,
                vocalR = zeroR,
                outL = outL,
                outR = outR,
                count = n,
            )
            profiler?.record(
                LiveKaraokeStageProfiler.Stage.NATIVE_DSP,
                System.nanoTime() - dspStart,
            )

            delayed.add(outL, outR, n)
            sourceSamples += n.toLong()
            drainReady()

            offset += n
        }
    }

    fun finish() {
        check(!finished) { "Live DSP fallback is already finished" }
        finished = true

        var remaining = native.latencySamples() + FLUSH_EXTRA_SAMPLES
        while (remaining > 0 && emittedSamples < sourceSamples) {
            val n = min(BLOCK, remaining)

            val dspStart = System.nanoTime()
            native.processBlock(
                mixL = zeroL,
                mixR = zeroR,
                vocalL = zeroL,
                vocalR = zeroR,
                outL = outL,
                outR = outR,
                count = n,
            )
            profiler?.record(
                LiveKaraokeStageProfiler.Stage.NATIVE_DSP,
                System.nanoTime() - dspStart,
            )

            delayed.add(outL, outR, n)
            drainReady()
            remaining -= n
        }

        check(emittedSamples == sourceSamples) {
            "Fast Live DSP alignment emitted " +
                emittedSamples +
                " samples; expected " +
                sourceSamples
        }
    }

    private fun drainReady() {
        val ready =
            delayed.available() - native.latencySamples()
        if (ready <= 0) return

        var remaining = min(
            ready,
            BLOCK,
        )
        while (remaining > 0 && emittedSamples < sourceSamples) {
            val n =
                min(
                    remaining,
                    (sourceSamples - emittedSamples)
                        .coerceAtMost(BLOCK.toLong())
                        .toInt(),
                )
            delayed.readInto(outL, outR, n)
            emit(
                VocalSeparatorCore.Stereo(
                    outL.copyOf(n),
                    outR.copyOf(n),
                )
            )
            emittedSamples += n.toLong()
            remaining -= n
        }
    }

    override fun close() {
        delayed.clear()
        runCatching { native.close() }
    }
}

private class StereoDelayQueue(
    initialCapacity: Int,
    private val maxCapacity: Int,
) {
    private var left = FloatArray(initialCapacity)
    private var right = FloatArray(initialCapacity)
    private var read = 0
    private var size = 0

    fun available(): Int = size

    fun add(inputL: FloatArray, inputR: FloatArray, count: Int) {
        require(count in 1..inputL.size)
        require(count <= inputR.size)

        if (read > 0 && read + size + count > left.size) compact()
        ensureCapacity(size + count)

        val write = read + size
        System.arraycopy(inputL, 0, left, write, count)
        System.arraycopy(inputR, 0, right, write, count)
        size += count
    }

    fun readInto(outL: FloatArray, outR: FloatArray, count: Int) {
        require(count in 1..size)
        System.arraycopy(left, read, outL, 0, count)
        System.arraycopy(right, read, outR, 0, count)
        read += count
        size -= count
        if (size == 0) read = 0
    }

    fun clear() {
        read = 0
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= left.size) return
        compact()
        if (required <= left.size) return

        var capacity = left.size
        while (capacity < required && capacity < maxCapacity) {
            capacity = min(maxCapacity, capacity * 2)
        }
        check(capacity >= required) {
            "Fast Live DSP delay queue exceeded $maxCapacity samples"
        }
        left = left.copyOf(capacity)
        right = right.copyOf(capacity)
    }

    private fun compact() {
        if (read == 0) return
        if (size > 0) {
            System.arraycopy(left, read, left, 0, size)
            System.arraycopy(right, read, right, 0, size)
        }
        read = 0
    }
}

/** Snapshot of Live Karaoke DSP controls. */
internal data class LiveKaraokeSettingsSnapshot(
    val depth: Float,
    val focus: Float,
    val transientProtection: Float,
    val dryWet: Float,
    val stemGainDb: Float,
    val outputGainDb: Float,
    val ceilingDb: Float,
)
