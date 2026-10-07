package com.bmwanje.audiophile.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StreamingWindowedSincResamplerTest {
    @Test
    fun sameRateIsBoundedAndLossless() {
        val left = FloatArray(30_000) { i ->
            sin(i * 0.011).toFloat()
        }
        val right = FloatArray(30_000) { i ->
            sin(i * 0.017).toFloat()
        }
        val input = VocalSeparatorCore.Stereo(left, right)

        val chunks = ArrayList<VocalSeparatorCore.Stereo>()
        val resampler =
            StreamingWindowedSincResampler(
                sourceRate = 44_100,
                targetRate = 44_100,
            ) { chunks += it }

        var pos = 0
        val pattern = intArrayOf(1, 4097, 257, 8192, 33, 701)
        var p = 0
        while (pos < input.size) {
            val n = minOf(
                pattern[p % pattern.size],
                input.size - pos,
            )
            resampler.push(
                slice(input, pos, n)
            )
            pos += n
            p++
        }
        resampler.finish()

        val out = concat(chunks)
        assertEquals(input.size, out.size)
        assertTrue(maxError(input, out) < 1.0e-7f)
    }

    @Test
    fun downsampleMatchesBatchReference() {
        val input =
            VocalSeparatorCore.Stereo(
                left = FloatArray(24_000) { i ->
                    (
                        0.3 * sin(i * 0.071) +
                            0.1 * sin(i * 0.013)
                    ).toFloat()
                },
                right = FloatArray(24_000) { i ->
                    (
                        0.2 * sin(i * 0.052) -
                            0.12 * sin(i * 0.017)
                    ).toFloat()
                },
            )

        val batch =
            VocalSeparatorCore.WindowedSincResampler.resample(
                input,
                48_000,
                44_100,
            )

        val emitted = ArrayList<VocalSeparatorCore.Stereo>()
        val stream =
            StreamingWindowedSincResampler(
                sourceRate = 48_000,
                targetRate = 44_100,
            ) { emitted += it }

        var pos = 0
        val pattern = intArrayOf(113, 4096, 777, 8192, 2049)
        var p = 0
        while (pos < input.size) {
            val n = minOf(
                pattern[p % pattern.size],
                input.size - pos,
            )
            stream.push(slice(input, pos, n))
            pos += n
            p++
        }
        stream.finish()

        val out = concat(emitted)
        assertEquals(batch.size, out.size)
        assertTrue(maxError(batch, out) < 1.0e-5f)
    }

    @Test
    fun upsampleMatchesBatchReference() {
        val input =
            VocalSeparatorCore.Stereo(
                left = FloatArray(18_000) { i ->
                    (
                        0.25 * sin(i * 0.031) +
                            0.08 * sin(i * 0.004)
                    ).toFloat()
                },
                right = FloatArray(18_000) { i ->
                    (
                        0.21 * sin(i * 0.027) -
                            0.06 * sin(i * 0.006)
                    ).toFloat()
                },
            )

        val batch =
            VocalSeparatorCore.WindowedSincResampler.resample(
                input,
                44_100,
                48_000,
            )

        val emitted = ArrayList<VocalSeparatorCore.Stereo>()
        val stream =
            StreamingWindowedSincResampler(
                sourceRate = 44_100,
                targetRate = 48_000,
            ) { emitted += it }

        var pos = 0
        val pattern = intArrayOf(89, 5001, 2048, 431, 8192)
        var p = 0
        while (pos < input.size) {
            val n = minOf(
                pattern[p % pattern.size],
                input.size - pos,
            )
            stream.push(slice(input, pos, n))
            pos += n
            p++
        }
        stream.finish()

        val out = concat(emitted)
        assertEquals(batch.size, out.size)
        assertTrue(maxError(batch, out) < 1.0e-5f)
    }

    private fun slice(
        input: VocalSeparatorCore.Stereo,
        start: Int,
        count: Int,
    ): VocalSeparatorCore.Stereo {
        return VocalSeparatorCore.Stereo(
            input.left.copyOfRange(start, start + count),
            input.right.copyOfRange(start, start + count),
        )
    }

    private fun concat(
        blocks: List<VocalSeparatorCore.Stereo>,
    ): VocalSeparatorCore.Stereo {
        val total = blocks.sumOf { it.size }
        val left = FloatArray(total)
        val right = FloatArray(total)
        var pos = 0

        for (block in blocks) {
            System.arraycopy(block.left, 0, left, pos, block.size)
            System.arraycopy(block.right, 0, right, pos, block.size)
            pos += block.size
        }
        return VocalSeparatorCore.Stereo(left, right)
    }

    private fun maxError(
        a: VocalSeparatorCore.Stereo,
        b: VocalSeparatorCore.Stereo,
    ): Float {
        var max = 0f
        for (i in 0 until a.size) {
            max = maxOf(max, abs(a.left[i] - b.left[i]))
            max = maxOf(max, abs(a.right[i] - b.right[i]))
        }
        return max
    }
}
