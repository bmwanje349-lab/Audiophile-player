package com.bmwanje.audiophile.vocalremover

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer

/**
 * LiteRT runner for a UVR MDX-Net vocal model.
 *
 * The model predicts the vocal spectrogram. The output is converted back to a
 * vocal PCM stem by MdxStft.inverse(). Hardware selection is GPU+CPU first,
 * with CPU fallback so unsupported devices still work.
 */
class MdxLiteRtVocalModelRunner(
    modelPath: String,
    private val modelSpec: MdxModelSpec,
    cpuThreads: Int = 4,
) : AutoCloseable, MdxSeparatorCore.Runner {

    private val stft = MdxStft(modelSpec)
    private val model: CompiledModel
    private lateinit var inputBuffers: List<TensorBuffer>
    private lateinit var outputBuffers: List<TensorBuffer>

    init {
        model = createModel(modelPath, cpuThreads)
        inputBuffers = model.createInputBuffers()
        outputBuffers = model.createOutputBuffers()
        require(inputBuffers.size == 1) { "MDX model must expose one input tensor" }
        require(outputBuffers.size == 1) { "MDX model must expose one output tensor" }
    }

    @Synchronized
    override fun separateChunk(left: FloatArray, right: FloatArray): MdxStft.StereoChunk {
        val input = stft.forward(left, right)
        inputBuffers[0].writeFloat(input.data)
        model.run(inputBuffers, outputBuffers)
        val output = outputBuffers[0].readFloat()
        require(output.size == stft.tensorSize()) {
            "Unexpected MDX output size: ${output.size} != ${stft.tensorSize()}"
        }
        val vocalSpec = MdxStft.Spectrogram(output)
        val (vocL, vocR) = stft.inverse(vocalSpec)
        return MdxStft.StereoChunk(vocL, vocR)
    }

    private fun createModel(path: String, cpuThreads: Int): CompiledModel {
        val gpuThenCpu = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads.coerceAtLeast(1))
        }
        try {
            return CompiledModel.create(path, gpuThenCpu, null)
        } catch (_: Throwable) {
            val cpuOnly = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads.coerceAtLeast(1))
            }
            return CompiledModel.create(path, cpuOnly, null)
        }
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
    }
}
