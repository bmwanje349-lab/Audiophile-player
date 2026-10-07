package ai.onnxruntime

import java.nio.FloatBuffer

enum class OnnxJavaType { FLOAT }

interface ValueInfo

class TensorInfo(private val shape: LongArray) : ValueInfo {
    val type: OnnxJavaType = OnnxJavaType.FLOAT
    fun getShape(): LongArray = shape.copyOf()
}

class NodeInfo(val info: ValueInfo)

class OnnxTensor private constructor() : AutoCloseable {
    companion object {
        fun createTensor(env: OrtEnvironment, data: FloatBuffer, shape: LongArray): OnnxTensor =
            OnnxTensor()
    }
    fun getFloatBuffer(): FloatBuffer? = FloatBuffer.wrap(FloatArray(0))
    override fun close() {}
}

class OrtEnvironment {
    companion object {
        fun getEnvironment(): OrtEnvironment = OrtEnvironment()
    }
    fun createSession(path: String, options: OrtSession.SessionOptions): OrtSession = OrtSession()
}

class OrtSession : AutoCloseable {
    class SessionOptions {
        fun setIntraOpNumThreads(threads: Int) {}
        fun setInterOpNumThreads(threads: Int) {}
    }
    class Result : AutoCloseable {
        override fun close() {}
    }
    val inputNames: Set<String> = setOf("input")
    val outputNames: Set<String> = setOf("output")
    val inputInfo: Map<String, NodeInfo> =
        mapOf("input" to NodeInfo(TensorInfo(longArrayOf(1, 4, 2048, 256))))
    val outputInfo: Map<String, NodeInfo> =
        mapOf("output" to NodeInfo(TensorInfo(longArrayOf(1, 4, 2048, 256))))
    override fun close() {}
}
