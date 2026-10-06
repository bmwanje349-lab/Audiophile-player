package com.bmwanje.audiophile.vocalremover

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Pure-Kotlin source separation orchestration. No Android or ONNX classes here. */
object VocalSeparatorCore {
    const val MODEL_SAMPLE_RATE = 44_100
    const val MODEL_SAMPLES = 343_980 // 7.8 s @ 44.1 kHz
    const val OVERLAP_SAMPLES = 85_995 // 25%
    const val STRIDE_SAMPLES = MODEL_SAMPLES - OVERLAP_SAMPLES

    data class Stereo(val left: FloatArray, val right: FloatArray) {
        init {
            require(left.size == right.size) { "Stereo channel lengths differ" }
        }
        val size: Int get() = left.size
    }

    interface ModelRunner {
        /** Input/output are planar: [L N][R N], N = MODEL_SAMPLES. */
        fun run(planarInput: FloatArray): FloatArray
    }

    /**
     * Separates vocals from arbitrary-rate stereo audio by resampling to the
     * fixed-size neural-model contract, chunking, overlap-adding, then resampling back.
     */
    fun separate(
        input: Stereo,
        sampleRate: Int,
        runner: ModelRunner,
    ): Stereo {
        require(sampleRate >= 8_000) { "Unsupported sample rate: $sampleRate" }
        require(input.size > 0) { "Input audio is empty" }

        val modelRateAudio = if (sampleRate == MODEL_SAMPLE_RATE) {
            input
        } else {
            WindowedSincResampler.resample(input, sampleRate, MODEL_SAMPLE_RATE)
        }

        val modelVocals = separateAtModelRate(modelRateAudio, runner)

        return if (sampleRate == MODEL_SAMPLE_RATE) {
            modelVocals
        } else {
            WindowedSincResampler.resample(modelVocals, MODEL_SAMPLE_RATE, sampleRate)
        }
    }

    fun separateAtModelRate(input: Stereo, runner: ModelRunner): Stereo {
        require(input.size > 0)
        val total = input.size
        val chunkCount = ceil(total.toDouble() / STRIDE_SAMPLES.toDouble()).toInt()
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val weight = FloatArray(total)
        val chunk = FloatArray(2 * MODEL_SAMPLES)
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * STRIDE_SAMPLES
            val count = min(MODEL_SAMPLES, total - start)
            val window = transitionWindow(chunkIndex == 0, chunkIndex == chunkCount - 1)
            chunk.fill(0f)
            System.arraycopy(input.left, start, chunk, 0, count)
            System.arraycopy(input.right, start, chunk, MODEL_SAMPLES, count)

            val vocals = runner.run(chunk)
            require(vocals.size == 2 * MODEL_SAMPLES) {
                "Model runner returned ${vocals.size} floats; expected ${2 * MODEL_SAMPLES}"
            }

            for (i in 0 until count) {
                val w = window[i]
                outL[start + i] += vocals[i] * w
                outR[start + i] += vocals[MODEL_SAMPLES + i] * w
                weight[start + i] += w
            }
        }

        for (i in 0 until total) {
            val w = max(weight[i], 1.0e-6f)
            outL[i] /= w
            outR[i] /= w
        }
        return Stereo(outL, outR)
    }

    fun transitionWindow(isFirst: Boolean, isLast: Boolean): FloatArray {
        val w = FloatArray(MODEL_SAMPLES) { 1f }
        if (!isFirst) {
            for (i in 0 until OVERLAP_SAMPLES) {
                w[i] = i.toFloat() / OVERLAP_SAMPLES.toFloat()
            }
        }
        if (!isLast) {
            for (i in 0 until OVERLAP_SAMPLES) {
                w[MODEL_SAMPLES - 1 - i] = i.toFloat() / OVERLAP_SAMPLES.toFloat()
            }
        }
        return w
    }

    fun transitionWindow(): FloatArray = transitionWindow(isFirst = false, isLast = false)

    /**
     * Bounded-memory streaming separator for 44.1 kHz PCM.
     *
     * The caller supplies arbitrary-sized stereo blocks. The separator keeps only
     * one model chunk plus one overlap region in memory and emits vocal blocks in
     * timeline order. Call [finish] exactly once at end-of-stream.
     *
     * This class deliberately operates only at MODEL_SAMPLE_RATE. For other input
     * rates, resample the decoder stream to 44.1 kHz before feeding it here, or use
     * [separate] for the simpler whole-buffer API.
     */
    class StreamingSeparator(
        private val runner: ModelRunner,
        private val emit: (Stereo) -> Unit,
    ) {
        private val inputL = FloatArray(MODEL_SAMPLES)
        private val inputR = FloatArray(MODEL_SAMPLES)
        private val chunk = FloatArray(2 * MODEL_SAMPLES)
        private val pendingL = FloatArray(OVERLAP_SAMPLES)
        private val pendingR = FloatArray(OVERLAP_SAMPLES)
        private val pendingW = FloatArray(OVERLAP_SAMPLES)
        private val currentL = FloatArray(MODEL_SAMPLES)
        private val currentR = FloatArray(MODEL_SAMPLES)
        private val currentW = FloatArray(MODEL_SAMPLES)

        private var fill = 0
        private var emittedAny = false
        private var finished = false

        fun push(block: Stereo) {
            check(!finished) { "StreamingSeparator is already finished" }
            var pos = 0
            while (pos < block.size) {
                val take = min(MODEL_SAMPLES - fill, block.size - pos)
                System.arraycopy(block.left, pos, inputL, fill, take)
                System.arraycopy(block.right, pos, inputR, fill, take)
                fill += take
                pos += take

                if (fill == MODEL_SAMPLES) {
                    processChunk(actualSamples = MODEL_SAMPLES, isLast = false)
                    retainInputOverlap()
                }
            }
        }

        fun finish() {
            check(!finished) { "StreamingSeparator is already finished" }
            finished = true

            if (fill == 0 && !emittedAny) {
                // Empty stream. No model invocation is necessary.
                return
            }

            if (!emittedAny) {
                // Entire stream is shorter than one model chunk.
                processChunk(actualSamples = fill, isLast = true)
                return
            }

            // After every non-final chunk, [fill] contains exactly the retained
            // overlap. A final padded inference resolves that overlap and produces
            // the remaining timeline samples.
            processChunk(actualSamples = fill, isLast = true)
        }

        private fun processChunk(actualSamples: Int, isLast: Boolean) {
            require(actualSamples in 1..MODEL_SAMPLES)
            chunk.fill(0f)
            System.arraycopy(inputL, 0, chunk, 0, fill)
            System.arraycopy(inputR, 0, chunk, MODEL_SAMPLES, fill)

            val vocals = runner.run(chunk)
            require(vocals.size == 2 * MODEL_SAMPLES) {
                "Model runner returned ${vocals.size} floats; expected ${2 * MODEL_SAMPLES}"
            }

            val window = transitionWindow(isFirst = !emittedAny, isLast = isLast)
            currentW.fill(0f)
            for (i in 0 until MODEL_SAMPLES) {
                val w = window[i]
                currentL[i] = vocals[i] * w
                currentR[i] = vocals[MODEL_SAMPLES + i] * w
                currentW[i] = w
            }

            if (!emittedAny) {
                if (isLast) {
                    emitNormalized(0, actualSamples)
                } else {
                    emitNormalized(0, MODEL_SAMPLES - OVERLAP_SAMPLES)
                    retainWeightedTail()
                    emittedAny = true
                }
                return
            }

            // Resolve the previous chunk's retained tail against this chunk's head.
            val overlapL = FloatArray(OVERLAP_SAMPLES)
            val overlapR = FloatArray(OVERLAP_SAMPLES)
            for (i in 0 until OVERLAP_SAMPLES) {
                val denom = max(pendingW[i] + currentW[i], 1.0e-12f)
                overlapL[i] = (pendingL[i] + currentL[i]) / denom
                overlapR[i] = (pendingR[i] + currentR[i]) / denom
            }
            emit(Stereo(overlapL, overlapR))

            if (actualSamples > OVERLAP_SAMPLES) {
                val middleLimit = MODEL_SAMPLES - 2 * OVERLAP_SAMPLES
                val middleCount = if (isLast) {
                    min(actualSamples - OVERLAP_SAMPLES, middleLimit)
                } else {
                    middleLimit
                }
                if (middleCount > 0) {
                    emitNormalized(OVERLAP_SAMPLES, middleCount)
                }
            }

            if (!isLast) {
                retainWeightedTail()
            } else {
                // Tail belongs to the current final chunk, so emit its real samples.
                val tailStart = MODEL_SAMPLES - OVERLAP_SAMPLES
                if (actualSamples > tailStart) {
                    emitNormalized(tailStart, actualSamples - tailStart)
                }
            }
            emittedAny = true
        }

        private fun retainInputOverlap() {
            val start = MODEL_SAMPLES - OVERLAP_SAMPLES
            System.arraycopy(inputL, start, inputL, 0, OVERLAP_SAMPLES)
            System.arraycopy(inputR, start, inputR, 0, OVERLAP_SAMPLES)
            fill = OVERLAP_SAMPLES
        }

        private fun retainWeightedTail() {
            val start = MODEL_SAMPLES - OVERLAP_SAMPLES
            System.arraycopy(currentL, start, pendingL, 0, OVERLAP_SAMPLES)
            System.arraycopy(currentR, start, pendingR, 0, OVERLAP_SAMPLES)
            System.arraycopy(currentW, start, pendingW, 0, OVERLAP_SAMPLES)
        }

        private fun emitNormalized(start: Int, count: Int) {
            val left = FloatArray(count)
            val right = FloatArray(count)
            for (i in 0 until count) {
                val idx = start + i
                val w = max(currentW[idx], 1.0e-12f)
                left[i] = currentL[idx] / w
                right[i] = currentR[idx] / w
            }
            emit(Stereo(left, right))
        }

    }

    /** 32-tap Blackman-windowed sinc. Same-rate is a zero-copy-equivalent copy. */
    object WindowedSincResampler {
        private const val TAPS = 32
        private const val PHASES = 1024
        private const val CUT_RATIO = 0.94
        private val coeffCache = HashMap<Long, FloatArray>()

        fun resample(input: Stereo, sourceRate: Int, targetRate: Int): Stereo {
            require(sourceRate > 0 && targetRate > 0)
            if (sourceRate == targetRate) {
                return Stereo(input.left.copyOf(), input.right.copyOf())
            }
            val outLength = max(1, (input.size.toDouble() * targetRate / sourceRate).roundToInt())
            val bank = coefficientBank(sourceRate, targetRate)
            val ratio = sourceRate.toDouble() / targetRate.toDouble()
            val left = FloatArray(outLength)
            val right = FloatArray(outLength)

            for (n in 0 until outLength) {
                val srcPos = n * ratio
                val base = kotlin.math.floor(srcPos).toInt()
                val frac = srcPos - base
                val phase = min(PHASES - 1, (frac * PHASES).roundToInt())
                val coeffOffset = phase * TAPS
                val start = base - (TAPS / 2 - 1)
                var sumL = 0.0
                var sumR = 0.0
                var norm = 0.0
                for (t in 0 until TAPS) {
                    val idx = start + t
                    if (idx in input.left.indices) {
                        val c = bank[coeffOffset + t].toDouble()
                        sumL += input.left[idx].toDouble() * c
                        sumR += input.right[idx].toDouble() * c
                        norm += c
                    }
                }
                if (abs(norm) > 1.0e-12) {
                    sumL /= norm
                    sumR /= norm
                }
                left[n] = finiteOrZero(sumL.toFloat())
                right[n] = finiteOrZero(sumR.toFloat())
            }
            return Stereo(left, right)
        }

        private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0.0f

        private fun coefficientBank(sourceRate: Int, targetRate: Int): FloatArray {
            val key = (sourceRate.toLong() shl 32) xor (targetRate.toLong() and 0xffffffffL)
            synchronized(coeffCache) {
                coeffCache[key]?.let { return it }
                val ratio = min(1.0, targetRate.toDouble() / sourceRate.toDouble())
                val cutoff = PI * ratio * CUT_RATIO
                val bank = FloatArray(PHASES * TAPS)
                for (p in 0 until PHASES) {
                    val frac = p.toDouble() / PHASES
                    var sum = 0.0
                    for (t in 0 until TAPS) {
                        val x = (t - (TAPS / 2 - 1)).toDouble() - frac
                        val ax = abs(x)
                        val window = if (ax >= TAPS / 2.0) 0.0 else {
                            // Blackman window, normalized over the finite support.
                            0.42 + 0.5 * cos(2.0 * PI * ax / (TAPS / 2.0)) +
                                0.08 * cos(4.0 * PI * ax / (TAPS / 2.0))
                        }
                        val sinc = if (abs(x) < 1.0e-12) cutoff / PI else sin(cutoff * x) / (PI * x)
                        val c = sinc * window
                        bank[p * TAPS + t] = c.toFloat()
                        sum += c
                    }
                    if (abs(sum) > 1.0e-15) {
                        val base = p * TAPS
                        for (t in 0 until TAPS) bank[base + t] /= sum.toFloat()
                    }
                }
                coeffCache[key] = bank
                return bank
            }
        }
    }
}
