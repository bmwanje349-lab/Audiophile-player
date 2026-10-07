package com.bmwanje.audiophile.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.math.sin

class MdxStftTest {
    @Test
    fun light9482ProfileMatchesUvr() {
        val spec = MdxModelSpec.LIGHT_9482
        val stft = MdxStft(spec)

        assertEquals(6_144, spec.nFft)
        assertEquals(2_048, spec.dimF)
        assertEquals(1_024, spec.hop)
        assertEquals(256, spec.dimT)
        assertEquals(1.035f, spec.compensation, 0f)

        assertEquals(261_120, stft.chunkSizeSamples())
        assertEquals(3_072, stft.edgeTrimSamples())
        assertEquals(254_976, stft.generatedSamplesPerChunk())
        assertEquals(4 * 2_048 * 256, stft.tensorSize())
    }

    @Test
    fun lowFrequencyRoundTripIsNumericallyStable() {
        val stft = MdxStft(MdxModelSpec.LIGHT_9482)
        val n = stft.chunkSizeSamples()

        val left =
            FloatArray(n) { i ->
                (
                    0.21 * sin(2.0 * Math.PI * 440.0 * i / MdxStft.SAMPLE_RATE) +
                        0.08 * sin(2.0 * Math.PI * 2_000.0 * i / MdxStft.SAMPLE_RATE)
                ).toFloat()
            }
        val right =
            FloatArray(n) { i ->
                (
                    0.17 * sin(2.0 * Math.PI * 660.0 * i / MdxStft.SAMPLE_RATE) +
                        0.06 * sin(2.0 * Math.PI * 3_000.0 * i / MdxStft.SAMPLE_RATE)
                ).toFloat()
            }

        val reconstructed = stft.inverse(stft.forward(left, right))
        assertEquals(n, reconstructed.size)

        val rms =
            sqrt(
                left.indices.sumOf { i ->
                    val d = (left[i] - reconstructed.left[i]).toDouble()
                    d * d
                } / n.toDouble()
            ).toFloat()

        val rmsR =
            sqrt(
                right.indices.sumOf { i ->
                    val d = (right[i] - reconstructed.right[i]).toDouble()
                    d * d
                } / n.toDouble()
            ).toFloat()

        assertTrue("left RMS error too large: $rms", rms < 1.0e-4f)
        assertTrue("right RMS error too large: $rmsR", rmsR < 1.0e-4f)

        val finiteCount =
            reconstructed.left.count { it.isFinite() } +
                reconstructed.right.count { it.isFinite() }

        assertEquals(2 * n, finiteCount)
    }
}
