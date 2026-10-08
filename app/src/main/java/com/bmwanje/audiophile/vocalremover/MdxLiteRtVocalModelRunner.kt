package com.bmwanje.audiophile.vocalremover

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    private val xnnpackEnabled: Boolean
    private val inputShape =
        longArrayOf(
            1L,
            4L,
            modelSpec.dimF.toLong(),
            modelSpec.dimT.toLong(),
        )

    /*
     * Keep one tensor-sized heap scratch buffer and one direct FloatBuffer.
     * ONNX Runtime can otherwise allocate/copy a direct buffer internally for
     * each non-direct FloatBuffer input.
     */
    private val inputScratch = FloatArray(stft.tensorSize())
    private val directInput =
        ByteBuffer
            .allocateDirect(stft.tensorSize() * java.lang.Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val outputScratch = FloatArray(stft.tensorSize())

    private var inferenceCounter = 0L

    init {
        val requestedThreads = cpuThreads.coerceAtLeast(1)
        var configuredOptions: OrtSession.SessionOptions? = null
        var configuredSession: OrtSession? = null
        var configuredWithXnnpack = false

        try {
            /*
             * XNNPACK is an optional accelerator in the Android ORT package.
             * It has its own CPU thread pool, so keep ORT's intra-op pool at
             * one thread when XNNPACK is active to avoid CPU oversubscription.
             * The fallback below keeps the model fully functional if a build
             * or device does not expose XNNPACK.
             */
            val xnnOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                addConfigEntry(
                    "session.intra_op.allow_spinning",
                    "0",
                )
                addConfigEntry(
                    "session.inter_op.allow_spinning",
                    "0",
                )
                addXnnpack(
                    mapOf(
                        "intra_op_num_threads" to
                            requestedThreads.toString(),
                    )
                )
            }

            try {
                configuredSession =
                    environment.createSession(
                        modelPath,
                        xnnOptions,
                    )
                configuredOptions = xnnOptions
                configuredWithXnnpack = true
            } catch (xnnFailure: Throwable) {
                runCatching { xnnOptions.close() }

                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(requestedThreads)
                    setInterOpNumThreads(1)
                }

                try {
                    configuredSession =
                        environment.createSession(
                            modelPath,
                            cpuOptions,
                        )
                    configuredOptions = cpuOptions
                } catch (cpuFailure: Throwable) {
                    runCatching { cpuOptions.close() }
                    cpuFailure.addSuppressed(xnnFailure)
                    throw cpuFailure
                }
            }

            sessionOptions =
                configuredOptions
                    ?: error("ONNX session options were not initialized")
            session =
                configuredSession
                    ?: error("ONNX session was not initialized")
            xnnpackEnabled = configuredWithXnnpack

            inputName = session.inputNames.singleOrNull()
                ?: error("9482 ONNX model must expose exactly one input")
            outputName = session.outputNames.singleOrNull()
                ?: error("9482 ONNX model must expose exactly one output")
            validateTensor("input", session.inputInfo[inputName]?.info)
            validateTensor("output", session.outputInfo[outputName]?.info)
        } catch (throwable: Throwable) {
            runCatching { configuredSession?.close() }
            runCatching { configuredOptions?.close() }
            throw throwable
        }
    }


    @Synchronized
    override fun separateChunk(
        left: FloatArray,
        right: FloatArray,
    ): MdxStft.StereoChunk {
        require(left.size == stft.chunkSizeSamples())
        require(right.size == stft.chunkSizeSamples())

        stft.forwardInto(left, right, inputScratch)
        directInput.clear()
        directInput.put(inputScratch)
        directInput.flip()

        OnnxTensor.createTensor(environment, directInput, inputShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result[outputName].orElseThrow {
                    IllegalStateException("9482 ONNX output '$outputName' is missing")
                }
                val outputTensor = value as? OnnxTensor
                    ?: error("9482 ONNX output is not a tensor")
                val outputBuffer = outputTensor.getFloatBuffer()
                    ?: error("9482 ONNX output is not float-compatible")

                require(outputBuffer.remaining() == outputScratch.size) {
                    "Unexpected MDX output size: " + outputBuffer.remaining() +
                        " != " + outputScratch.size
                }
                outputBuffer.get(outputScratch, 0, outputScratch.size)

                inferenceCounter += 1L
                val (vocL, vocR) =
                    stft.inverse(
                        MdxStft.Spectrogram(outputScratch)
                    )
                return MdxStft.StereoChunk(vocL, vocR)
            }
        }
    }

    /** Number of real ONNX Runtime inference calls completed by this runner. */
    fun inferenceCount(): Long = synchronized(this) { inferenceCounter }

    fun inferenceBackend(): String =
        if (xnnpackEnabled) "XNNPACK" else "CPU"

    private fun validateTensor(role: String, info: ai.onnxruntime.ValueInfo?) {
        val tensorInfo = info as? TensorInfo
            ?: error("9482 ONNX " + role + " is not a tensor")

        val expectedShape = inputShape
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
        runCatching { session.close() }
            .also { runCatching { sessionOptions.close() } }
    }
}
