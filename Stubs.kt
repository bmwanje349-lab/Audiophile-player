package com.google.ai.edge.litert

class Accelerator { companion object { val GPU = Accelerator(); val CPU = Accelerator() } }

class TensorBuffer(private val size: Int) : AutoCloseable {
    private var data = FloatArray(size)
    fun writeFloat(values: FloatArray) { require(values.size == size); data = values.copyOf() }
    fun readFloat(): FloatArray = data.copyOf()
    override fun close() {}
}

class CompiledModel private constructor() : AutoCloseable {
    class CpuOptions(val numThreads: Int)
    class Options(vararg accelerators: Accelerator) {
        var cpuOptions: CpuOptions? = null
    }
    companion object {
        fun create(path: String, options: Options, env: Any?): CompiledModel = CompiledModel()
    }
    fun createInputBuffers(): List<TensorBuffer> = listOf(TensorBuffer(4 * 2048 * 256))
    fun createOutputBuffers(): List<TensorBuffer> = listOf(TensorBuffer(4 * 2048 * 256))
    fun run(input: List<TensorBuffer>, output: List<TensorBuffer>) {}
    override fun close() {}
}
