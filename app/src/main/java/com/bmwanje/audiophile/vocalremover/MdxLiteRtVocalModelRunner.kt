package com.bmwanje.audiophile.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * ONNX Runtime runner for the verified UVR MDX-Net 9482 vocal model.
 *
 * The host-side MdxStft produces the model's [1,4,dimF,256] tensor and the
 * ONNX model predicts the vocal spectrogram. The result is converted back to
 * PCM by MdxStft.inverse().
 */
class MdxLiteRtVocalModelRunner(
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

        validateModelShape()
    }

    @Synchronized
    override fun separateChunk(
        left: FloatArray,
        right: FloatArray,
    ): MdxStft.StereoChunk {
        val input = stft.forward(left, right)

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input.data),
            longArrayOf(
                1L,
                4L,
                modelSpec.dimF.toLong(),
                modelSpec.dimT.toLong(),
            ),
        )

        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result[outputName].orElseThrow {
                    IllegalStateException(
                        "9482 ONNX output '" + outputName + "' is missing"
                    )
                }

                val outputTensor = value as? OnnxTensor
                    ?: error("9482 ONNX output is not a tensor")

                val output = outputTensor.floatBufferCopy()
                require(output.size == stft.tensorSize()) {
                    "Unexpected MDX output size: " +
                        output.size + " != " + stft.tensorSize()
                }

                val vocalSpec = MdxStft.Spectrogram(output)
                val (vocL, vocR) = stft.inverse(vocalSpec)
                return MdxStft.StereoChunk(vocL, vocR)
            }
        }
    }

    private fun validateModelShape() {
        val info = session.inputInfo[inputName]
            ?: error("9482 ONNX input metadata is unavailable")

        val shape = info.info.shape
        val expected = longArrayOf(
            1L,
            4L,
            modelSpec.dimF.toLong(),
            modelSpec.dimT.toLong(),
        )

        require(shape.contentEquals(expected)) {
            "Unexpected 9482 ONNX input shape: " +
                shape.contentToString() +
                " expected " +
                expected.contentToString()
        }
    }

    private fun OnnxTensor.floatBufferCopy(): FloatArray {
        val buffer = floatBuffer
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    override fun close() {
        session.close()
    }
}
