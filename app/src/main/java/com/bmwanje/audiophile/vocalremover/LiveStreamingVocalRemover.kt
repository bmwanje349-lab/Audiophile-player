package com.bmwanje.audiophile.vocalremover

import kotlin.math.min

/**
 * End-to-end bounded-memory offline neural render.
 *
 * Mix PCM arrives in source-rate blocks. The source stream is resampled to
 * 44.1 kHz, passed through the fixed-size MDX-Net runner, resampled back, and
 * paired with the original mix as it becomes available. The native premium
 * DSP then processes small blocks and its fixed latency is removed without
 * ever accumulating a full-song FloatArray.
 */
class LiveStreamingVocalRemover(
    private val sampleRate: Int,
    private val runner: MdxSeparatorCore.Runner,
    private val modelSpec: MdxModelSpec,
    private val native: NativeVocalRemover,
    private val emit: (VocalSeparatorCore.Stereo) -> Unit,
    private val profiler: LiveKaraokeStageProfiler? = null,
) : AutoCloseable {

    companion object {
        private const val DSP_BLOCK = 8192
        private const val MIX_QUEUE_INITIAL = 32_768
        private const val MIX_QUEUE_MAX = 1_048_576
    }

    private val mixQueue = StereoSampleQueue(MIX_QUEUE_INITIAL, MIX_QUEUE_MAX)
    private val vocalQueue = StereoSampleQueue(MIX_QUEUE_INITIAL, MIX_QUEUE_MAX)

    private val processL = FloatArray(DSP_BLOCK)
    private val processR = FloatArray(DSP_BLOCK)
    private val vocalL = FloatArray(DSP_BLOCK)
    private val vocalR = FloatArray(DSP_BLOCK)
    private val outputL = FloatArray(DSP_BLOCK)
    private val outputR = FloatArray(DSP_BLOCK)

    private val aligner =
        NativeOutputAligner(
            latencySamples = native.latencySamples(),
            emit = emit,
        )

    private lateinit var sourceToModel: StreamingWindowedSincResampler
    private lateinit var mdx: MdxSeparatorCore.StreamingSeparator
    private lateinit var modelToSource: StreamingWindowedSincResampler

    private var sourceSamples = 0L
    private var finished = false

    init {
        require(sampleRate >= 8_000) { "Unsupported sample rate: $sampleRate" }

        /*
         * The native processor is stateful. Reset it once per complete render,
         * put it into neural-stem mode, and never reset between decoder blocks.
         */
        native.reset()
        native.setNeuralStemMode(true)

        modelToSource =
            StreamingWindowedSincResampler(
                sourceRate = MdxSeparatorCore.MODEL_SAMPLE_RATE,
                targetRate = sampleRate,
            ) { modelBlock ->
                vocalQueue.add(modelBlock)
                drainPairs()
            }

        mdx =
            MdxSeparatorCore.StreamingSeparator(
                modelSpec = modelSpec,
                runner = runner,
            ) { vocalModelBlock ->
                modelToSource.push(vocalModelBlock)
            }

        sourceToModel =
            StreamingWindowedSincResampler(
                sourceRate = sampleRate,
                targetRate = MdxSeparatorCore.MODEL_SAMPLE_RATE,
            ) { modelInputBlock ->
                mdx.push(modelInputBlock)
            }
    }

    fun push(block: VocalSeparatorCore.Stereo) {
        check(!finished) { "StreamingVocalRemover is already finished" }
        require(block.size > 0)

        sourceSamples += block.size.toLong()
        profiler?.markSourceFrames(block.size)

        /*
         * Keep the original mix until its matching neural vocal samples have
         * arrived. The queue is bounded and normally only contains the MDX
         * algorithmic look-ahead plus a small block.
         */
        mixQueue.add(block)
        profiler?.measure(LiveKaraokeStageProfiler.Stage.NEURAL_PIPELINE) {
            sourceToModel.push(block)
        }
        drainPairs()
    }

    fun finish() {
        check(!finished) { "StreamingVocalRemover is already finished" }
        finished = true

        require(sourceSamples > 0L) {
            "Cannot render an empty audio stream"
        }
        require(sourceSamples <= Int.MAX_VALUE) {
            "Source track is too large for the Android FloatArray pipeline"
        }

        val expectedModelSamples =
            maxOf(
                1,
                (sourceSamples.toDouble() *
                    MdxSeparatorCore.MODEL_SAMPLE_RATE.toDouble() /
                    sampleRate.toDouble())
                    .roundToIntSafe(),
            )

        /*
         * Flush every stage in order. Each stage emits directly into the next
         * stage; no stage materializes the complete track.
         */
        sourceToModel.finish(expectedModelSamples)
        mdx.finish()
        modelToSource.finish(sourceSamples.toInt())
        drainPairs()

        check(mixQueue.available() == 0) {
            "Streaming render ended with " +
                mixQueue.available() +
                " unpaired mix samples"
        }
        check(vocalQueue.available() == 0) {
            "Streaming render ended with " +
                vocalQueue.available() +
                " unpaired vocal samples"
        }

        /*
         * PremiumVocalRemoverDSP intentionally exposes the same fixed latency
         * as its offline path. Feed a bounded zero tail so the delayed final
         * source samples emerge, then emit exactly the original track length.
         */
        aligner.finish(
            native = native,
            expectedSamples = sourceSamples.toInt(),
            processBlock = ::processNative,
        )
    }

    fun inputSamples(): Long = sourceSamples
    fun emittedSamples(): Long = aligner.emittedSamples()
    fun mdxInferenceCount(): Long = runner.inferenceCount()

    private fun drainPairs() {
        while (mixQueue.available() > 0 && vocalQueue.available() > 0) {
            val n = min(
                DSP_BLOCK,
                min(mixQueue.available(), vocalQueue.available()),
            )

            mixQueue.readInto(processL, processR, n)
            vocalQueue.readInto(vocalL, vocalR, n)
            processNative(
                processL,
                processR,
                vocalL,
                vocalR,
                outputL,
                outputR,
                n,
            )
            aligner.push(outputL, outputR, n)
        }
    }

    private fun processNative(
        mixL: FloatArray,
        mixR: FloatArray,
        stemL: FloatArray,
        stemR: FloatArray,
        outL: FloatArray,
        outR: FloatArray,
        n: Int,
    ) {
        profiler?.measure(LiveKaraokeStageProfiler.Stage.NATIVE_DSP) {
            native.processBlock(
                mixL = mixL,
            mixR = mixR,
            vocalL = stemL,
            vocalR = stemR,
            outL = outL,
            outR = outR,
                count = n,
            )
        }
    }

    override fun close() {
        mixQueue.clear()
        vocalQueue.clear()
        runCatching { native.close() }
        runCatching { runner.close() }
    }
}

private class StereoSampleQueue(
    initialCapacity: Int,
    private val maxCapacity: Int,
) {
    private var left = FloatArray(initialCapacity)
    private var right = FloatArray(initialCapacity)
    private var read = 0
    private var size = 0

    fun available(): Int = size

    fun add(block: VocalSeparatorCore.Stereo) {
        require(block.size > 0)

        if (read > 0 && read + size + block.size > left.size) {
            compact()
        }
        ensureCapacity(size + block.size)

        val write = read + size
        System.arraycopy(block.left, 0, left, write, block.size)
        System.arraycopy(block.right, 0, right, write, block.size)
        size += block.size
    }

    fun readInto(outL: FloatArray, outR: FloatArray, count: Int) {
        require(count in 1..size)
        require(outL.size >= count && outR.size >= count)

        System.arraycopy(left, read, outL, 0, count)
        System.arraycopy(right, read, outR, 0, count)
        read += count
        size -= count

        if (size == 0) {
            read = 0
        }
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
            "Streaming stereo queue exceeded " + maxCapacity + " samples"
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

private class NativeOutputAligner(
    private val latencySamples: Int,
    private val emit: (VocalSeparatorCore.Stereo) -> Unit,
) {
    private companion object {
        const val DSP_BLOCK = 8192
    }

    private val queue =
        StereoSampleQueue(
            initialCapacity = 32_768,
            maxCapacity = 65_536,
        )
    private val drainL = FloatArray(DSP_BLOCK)
    private val drainR = FloatArray(DSP_BLOCK)

    private var emitted = 0L
    private var expected: Int? = null

    init {
        require(latencySamples >= 0)
    }

    fun push(
        outL: FloatArray,
        outR: FloatArray,
        count: Int,
    ) {
        require(count in 1..outL.size)
        require(count <= outR.size)

        val left =
            if (count == outL.size) {
                outL
            } else {
                outL.copyOf(count)
            }

        val right =
            if (count == outR.size) {
                outR
            } else {
                outR.copyOf(count)
            }

        queue.add(
            VocalSeparatorCore.Stereo(
                left,
                right,
            )
        )
        drain()
    }

    fun finish(
        native: NativeVocalRemover,
        expectedSamples: Int,
        processBlock: (
            mixL: FloatArray,
            mixR: FloatArray,
            stemL: FloatArray,
            stemR: FloatArray,
            outL: FloatArray,
            outR: FloatArray,
            n: Int,
        ) -> Unit,
    ) {
        expected = expectedSamples
        drain()

        val zero = FloatArray(DSP_BLOCK)
        val outL = FloatArray(DSP_BLOCK)
        val outR = FloatArray(DSP_BLOCK)

        var remaining = native.latencySamples() + 2048
        while (remaining > 0 && emitted < expectedSamples.toLong()) {
            val n = min(DSP_BLOCK, remaining)

            processBlock(
                zero,
                zero,
                zero,
                zero,
                outL,
                outR,
                n,
            )

            push(outL, outR, n)
            remaining -= n
        }

        drain()

        check(emitted == expectedSamples.toLong()) {
            "Native output alignment emitted " +
                emitted +
                " samples; expected " +
                expectedSamples
        }

        queue.clear()
    }

    fun emittedSamples(): Long = emitted

    private fun drain() {
        val target = expected?.toLong() ?: Long.MAX_VALUE
        var ready = queue.available() - latencySamples

        while (ready > 0 && emitted < target) {
            val targetRemaining =
                (target - emitted)
                    .coerceAtMost(DSP_BLOCK.toLong())
                    .toInt()

            val n =
                min(
                    ready,
                    targetRemaining,
                )

            queue.readInto(
                drainL,
                drainR,
                n,
            )

            emit(
                VocalSeparatorCore.Stereo(
                    drainL.copyOf(n),
                    drainR.copyOf(n),
                )
            )

            emitted += n.toLong()
            ready = queue.available() - latencySamples
        }
    }
}

private fun Double.roundToIntSafe(): Int {
    val rounded = kotlin.math.round(this)
    require(rounded <= Int.MAX_VALUE.toDouble()) {
        "Resampled audio is too large"
    }
    return rounded.coerceAtLeast(1.0).toInt()
}
