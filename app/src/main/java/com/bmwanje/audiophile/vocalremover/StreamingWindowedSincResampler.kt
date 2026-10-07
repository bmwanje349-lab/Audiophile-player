package com.bmwanje.audiophile.vocalremover

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bounded-memory stereo windowed-sinc resampler.
 *
 * It uses the same 32-tap / 1024-phase Blackman-windowed sinc kernel as the
 * existing whole-buffer resampler, but never materializes the entire track.
 * Input is pushed in arbitrary blocks and output is emitted in bounded blocks.
 */
class StreamingWindowedSincResampler(
    private val sourceRate: Int,
    private val targetRate: Int,
    private val emit: (VocalSeparatorCore.Stereo) -> Unit,
) {
    companion object {
        private const val TAPS = 32
        private const val PHASES = 1024
        private const val CUT_RATIO = 0.94
        private const val OUTPUT_BLOCK = 8192
        private const val INITIAL_CAPACITY = 65_536
        private const val MAX_CAPACITY = 131_072
    }

    private val ratio = sourceRate.toDouble() / targetRate.toDouble()
    private val sameRate = sourceRate == targetRate
    private val bank = coefficientBank(sourceRate, targetRate)

    private var left = FloatArray(INITIAL_CAPACITY)
    private var right = FloatArray(INITIAL_CAPACITY)
    private var baseIndex = 0L
    private var buffered = 0
    private var totalInput = 0L
    private var nextOutput = 0L
    private var finished = false

    private val pendingOutL = FloatArray(OUTPUT_BLOCK)
    private val pendingOutR = FloatArray(OUTPUT_BLOCK)
    private var pendingOut = 0

    init {
        require(sourceRate > 0) { "Invalid source sample rate: $sourceRate" }
        require(targetRate > 0) { "Invalid target sample rate: $targetRate" }
    }

    fun inputSamples(): Long = totalInput
    fun outputSamplesProduced(): Long = nextOutput

    fun push(block: VocalSeparatorCore.Stereo) {
        check(!finished) { "StreamingWindowedSincResampler is already finished" }
        require(block.size > 0)

        if (sameRate) {
            emitDirect(block)
            totalInput += block.size.toLong()
            nextOutput = totalInput
            return
        }

        append(block)
        totalInput += block.size.toLong()
        produce(final = false, outputLimit = Long.MAX_VALUE)
    }

    /**
     * Flushes the resampler and emits exactly [expectedOutputSamples].
     *
     * With the same rate/rounding convention as the existing batch resampler,
     * callers normally pass the natural sourceLength * targetRate / sourceRate
     * rounded to the nearest sample.
     */
    fun finish(expectedOutputSamples: Int = naturalOutputLength()) {
        check(!finished) { "StreamingWindowedSincResampler is already finished" }
        check(expectedOutputSamples >= 0)

        finished = true
        produce(
            final = true,
            outputLimit = expectedOutputSamples.toLong(),
        )
        flushOutput()

        check(nextOutput == expectedOutputSamples.toLong()) {
            "Resampler produced $nextOutput samples; expected $expectedOutputSamples"
        }
    }

    private fun naturalOutputLength(): Int {
        val value = max(
            1L,
            (totalInput.toDouble() * targetRate.toDouble() / sourceRate.toDouble())
                .roundToInt()
                .toLong(),
        )
        require(value <= Int.MAX_VALUE) { "Resampled audio is too large" }
        return value.toInt()
    }

    private fun emitDirect(block: VocalSeparatorCore.Stereo) {
        var pos = 0
        while (pos < block.size) {
            val count = min(OUTPUT_BLOCK, block.size - pos)
            val outL = FloatArray(count)
            val outR = FloatArray(count)
            System.arraycopy(block.left, pos, outL, 0, count)
            System.arraycopy(block.right, pos, outR, 0, count)
            emit(VocalSeparatorCore.Stereo(outL, outR))
            pos += count
        }
    }

    private fun append(block: VocalSeparatorCore.Stereo) {
        ensureCapacity(buffered + block.size)
        System.arraycopy(block.left, 0, left, buffered, block.size)
        System.arraycopy(block.right, 0, right, buffered, block.size)
        buffered += block.size
    }

    private fun ensureCapacity(required: Int) {
        if (required <= left.size) return

        compactIfPossible()

        if (required <= left.size) return

        var capacity = left.size
        while (capacity < required && capacity < MAX_CAPACITY) {
            capacity = min(MAX_CAPACITY, capacity * 2)
        }

        check(capacity >= required) {
            "Streaming resampler input buffer exceeded $MAX_CAPACITY samples"
        }

        left = left.copyOf(capacity)
        right = right.copyOf(capacity)
    }

    private fun compactIfPossible() {
        val desiredBase = floor(nextOutput.toDouble() * ratio).toLong() - (TAPS / 2 - 1)
        val drop = (desiredBase - baseIndex).coerceAtLeast(0L).coerceAtMost(buffered.toLong())
        if (drop < 4096L) return

        val d = drop.toInt()
        val remaining = buffered - d
        if (remaining > 0) {
            System.arraycopy(left, d, left, 0, remaining)
            System.arraycopy(right, d, right, 0, remaining)
        }
        buffered = remaining
        baseIndex += drop
    }

    private fun produce(final: Boolean, outputLimit: Long) {
        while (nextOutput < outputLimit) {
            val sourcePos = nextOutput.toDouble() * ratio
            val base = floor(sourcePos).toLong()

            /*
             * A non-final output needs all taps available. At finish, the batch
             * implementation renormalizes the finite support at the edges, so
             * partial taps are permitted and normalized below.
             */
            if (!final) {
                val requiredExclusive = base + (TAPS / 2).toLong()
                if (requiredExclusive > baseIndex + buffered) {
                    break
                }
            }

            val frac = sourcePos - base.toDouble()
            val phase = min(
                PHASES - 1,
                (frac * PHASES.toDouble()).roundToInt(),
            )
            val coeffOffset = phase * TAPS
            val start = base - (TAPS / 2 - 1)

            var sumL = 0.0
            var sumR = 0.0
            var norm = 0.0

            for (t in 0 until TAPS) {
                val index = start + t.toLong()
                if (index < baseIndex || index >= baseIndex + buffered) continue

                val local = (index - baseIndex).toInt()
                val c = bank[coeffOffset + t].toDouble()
                sumL += left[local].toDouble() * c
                sumR += right[local].toDouble() * c
                norm += c
            }

            if (abs(norm) > 1.0e-12) {
                sumL /= norm
                sumR /= norm
            }

            pendingOutL[pendingOut] = finiteOrZero(sumL.toFloat())
            pendingOutR[pendingOut] = finiteOrZero(sumR.toFloat())
            pendingOut++
            nextOutput++

            if (pendingOut == OUTPUT_BLOCK) {
                flushOutput()
            }

            if (nextOutput % 4096L == 0L) {
                compactIfPossible()
            }
        }
    }

    private fun flushOutput() {
        if (pendingOut == 0) return

        val outL = FloatArray(pendingOut)
        val outR = FloatArray(pendingOut)
        System.arraycopy(pendingOutL, 0, outL, 0, pendingOut)
        System.arraycopy(pendingOutR, 0, outR, 0, pendingOut)
        emit(VocalSeparatorCore.Stereo(outL, outR))
        pendingOut = 0
    }

    private fun coefficientBank(sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate) return FloatArray(1)

        val ratio = min(1.0, targetRate.toDouble() / sourceRate.toDouble())
        val cutoff = PI * ratio * CUT_RATIO
        val bank = FloatArray(PHASES * TAPS)

        for (phase in 0 until PHASES) {
            val frac = phase.toDouble() / PHASES.toDouble()
            var sum = 0.0

            for (t in 0 until TAPS) {
                val x = (t - (TAPS / 2 - 1)).toDouble() - frac
                val ax = abs(x)
                val window = if (ax >= TAPS / 2.0) {
                    0.0
                } else {
                    0.42 +
                        0.5 * cos(2.0 * PI * ax / (TAPS / 2.0)) +
                        0.08 * cos(4.0 * PI * ax / (TAPS / 2.0))
                }
                val sinc =
                    if (abs(x) < 1.0e-12) {
                        cutoff / PI
                    } else {
                        sin(cutoff * x) / (PI * x)
                    }
                val c = sinc * window
                bank[phase * TAPS + t] = c.toFloat()
                sum += c
            }

            if (abs(sum) > 1.0e-15) {
                val base = phase * TAPS
                for (t in 0 until TAPS) {
                    bank[base + t] /= sum.toFloat()
                }
            }
        }

        return bank
    }

    private fun finiteOrZero(value: Float): Float =
        if (value.isFinite()) value else 0.0f
}
