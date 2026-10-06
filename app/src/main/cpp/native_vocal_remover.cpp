package com.bmwanje.audiophile.vocalremover

/** JNI bridge to the corrected PremiumVocalRemoverDSP. */
class NativeVocalRemover(sampleRate: Int) : AutoCloseable {
    private var handle: Long = nativeCreate(sampleRate.toDouble()).also { check(it != 0L) { "Failed to create native vocal-remover engine" } }

    fun setDepth(value: Float) = nativeSetDepth(requireHandle(), value)
    fun setFocus(value: Float) = nativeSetFocus(requireHandle(), value)
    fun setTransientProtection(value: Float) = nativeSetTransientProtection(requireHandle(), value)
    fun setStemGainDb(db: Float) = nativeSetStemGainDb(requireHandle(), db)
    fun setDryWet(value: Float) = nativeSetDryWet(requireHandle(), value)
    fun setOutputGainDb(db: Float) = nativeSetOutputGainDb(requireHandle(), db)
    fun setCeilingDb(db: Float) = nativeSetCeilingDb(requireHandle(), db)

    fun processBlock(
        mixL: FloatArray,
        mixR: FloatArray,
        vocalL: FloatArray,
        vocalR: FloatArray,
        outL: FloatArray,
        outR: FloatArray,
    ) {
        require(mixL.size == mixR.size && vocalL.size == mixL.size && vocalR.size == mixL.size)
        require(outL.size >= mixL.size && outR.size >= mixL.size)
        nativeProcess(
            requireHandle(), mixL, mixR, vocalL, vocalR, outL, outR, mixL.size,
        )
    }

    fun reset() = nativeReset(requireHandle())
    fun latencySamples(): Int = nativeLatency(requireHandle())

    /**
     * Offline render. The native DSP latency is removed so the returned audio
     * has exactly the same number of samples and timeline as the input.
     */
    fun renderOffline(
        mix: VocalSeparatorCore.Stereo,
        vocals: VocalSeparatorCore.Stereo,
        blockSize: Int = 4096,
    ): VocalSeparatorCore.Stereo {
        require(mix.size == vocals.size)
        require(blockSize > 0)
        reset()
        nativeSetNeuralMode(requireHandle(), true)

        val n = mix.size
        val latency = latencySamples()
        val tail = latency + 2048
        val rawL = FloatArray(n + tail)
        val rawR = FloatArray(n + tail)
        val inL = FloatArray(blockSize)
        val inR = FloatArray(blockSize)
        val vL = FloatArray(blockSize)
        val vR = FloatArray(blockSize)
        val oL = FloatArray(blockSize)
        val oR = FloatArray(blockSize)
        var rawPos = 0
        var pos = 0

        while (pos < n) {
            val count = minOf(blockSize, n - pos)
            java.lang.System.arraycopy(mix.left, pos, inL, 0, count)
            java.lang.System.arraycopy(mix.right, pos, inR, 0, count)
            java.lang.System.arraycopy(vocals.left, pos, vL, 0, count)
            java.lang.System.arraycopy(vocals.right, pos, vR, 0, count)
            nativeProcess(requireHandle(), inL, inR, vL, vR, oL, oR, count)
            java.lang.System.arraycopy(oL, 0, rawL, rawPos, count)
            java.lang.System.arraycopy(oR, 0, rawR, rawPos, count)
            rawPos += count
            pos += count
        }

        val zero = FloatArray(blockSize)
        var remaining = tail
        while (remaining > 0) {
            val count = minOf(blockSize, remaining)
            nativeProcess(requireHandle(), zero, zero, zero, zero, oL, oR, count)
            java.lang.System.arraycopy(oL, 0, rawL, rawPos, count)
            java.lang.System.arraycopy(oR, 0, rawR, rawPos, count)
            rawPos += count
            remaining -= count
        }

        val alignedL = FloatArray(n)
        val alignedR = FloatArray(n)
        java.lang.System.arraycopy(rawL, latency, alignedL, 0, n)
        java.lang.System.arraycopy(rawR, latency, alignedR, 0, n)
        return VocalSeparatorCore.Stereo(alignedL, alignedR)
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "NativeVocalRemover is closed" }
        return handle
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Double): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeLatency(handle: Long): Int
    private external fun nativeSetDepth(handle: Long, value: Float)
    private external fun nativeSetFocus(handle: Long, value: Float)
    private external fun nativeSetTransientProtection(handle: Long, value: Float)
    private external fun nativeSetStemGainDb(handle: Long, db: Float)
    private external fun nativeSetDryWet(handle: Long, value: Float)
    private external fun nativeSetOutputGainDb(handle: Long, db: Float)
    private external fun nativeSetCeilingDb(handle: Long, db: Float)
    private external fun nativeSetNeuralMode(handle: Long, enabled: Boolean)
    private external fun nativeProcess(
        handle: Long,
        mixL: FloatArray,
        mixR: FloatArray,
        vocalL: FloatArray,
        vocalR: FloatArray,
        outL: FloatArray,
        outR: FloatArray,
        n: Int,
    )
    companion object {
        init { System.loadLibrary("audiophile_vocal_remover") }
    }
}
