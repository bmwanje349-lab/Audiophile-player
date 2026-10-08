package com.bmwanje.audiophile.vocalremover

import kotlin.math.min

/**
 * MDX-Net vocal separation orchestration.
 *
 * The chunking here follows the UVR MDX-Net reference path rather than applying
 * an arbitrary crossfade. Each model window contains n_fft/2 samples of left
 * context and n_fft/2 samples of right context, and only the central
 * chunkSize - n_fft samples are emitted. This removes the model's edge region
 * before the vocal stem is returned to the existing premium DSP.
 */
object MdxSeparatorCore {
    const val MODEL_SAMPLE_RATE = MdxStft.SAMPLE_RATE

    interface Runner {
        fun separateChunk(
            left: FloatArray,
            right: FloatArray,
        ): MdxStft.StereoChunk
    }

    fun separate(
        input: VocalSeparatorCore.Stereo,
        sampleRate: Int,
        modelSpec: MdxModelSpec,
        runner: Runner,
    ): VocalSeparatorCore.Stereo {
        require(sampleRate >= 8_000) { "Unsupported sample rate: $sampleRate" }
        require(input.size > 0) { "Input audio is empty" }

        val atModelRate =
            if (sampleRate == MODEL_SAMPLE_RATE) {
                input
            } else {
                VocalSeparatorCore.WindowedSincResampler.resample(
                    input,
                    sampleRate,
                    MODEL_SAMPLE_RATE,
                )
            }

        val vocalAtModelRate =
            separateAtModelRate(
                atModelRate,
                modelSpec,
                runner,
            )

        return if (sampleRate == MODEL_SAMPLE_RATE) {
            vocalAtModelRate
        } else {
            VocalSeparatorCore.WindowedSincResampler.resample(
                vocalAtModelRate,
                MODEL_SAMPLE_RATE,
                sampleRate,
            )
        }
    }

    /**
     * UVR-style chunk reconstruction.
     *
     * For each generation region [start, start + generation), the model sees
     * [start - trim, start + generation + trim). Samples outside the source are
     * zero. Only the central generation region is copied from the model output.
     */
    fun separateAtModelRate(
        input: VocalSeparatorCore.Stereo,
        modelSpec: MdxModelSpec,
        runner: Runner,
    ): VocalSeparatorCore.Stereo {
        require(input.size > 0) { "Input audio is empty" }

        val stft = MdxStft(modelSpec)
        val chunkSize = stft.chunkSizeSamples()
        val trim = stft.edgeTrimSamples()
        val generation = stft.generatedSamplesPerChunk()
        val total = input.size

        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val windowL = FloatArray(chunkSize)
        val windowR = FloatArray(chunkSize)

        var start = 0
        while (start < total) {
            java.util.Arrays.fill(windowL, 0f)
            java.util.Arrays.fill(windowR, 0f)

            val sourceStart = start - trim
            val sourceCopyStart = sourceStart.coerceAtLeast(0)
            val destinationStart = (-sourceStart).coerceAtLeast(0)
            val copyCount =
                min(
                    chunkSize - destinationStart,
                    total - sourceCopyStart,
                )

            if (copyCount > 0) {
                System.arraycopy(
                    input.left,
                    sourceCopyStart,
                    windowL,
                    destinationStart,
                    copyCount,
                )
                System.arraycopy(
                    input.right,
                    sourceCopyStart,
                    windowR,
                    destinationStart,
                    copyCount,
                )
            }

            val vocal =
                runner.separateChunk(
                    windowL,
                    windowR,
                )
            require(vocal.size == chunkSize) {
                "MDX runner returned " + vocal.size +
                    " samples; expected " + chunkSize
            }

            val actual = min(generation, total - start)
            emitCompensated(
                vocal.left,
                vocal.right,
                sourceOffset = trim,
                destination = start,
                count = actual,
                modelSpec = modelSpec,
                outputLeft = outL,
                outputRight = outR,
            )

            start += generation
        }

        return VocalSeparatorCore.Stereo(outL, outR)
    }

    private fun emitCompensated(
        sourceLeft: FloatArray,
        sourceRight: FloatArray,
        sourceOffset: Int,
        destination: Int,
        count: Int,
        modelSpec: MdxModelSpec,
        outputLeft: FloatArray,
        outputRight: FloatArray,
    ) {
        val gain = modelSpec.compensation
        for (i in 0 until count) {
            outputLeft[destination + i] =
                sourceLeft[sourceOffset + i] * gain
            outputRight[destination + i] =
                sourceRight[sourceOffset + i] * gain
        }
    }

    /**
     * Bounded-memory UVR-compatible streaming separator at 44.1 kHz.
     *
     * Before the first inference, the buffer collects generation + right-context
     * samples. Every subsequent inference advances by exactly generation samples
     * and reuses the preceding 2 * trim samples as the overlapping context.
     */
    class StreamingSeparator(
        private val modelSpec: MdxModelSpec,
        private val runner: Runner,
        private val emit: (VocalSeparatorCore.Stereo) -> Unit,
    ) {
        companion object {
            private const val OUTPUT_BLOCK = 8192
        }

        private val stft = MdxStft(modelSpec)
        private val chunkSize = stft.chunkSizeSamples()
        private val trim = stft.edgeTrimSamples()
        private val generation = stft.generatedSamplesPerChunk()
        private val overlap = 2 * trim

        private val windowL = FloatArray(chunkSize)
        private val windowR = FloatArray(chunkSize)
        private val incomingL = FloatArray(generation + trim)
        private val incomingR = FloatArray(generation + trim)
        private val historyL = FloatArray(overlap)
        private val historyR = FloatArray(overlap)
        private val emitLeftScratch = FloatArray(OUTPUT_BLOCK)
        private val emitRightScratch = FloatArray(OUTPUT_BLOCK)

        private var fill = 0
        private var processedAny = false
        private var finished = false

        fun push(block: VocalSeparatorCore.Stereo) {
            check(!finished) {
                "MdxSeparatorCore.StreamingSeparator is already finished"
            }
            require(block.size > 0)

            var pos = 0
            while (pos < block.size) {
                val required =
                    if (processedAny) generation else generation + trim

                val take =
                    min(
                        required - fill,
                        block.size - pos,
                    )

                System.arraycopy(
                    block.left,
                    pos,
                    incomingL,
                    fill,
                    take,
                )
                System.arraycopy(
                    block.right,
                    pos,
                    incomingR,
                    fill,
                    take,
                )
                fill += take
                pos += take

                if (fill == required) {
                    processFullWindow()
                }
            }
        }

        fun finish() {
            check(!finished) {
                "MdxSeparatorCore.StreamingSeparator is already finished"
            }
            finished = true

            if (!processedAny) {
                when {
                    fill == 0 -> return
                    fill <= generation -> {
                        java.util.Arrays.fill(windowL, 0f)
                        java.util.Arrays.fill(windowR, 0f)
                        System.arraycopy(
                            incomingL,
                            0,
                            windowL,
                            trim,
                            fill,
                        )
                        System.arraycopy(
                            incomingR,
                            0,
                            windowR,
                            trim,
                            fill,
                        )

                        val vocal = runner.separateChunk(windowL, windowR)
                        require(vocal.size == chunkSize)
                        emitRange(
                            vocal,
                            sourceOffset = trim,
                            count = fill,
                        )
                        return
                    }
                    else -> {
                        val firstCount = fill
                        buildFirstWindow(firstCount)
                        val vocal = runner.separateChunk(windowL, windowR)
                        require(vocal.size == chunkSize)

                        emitRange(
                            vocal,
                            sourceOffset = trim,
                            count = generation,
                        )
                        retainTail()
                        processedAny = true
                        val remaining = firstCount - generation
                        fill = 0

                        if (remaining > 0) {
                            flushFinalWindows(remaining)
                        }
                        return
                    }
                }
            }

            flushFinalWindows(trim + fill)
            fill = 0
        }

        private fun processFullWindow() {
            if (!processedAny) {
                buildFirstWindow(fill)
            } else {
                java.util.Arrays.fill(windowL, 0f)
                java.util.Arrays.fill(windowR, 0f)
                System.arraycopy(
                    historyL,
                    0,
                    windowL,
                    0,
                    overlap,
                )
                System.arraycopy(
                    historyR,
                    0,
                    windowR,
                    0,
                    overlap,
                )
                System.arraycopy(
                    incomingL,
                    0,
                    windowL,
                    overlap,
                    generation,
                )
                System.arraycopy(
                    incomingR,
                    0,
                    windowR,
                    overlap,
                    generation,
                )
            }

            val vocal = runner.separateChunk(windowL, windowR)
            require(vocal.size == chunkSize) {
                "MDX runner returned " + vocal.size +
                    " samples; expected " + chunkSize
            }

            emitRange(
                vocal,
                sourceOffset = trim,
                count = generation,
            )
            retainTail()
            processedAny = true
            fill = 0
        }

        private fun buildFirstWindow(actualInput: Int) {
            java.util.Arrays.fill(windowL, 0f)
            java.util.Arrays.fill(windowR, 0f)
            System.arraycopy(
                incomingL,
                0,
                windowL,
                trim,
                actualInput,
            )
            System.arraycopy(
                incomingR,
                0,
                windowR,
                trim,
                actualInput,
            )
        }

        private fun flushFinalWindows(initialRemaining: Int) {
            var remaining = initialRemaining.coerceAtLeast(0)

            while (remaining > 0) {
                java.util.Arrays.fill(
                    windowL,
                    overlap,
                    chunkSize,
                    0f,
                )
                java.util.Arrays.fill(
                    windowR,
                    overlap,
                    chunkSize,
                    0f,
                )
                System.arraycopy(
                    historyL,
                    0,
                    windowL,
                    0,
                    overlap,
                )
                System.arraycopy(
                    historyR,
                    0,
                    windowR,
                    0,
                    overlap,
                )

                if (fill > 0) {
                    System.arraycopy(
                        incomingL,
                        0,
                        windowL,
                        overlap,
                        fill,
                    )
                    System.arraycopy(
                        incomingR,
                        0,
                        windowR,
                        overlap,
                        fill,
                    )
                }

                val vocal = runner.separateChunk(windowL, windowR)
                require(vocal.size == chunkSize) {
                    "MDX runner returned " + vocal.size +
                        " samples; expected " + chunkSize
                }

                val take = min(generation, remaining)
                emitRange(
                    vocal,
                    sourceOffset = trim,
                    count = take,
                )
                remaining -= take

                if (remaining > 0) {
                    retainTail()
                    fill = 0
                }
            }
        }

        private fun retainTail() {
            val start = chunkSize - overlap
            System.arraycopy(
                windowL,
                start,
                historyL,
                0,
                overlap,
            )
            System.arraycopy(
                windowR,
                start,
                historyR,
                0,
                overlap,
            )
        }

        private fun emitRange(
            vocal: MdxStft.StereoChunk,
            sourceOffset: Int,
            count: Int,
        ) {
            if (count <= 0) return

            val gain = modelSpec.compensation
            var pos = 0

            while (pos < count) {
                val n = min(OUTPUT_BLOCK, count - pos)
                for (i in 0 until n) {
                    emitLeftScratch[i] =
                        vocal.left[sourceOffset + pos + i] * gain
                    emitRightScratch[i] =
                        vocal.right[sourceOffset + pos + i] * gain
                }

                /*
                 * emit() is synchronous: LiveStreamingVocalRemover immediately
                 * consumes/copies the block into its bounded resampler/queue.
                 * Reusing these two 8K buffers removes dozens of heap allocations
                 * per MDX window.
                 */
                emit(
                    VocalSeparatorCore.Stereo(
                        if (n == OUTPUT_BLOCK) emitLeftScratch
                        else emitLeftScratch.copyOf(n),
                        if (n == OUTPUT_BLOCK) emitRightScratch
                        else emitRightScratch.copyOf(n),
                    )
                )
                pos += n
            }
        }
    }
}
