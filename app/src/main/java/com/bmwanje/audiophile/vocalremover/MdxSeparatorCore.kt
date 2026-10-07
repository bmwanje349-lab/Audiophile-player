package com.bmwanje.audiophile.vocalremover

import kotlin.math.max
import kotlin.math.min

/**
 * MDX-Net vocal separation orchestration.
 *
 * Audio is resampled to 44.1 kHz, processed in native 256-frame model chunks,
 * crossfaded at 10%, then resampled back. This class does not touch the premium
 * vocal-removal DSP; it only supplies the neural vocal stem to it.
 */
object MdxSeparatorCore {
    const val MODEL_SAMPLE_RATE = MdxStft.SAMPLE_RATE

    interface Runner {
        fun separateChunk(left: FloatArray, right: FloatArray): MdxStft.StereoChunk
    }

    fun separate(
        input: VocalSeparatorCore.Stereo,
        sampleRate: Int,
        modelSpec: MdxModelSpec,
        runner: Runner,
    ): VocalSeparatorCore.Stereo {
        require(sampleRate >= 8_000) { "Unsupported sample rate: $sampleRate" }
        require(input.size > 0) { "Input audio is empty" }

        val atModelRate = if (sampleRate == MODEL_SAMPLE_RATE) {
            input
        } else {
            VocalSeparatorCore.WindowedSincResampler.resample(input, sampleRate, MODEL_SAMPLE_RATE)
        }

        val vocalAtModelRate = separateAtModelRate(atModelRate, modelSpec, runner)
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

    fun separateAtModelRate(
        input: VocalSeparatorCore.Stereo,
        modelSpec: MdxModelSpec,
        runner: Runner,
    ): VocalSeparatorCore.Stereo {
        val stft = MdxStft(modelSpec)
        val chunkSize = stft.chunkSizeSamples()
        val crossfade = stft.crossfadeSamples().coerceAtMost(chunkSize / 2)
        val stride = max(1, chunkSize - crossfade)
        val total = input.size
        val chunkCount = max(1, ((total - 1) / stride) + 1)

        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val weights = FloatArray(total)
        val inL = FloatArray(chunkSize)
        val inR = FloatArray(chunkSize)

        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * stride
            if (start >= total) break
            val actual = min(chunkSize, total - start)
            java.util.Arrays.fill(inL, 0f)
            java.util.Arrays.fill(inR, 0f)
            System.arraycopy(input.left, start, inL, 0, actual)
            System.arraycopy(input.right, start, inR, 0, actual)

            val vocal = runner.separateChunk(inL, inR)
            require(vocal.size == chunkSize) { "MDX runner returned an invalid chunk size" }

            val isFirst = chunkIndex == 0
            val isLast = chunkIndex == chunkCount - 1 || start + chunkSize >= total
            for (i in 0 until actual) {
                val w = crossfadeWindow(i, chunkSize, crossfade, isFirst, isLast)
                outL[start + i] += vocal.left[i] * w * modelSpec.compensation
                outR[start + i] += vocal.right[i] * w * modelSpec.compensation
                weights[start + i] += w
            }
        }

        for (i in 0 until total) {
            val w = max(weights[i], 1.0e-6f)
            outL[i] /= w
            outR[i] /= w
        }
        return VocalSeparatorCore.Stereo(outL, outR)
    }

    private fun crossfadeWindow(
        index: Int,
        chunkSize: Int,
        crossfade: Int,
        isFirst: Boolean,
        isLast: Boolean,
    ): Float {
        var w = 1f
        if (!isFirst && index < crossfade) {
            w *= index.toFloat() / crossfade.toFloat()
        }
        if (!isLast && index >= chunkSize - crossfade) {
            w *= (chunkSize - index).toFloat() / crossfade.toFloat()
        }
        return w.coerceIn(0f, 1f)
    }
    /**
     * Bounded-memory MDX separator. It accepts arbitrary input blocks at
     * 44.1 kHz, feeds the real model runner only fixed-size model chunks, and
     * emits the vocal stem in small blocks.
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
        private val crossfade = stft.crossfadeSamples().coerceAtMost(chunkSize / 2)

        private val inputL = FloatArray(chunkSize)
        private val inputR = FloatArray(chunkSize)
        private val currentL = FloatArray(chunkSize)
        private val currentR = FloatArray(chunkSize)
        private val currentW = FloatArray(chunkSize)
        private val pendingL = FloatArray(crossfade)
        private val pendingR = FloatArray(crossfade)
        private val pendingW = FloatArray(crossfade)

        private var fill = 0
        private var emittedAny = false
        private var finished = false

        fun push(block: VocalSeparatorCore.Stereo) {
            check(!finished) { "MdxSeparatorCore.StreamingSeparator is already finished" }
            require(block.size > 0)

            var pos = 0
            while (pos < block.size) {
                val take = min(chunkSize - fill, block.size - pos)
                System.arraycopy(block.left, pos, inputL, fill, take)
                System.arraycopy(block.right, pos, inputR, fill, take)
                fill += take
                pos += take

                if (fill == chunkSize) {
                    processChunk(actualSamples = chunkSize, isLast = false)
                    retainInputOverlap()
                }
            }
        }

        fun finish() {
            check(!finished) { "MdxSeparatorCore.StreamingSeparator is already finished" }
            finished = true

            if (fill == 0 && !emittedAny) return

            processChunk(actualSamples = fill, isLast = true)
        }

        private fun processChunk(actualSamples: Int, isLast: Boolean) {
            require(actualSamples in 1..chunkSize)

            java.util.Arrays.fill(inputL, actualSamples, chunkSize, 0f)
            java.util.Arrays.fill(inputR, actualSamples, chunkSize, 0f)

            val vocal = runner.separateChunk(inputL, inputR)
            require(vocal.size == chunkSize) {
                "MDX runner returned ${vocal.size} samples; expected $chunkSize"
            }

            val isFirst = !emittedAny
            for (i in 0 until chunkSize) {
                val w = crossfadeWindow(
                    index = i,
                    chunkSize = chunkSize,
                    crossfade = crossfade,
                    isFirst = isFirst,
                    isLast = isLast,
                )
                currentL[i] = vocal.left[i] * w * modelSpec.compensation
                currentR[i] = vocal.right[i] * w * modelSpec.compensation
                currentW[i] = w
            }

            if (!emittedAny) {
                if (isLast) {
                    emitNormalized(0, actualSamples)
                } else {
                    emitNormalized(0, chunkSize - crossfade)
                    retainWeightedTail()
                    emittedAny = true
                }
                return
            }

            val overlapL = FloatArray(crossfade)
            val overlapR = FloatArray(crossfade)

            for (i in 0 until crossfade) {
                val denom = max(pendingW[i] + currentW[i], 1.0e-12f)
                overlapL[i] = (pendingL[i] + currentL[i]) / denom
                overlapR[i] = (pendingR[i] + currentR[i]) / denom
            }

            emitInBlocks(overlapL, overlapR)

            if (actualSamples > crossfade) {
                val middleLimit = chunkSize - 2 * crossfade
                val middleCount = min(
                    actualSamples - crossfade,
                    middleLimit,
                )
                if (middleCount > 0) {
                    emitNormalized(crossfade, middleCount)
                }
            }

            if (!isLast) {
                retainWeightedTail()
            } else {
                val tailStart = chunkSize - crossfade
                if (actualSamples > tailStart) {
                    emitNormalized(
                        tailStart,
                        actualSamples - tailStart,
                    )
                }
            }

            emittedAny = true
        }

        private fun retainInputOverlap() {
            val start = chunkSize - crossfade
            System.arraycopy(inputL, start, inputL, 0, crossfade)
            System.arraycopy(inputR, start, inputR, 0, crossfade)
            fill = crossfade
        }

        private fun retainWeightedTail() {
            val start = chunkSize - crossfade
            System.arraycopy(currentL, start, pendingL, 0, crossfade)
            System.arraycopy(currentR, start, pendingR, 0, crossfade)
            System.arraycopy(currentW, start, pendingW, 0, crossfade)
        }

        private fun emitNormalized(start: Int, count: Int) {
            if (count <= 0) return
            val end = start + count
            var pos = start

            while (pos < end) {
                val n = min(OUTPUT_BLOCK, end - pos)
                val outL = FloatArray(n)
                val outR = FloatArray(n)
                for (i in 0 until n) {
                    val idx = pos + i
                    val w = max(currentW[idx], 1.0e-12f)
                    outL[i] = currentL[idx] / w
                    outR[i] = currentR[idx] / w
                }
                emit(VocalSeparatorCore.Stereo(outL, outR))
                pos += n
            }
        }

        private fun emitInBlocks(left: FloatArray, right: FloatArray) {
            var pos = 0
            while (pos < left.size) {
                val n = min(OUTPUT_BLOCK, left.size - pos)
                val outL = FloatArray(n)
                val outR = FloatArray(n)
                System.arraycopy(left, pos, outL, 0, n)
                System.arraycopy(right, pos, outR, 0, n)
                emit(VocalSeparatorCore.Stereo(outL, outR))
                pos += n
            }
        }
    }

}
