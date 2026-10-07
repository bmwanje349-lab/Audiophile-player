package com.bmwanje.audiophile.vocalremover

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Host-side STFT/iSTFT for the UVR MDX-Net ONNX model. */
class MdxStft(private val spec: MdxModelSpec) {
    companion object {
        const val SAMPLE_RATE = 44_100
        const val MODEL_INPUT_CHANNELS = 4
        const val CROSSFADE_RATIO = 0.10f
    }

    private val n = spec.nFft
    private val dimF = spec.dimF
    private val hop = spec.hop
    private val dimT = spec.dimT
    private val pad = n / 2
    private val chunkSize = hop * (dimT - 1)
    private val tensorSize = MODEL_INPUT_CHANNELS * dimF * dimT
    private val window = FloatArray(n) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i.toDouble() / n.toDouble())).toFloat()
    }
    private val windowSq = FloatArray(n) { i -> window[i] * window[i] }
    private val fft = FloatFFT_1D(n.toLong())

    data class Spectrogram(val data: FloatArray) {
        init { require(data.isNotEmpty()) }
    }

    data class StereoChunk(val left: FloatArray, val right: FloatArray) {
        init { require(left.size == right.size) }
        val size: Int get() = left.size
    }

    fun chunkSizeSamples(): Int = chunkSize
    fun crossfadeSamples(): Int = max(1, (chunkSize * CROSSFADE_RATIO).toInt())
    fun tensorSize(): Int = tensorSize

    fun forward(left: FloatArray, right: FloatArray): Spectrogram {
        require(left.size == chunkSize && right.size == chunkSize)
        val out = FloatArray(tensorSize)
        writeChannel(left, out, 0)
        writeChannel(right, out, 2)
        return Spectrogram(out)
    }

    private fun writeChannel(input: FloatArray, out: FloatArray, planeBase: Int) {
        val padded = reflectPad(input)
        val frame = FloatArray(2 * n)
        for (t in 0 until dimT) {
            val start = t * hop
            for (i in 0 until n) frame[i] = padded[start + i] * window[i]
            for (i in n until 2 * n) frame[i] = 0f
            fft.realForwardFull(frame)
            val realBase = planeBase * dimF * dimT
            val imagBase = (planeBase + 1) * dimF * dimT
            for (k in 0 until dimF) {
                out[realBase + k * dimT + t] = finite(frame[2 * k])
                out[imagBase + k * dimT + t] = finite(frame[2 * k + 1])
            }
        }
    }

    fun inverse(spectrogram: Spectrogram): Pair<FloatArray, FloatArray> {
        require(spectrogram.data.size == tensorSize)
        return inverseChannel(spectrogram.data, 0) to inverseChannel(spectrogram.data, 2)
    }

    private fun inverseChannel(specData: FloatArray, planeBase: Int): FloatArray {
        val timeLength = chunkSize + n
        val accum = FloatArray(timeLength)
        val envelope = FloatArray(timeLength)
        val frame = FloatArray(2 * n)
        val realBase = planeBase * dimF * dimT
        val imagBase = (planeBase + 1) * dimF * dimT

        for (t in 0 until dimT) {
            java.util.Arrays.fill(frame, 0f)
            for (k in 0 until dimF) {
                frame[2 * k] = specData[realBase + k * dimT + t]
                frame[2 * k + 1] = specData[imagBase + k * dimT + t]
            }
            for (k in 1 until dimF) {
                val dst = n - k
                frame[2 * dst] = frame[2 * k]
                frame[2 * dst + 1] = -frame[2 * k + 1]
            }
            fft.realInverseFull(frame, true)
            val start = t * hop
            for (i in 0 until n) {
                val x = frame[i] * window[i]
                accum[start + i] += x
                envelope[start + i] += window[i] * window[i]
            }
        }

        val out = FloatArray(chunkSize)
        for (i in 0 until chunkSize) {
            val d = envelope[i + pad]
            out[i] = if (d > 1.0e-8f) finite(accum[i + pad] / d) else 0f
        }
        return out
    }

    private fun reflectPad(input: FloatArray): FloatArray {
        val out = FloatArray(input.size + 2 * pad)
        System.arraycopy(input, 0, out, pad, input.size)
        for (j in 0 until pad) {
            out[j] = input[pad - j]
            out[pad + input.size + j] = input[input.size - 2 - j]
        }
        return out
    }

    private fun finite(value: Float): Float = if (value.isFinite()) value else 0f
}
