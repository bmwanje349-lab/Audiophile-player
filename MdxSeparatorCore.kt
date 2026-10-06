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
}
