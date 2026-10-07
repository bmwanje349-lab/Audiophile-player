package com.bmwanje.audiophile.vocalremover

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * ONNX Runtime runner for the verified UVR MDX-Net 9482 model.
 *
 * The legacy filename remains for source compatibility, but there is no
 * LiteRT dependency or execution path in this implementation.
 */
class MdxOnnxVocalModelRunner(
    modelPath: String,
    private val modelSpec: MdxModelSpec,
    cpuThreads: Int = 4,
) : AutoCloseable, MdxSeparatorCore.Runner {

    private val stft = MdxStft(modelSpec)
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(cpuThreads.coerceAtLeast(1))
            setInterOpNumThreads(1)
        }
        session = environment.createSession(modelPath, options)
        inputName = session.inputNames.singleOrNull()
            ?: error("9482 ONNX model must expose exactly one input")
        outputName = session.outputNames.singleOrNull()
            ?: error("9482 ONNX model must expose exactly one output")
        validateTensor("input", session.inputInfo[inputName]?.info)
        validateTensor("output", session.outputInfo[outputName]?.info)
    }

    @Synchronized
    override fun separateChunk(
        left: FloatArray,
        right: FloatArray,
    ): MdxStft.StereoChunk {
        val input = stft.forward(left, right)
        val shape = longArrayOf(1L, 4L, modelSpec.dimF.toLong(), modelSpec.dimT.toLong())

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result[outputName].orElseThrow {
                    IllegalStateException("9482 ONNX output '$outputName' is missing")
                }
                val outputTensor = value as? OnnxTensor
                    ?: error("9482 ONNX output is not a tensor")
                val outputBuffer = outputTensor.getFloatBuffer()
                    ?: error("9482 ONNX output is not float-compatible")
                val output = FloatArray(outputBuffer.remaining()).also { outputBuffer.get(it) }

                require(output.size == stft.tensorSize()) {
                    "Unexpected MDX output size: " + output.size +
                        " != " + stft.tensorSize()
                }

                val (vocL, vocR) = stft.inverse(MdxStft.Spectrogram(output))
                return MdxStft.StereoChunk(vocL, vocR)
            }
        }
    }

    private fun validateTensor(role: String, info: ai.onnxruntime.ValueInfo?) {
        val tensorInfo = info as? TensorInfo
            ?: error("9482 ONNX " + role + " is not a tensor")

        val expectedShape = longArrayOf(
            1L,
            4L,
            modelSpec.dimF.toLong(),
            modelSpec.dimT.toLong(),
        )
        val actualShape = tensorInfo.getShape()

        require(
            actualShape.size == 4 &&
                (actualShape[0] == -1L || actualShape[0] == 1L) &&
                actualShape[1] == expectedShape[1] &&
                actualShape[2] == expectedShape[2] &&
                actualShape[3] == expectedShape[3]
        ) {
            "Unexpected 9482 ONNX " + role + " shape: " +
                actualShape.contentToString() +
                " expected [batch,4,2048,256]"
        }

        require(tensorInfo.type == OnnxJavaType.FLOAT) {
            "9482 ONNX " + role + " must be float32, got " + tensorInfo.type
        }
    }

    override fun close() {
        session.close()
    }
}
