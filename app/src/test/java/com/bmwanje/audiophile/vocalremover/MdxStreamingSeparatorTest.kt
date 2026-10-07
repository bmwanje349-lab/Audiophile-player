package com.bmwanje.audiophile.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

class MdxStreamingSeparatorTest {
    @Test
    fun streamingIdentityMatchesUvRWindowTimeline() {
        val spec = MdxModelSpec.LIGHT_9482
        val stft = MdxStft(spec)
        val generation = stft.generatedSamplesPerChunk()
        val trim = stft.edgeTrimSamples()

        val lengths = intArrayOf(
            1_000,
            generation - 1,
            generation,
            generation + 1,
            generation + trim - 1,
            generation + trim,
            generation * 2 + 12_345,
        )

        for (length in lengths) {
            val input =
                VocalSeparatorCore.Stereo(
                    left = FloatArray(length) { i ->
                        (0.25 * sin(i * 0.013)).toFloat()
                    },
                    right = FloatArray(length) { i ->
                        (0.18 * sin(i * 0.019)).toFloat()
                    },
                )

            var calls = 0
            val outputBlocks =
                ArrayList<VocalSeparatorCore.Stereo>()
            val runner =
                object : MdxSeparatorCore.Runner {
                    override fun separateChunk(
                        left: FloatArray,
                        right: FloatArray,
                    ): MdxStft.StereoChunk {
                        calls++
                        assertEquals(stft.chunkSizeSamples(), left.size)
                        assertEquals(stft.chunkSizeSamples(), right.size)
                        return MdxStft.StereoChunk(
                            left.copyOf(),
                            right.copyOf(),
                        )
                    }
                }

            val streaming =
                MdxSeparatorCore.StreamingSeparator(
                    modelSpec = spec,
                    runner = runner,
                    emit = { outputBlocks += it },
                )

            var pos = 0
            val pattern = intArrayOf(17, 7_001, 257, 16_384, 997)
            var p = 0
            while (pos < input.size) {
                val n = min(
                    pattern[p % pattern.size],
                    input.size - pos,
                )
                streaming.push(
                    slice(input, pos, n)
                )
                pos += n
                p++
            }
            streaming.finish()

            val output = concat(outputBlocks)
            assertEquals(length, output.size)

            val scale = spec.compensation
            val expected =
                VocalSeparatorCore.Stereo(
                    FloatArray(length) { i -> input.left[i] * scale },
                    FloatArray(length) { i -> input.right[i] * scale },
                )

            assertTrue(maxError(expected, output) < 3.0e-6f)

            val expectedCalls =
                ceil(
                    length.toDouble() /
                        generation.toDouble()
                ).toInt()

            assertEquals(
                "unexpected model-call count for length " + length,
                expectedCalls,
                calls,
            )
        }
    }

    @Test
    fun streamingMatchesWholeBufferReferenceTimeline() {
        val spec = MdxModelSpec.LIGHT_9482
        val stft = MdxStft(spec)
        val length = stft.generatedSamplesPerChunk() * 2 + 7_777

        val input =
            VocalSeparatorCore.Stereo(
                FloatArray(length) { i ->
                    (0.22 * sin(i * 0.021)).toFloat()
                },
                FloatArray(length) { i ->
                    (0.17 * sin(i * 0.017)).toFloat()
                },
            )

        val identity =
            object : MdxSeparatorCore.Runner {
                override fun separateChunk(
                    left: FloatArray,
                    right: FloatArray,
                ): MdxStft.StereoChunk =
                    MdxStft.StereoChunk(
                        left.copyOf(),
                        right.copyOf(),
                    )
            }

        val batch =
            MdxSeparatorCore.separateAtModelRate(
                input,
                spec,
                identity,
            )

        val streamedBlocks =
            ArrayList<VocalSeparatorCore.Stereo>()
        val secondRunner =
            object : MdxSeparatorCore.Runner {
                override fun separateChunk(
                    left: FloatArray,
                    right: FloatArray,
                ): MdxStft.StereoChunk =
                    MdxStft.StereoChunk(
                        left.copyOf(),
                        right.copyOf(),
                    )
            }

        val streamed =
            MdxSeparatorCore.StreamingSeparator(
                modelSpec = spec,
                runner = secondRunner,
                emit = { streamedBlocks += it },
            )

        streamed.push(
            slice(
                input,
                start = 0,
                count = 8_191,
            )
        )
        streamed.push(
            slice(
                input,
                start = 8_191,
                count = 4_097,
            )
        )
        streamed.push(
            slice(
                input,
                start = 12_288,
                count = input.size - 12_288,
            )
        )
        streamed.finish()

        val output = concat(streamedBlocks)
        assertEquals(batch.size, output.size)
        assertTrue(maxError(batch, output) < 3.0e-6f)
    }

    private fun slice(
        input: VocalSeparatorCore.Stereo,
        start: Int,
        count: Int,
    ): VocalSeparatorCore.Stereo =
        VocalSeparatorCore.Stereo(
            input.left.copyOfRange(start, start + count),
            input.right.copyOfRange(start, start + count),
        )

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
