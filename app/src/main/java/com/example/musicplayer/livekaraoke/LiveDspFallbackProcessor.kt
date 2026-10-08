package com.example.musicplayer.livekaraoke

import com.bmwanje.audiophile.vocalremover.NativeVocalRemover
import com.bmwanje.audiophile.vocalremover.VocalRemoverPipeline
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import kotlin.math.min

/**
 * Low-latency fallback for devices that cannot sustain 9482 neural inference
 * in real time.
 *
 * This intentionally reuses the existing PremiumVocalRemoverDSP spectral
 * fallback. It is not the same quality as neural separation, but it is
 * bounded, local, and immediately streamable, so a slow device still gets
 * working Live Karaoke instead of a multi-minute prebuffer/error.
 *
 * The neural 9482 offline engine is not modified by this class.
 */
internal class LiveDspFallbackProcessor(
    sampleRate: Int,
    settings: LiveKaraokeSettingsSnapshot,
    private val emit: (VocalSeparatorCore.Stereo) -> Unit,
) : AutoCloseable {

    companion object {
        private const val BLOCK = 8192
        private const val TAIL_SAMPLES_EXTRA = 2048
    }

    private val native = NativeVocalRemover(sampleRate)
    private val workL = FloatArray(BLOCK)
    private val workR = FloatArray(BLOCK)
    private val zeroL = FloatArray(BLOCK)
    private val zeroR = FloatArray(BLOCK)
    private val outL = FloatArray(BLOCK)
    private val outR = FloatArray(BLOCK)
    private var finished = false

    init {
        native.reset()
        native.setNeuralStemMode(false)
        native.setDepth(settings.depth)
        native.setFocus(settings.focus)
        native.setTransientProtection(settings.transientProtection)
        native.setDryWet(settings.dryWet)
        native.setStemGainDb(settings.stemGainDb)
        native.setOutputGainDb(settings.outputGainDb)
        native.setCeilingDb(settings.ceilingDb)
    }

    fun push(block: VocalSeparatorCore.Stereo) {
        check(!finished) { "Live DSP fallback is already finished" }
        require(block.size > 0)

        var offset = 0
        while (offset < block.size) {
            val n = min(BLOCK, block.size - offset)

            System.arraycopy(block.left, offset, workL, 0, n)
            System.arraycopy(block.right, offset, workR, 0, n)

            native.processBlock(
                mixL = workL,
                mixR = workR,
                vocalL = zeroL,
                vocalR = zeroR,
                outL = outL,
                outR = outR,
                count = n,
            )

            emit(
                VocalSeparatorCore.Stereo(
                    outL.copyOf(n),
                    outR.copyOf(n),
                )
            )

            offset += n
        }
    }

    fun finish() {
        check(!finished) { "Live DSP fallback is already finished" }
        finished = true

        /*
         * Flush the native DSP delay so the final source samples emerge.
         * A small extra tail keeps the causal spectral FIFO and limiter from
         * being truncated at EOS.
         */
        var remaining =
            native.latencySamples() + TAIL_SAMPLES_EXTRA

        while (remaining > 0) {
            val n = min(BLOCK, remaining)

            native.processBlock(
                mixL = zeroL,
                mixR = zeroR,
                vocalL = zeroL,
                vocalR = zeroR,
                outL = outL,
                outR = outR,
                count = n,
            )

            emit(
                VocalSeparatorCore.Stereo(
                    outL.copyOf(n),
                    outR.copyOf(n),
                )
            )

            remaining -= n
        }
    }

    override fun close() {
        runCatching { native.close() }
    }
}

/**
 * Snapshot of the Live Karaoke DSP controls without coupling the fallback to
 * the Session object itself.
 */
internal data class LiveKaraokeSettingsSnapshot(
    val depth: Float,
    val focus: Float,
    val transientProtection: Float,
    val dryWet: Float,
    val stemGainDb: Float,
    val outputGainDb: Float,
    val ceilingDb: Float,
)
