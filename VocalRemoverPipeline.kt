package com.bmwanje.audiophile.vocalremover

import android.content.Context

/**
 * End-to-end: input PCM -> ONNX MDX vocal stem -> existing premium DSP.
 */
class VocalRemoverPipeline(
    context: Context,
    sampleRate: Int,
    private val modelSpec: MdxModelSpec = MdxModelSpec.LIGHT_9482,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val sampleRate = sampleRate
    private val native = NativeVocalRemover(sampleRate)
    private var runner: MdxOnnxVocalModelRunner? = null

    fun loadModel(progress: ((done: Long, total: Long) -> Unit)? = null) {
        val file = MdxModelManager.ensureInstalled(appContext, modelSpec, progress)
        val replacement = MdxOnnxVocalModelRunner(file.absolutePath, modelSpec)
        val previous = runner
        runner = replacement
        previous?.close()
    }

    fun setDepth(value: Float) = native.setDepth(value)
    fun setFocus(value: Float) = native.setFocus(value)
    fun setTransientProtection(value: Float) = native.setTransientProtection(value)
    fun setStemGainDb(db: Float) = native.setStemGainDb(db)
    fun setDryWet(value: Float) = native.setDryWet(value)
    fun setOutputGainDb(db: Float) = native.setOutputGainDb(db)
    fun setCeilingDb(db: Float) = native.setCeilingDb(db)

    fun separateAndRemove(input: VocalSeparatorCore.Stereo): VocalSeparatorCore.Stereo {
        require(input.size > 0)
        val activeRunner = runner ?: error("MDX model is not loaded")
        val vocals = MdxSeparatorCore.separate(
            input = input,
            sampleRate = sampleRate,
            modelSpec = modelSpec,
            runner = activeRunner,
        )
        return native.renderOffline(input, vocals)
    }

    override fun close() {
        runner?.close()
        runner = null
        native.close()
    }
}
