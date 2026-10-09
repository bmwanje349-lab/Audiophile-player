package com.example.musicplayer.livekaraoke

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.musicplayer.EqSettingsStore
import com.example.peq.PeqAudioProcessor
import com.example.peq.PeqEngine
import com.example.peq.WidenerAudioProcessor
import com.example.peq.WidenerEngine
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Session-owned copy of the app's normal PEQ -> stereo widener chain.
 *
 * Live Karaoke writes to its own AudioTrack rather than Media3's AudioSink, so
 * it must explicitly run these same processors. Separate engine instances keep
 * the normal player's filter state and lifecycle untouched.
 *
 * All methods are called on the Live Karaoke output-producer thread; the audio
 * callback never performs EQ, widening, allocation-heavy setup, or file access.
 */
@UnstableApi
internal class LiveKaraokeAudioEffects(
    context: Context,
    sampleRate: Int,
) : AutoCloseable {

    private val peqEngine = PeqEngine(16)
    private val widenerEngine = WidenerEngine()
    private val pipeline =
        AudioProcessingPipeline(
            ImmutableList.of<AudioProcessor>(
                PeqAudioProcessor(peqEngine),
                WidenerAudioProcessor(widenerEngine),
            ),
        )

    private var inputBuffer =
        ByteBuffer
            .allocateDirect(DEFAULT_INPUT_BUFFER_BYTES)
            .order(ByteOrder.nativeOrder())

    private var closed = false
    private var finished = false

    init {
        require(sampleRate > 0) { "Live Karaoke sample rate must be positive" }

        try {
            // Read exactly the same persisted settings used by PlaybackService.
            EqSettingsStore(context.applicationContext)
                .applyTo(peqEngine, widenerEngine)

            pipeline.configure(
                AudioProcessor.AudioFormat(
                    sampleRate,
                    CHANNELS,
                    C.ENCODING_PCM_16BIT,
                ),
            )
            pipeline.flush()
        } catch (failure: Throwable) {
            runCatching { pipeline.reset() }
            runCatching { peqEngine.close() }
            runCatching { widenerEngine.close() }
            closed = true
            throw failure
        }
    }

    /**
     * Processes one ordered, interleaved stereo PCM16 block. Output is delivered
     * synchronously and must be consumed/copied before this callback returns.
     */
    @Synchronized
    fun process(
        pcm: ShortArray,
        emit: (ShortArray) -> Unit,
    ) {
        check(!closed) { "Live Karaoke effects are closed" }
        check(!finished) { "Live Karaoke effects already reached end of stream" }
        require(pcm.size % CHANNELS == 0) {
            "Stereo PCM16 input must contain complete frames"
        }
        if (pcm.isEmpty()) return

        val requiredBytes = pcm.size * Short.SIZE_BYTES
        check(requiredBytes <= MAX_INPUT_BUFFER_BYTES) {
            "Live Karaoke PCM block exceeds the bounded effects input buffer"
        }
        if (requiredBytes > inputBuffer.capacity()) {
            var capacity = inputBuffer.capacity()
            while (capacity < requiredBytes) {
                capacity = (capacity * 2).coerceAtMost(MAX_INPUT_BUFFER_BYTES)
            }
            inputBuffer =
                ByteBuffer
                    .allocateDirect(capacity)
                    .order(ByteOrder.nativeOrder())
        }

        inputBuffer.clear()
        for (sample in pcm) inputBuffer.putShort(sample)
        inputBuffer.flip()

        pipeline.queueInput(inputBuffer)
        check(!inputBuffer.hasRemaining()) {
            "PEQ/widener pipeline did not consume the full input block"
        }
        drainAvailable(emit)
    }

    /**
     * Flushes each processor's legitimate buffered tail, in chain order, exactly
     * once. No stale tail is retained for the next track or seek session.
     */
    @Synchronized
    fun finish(emit: (ShortArray) -> Unit) {
        if (closed || finished) return
        finished = true

        pipeline.queueEndOfStream()
        var iterations = 0
        while (!pipeline.isEnded) {
            check(++iterations <= MAX_EOS_DRAIN_ITERATIONS) {
                "PEQ/widener pipeline did not finish draining its final audio"
            }

            val output = pipeline.output
            if (output.hasRemaining()) {
                emitOutput(output, emit)
            }
        }
    }

    private fun drainAvailable(emit: (ShortArray) -> Unit) {
        var iterations = 0
        while (true) {
            val output = pipeline.output
            if (!output.hasRemaining()) return

            check(++iterations <= MAX_NORMAL_DRAIN_ITERATIONS) {
                "PEQ/widener pipeline produced too many blocks for one input"
            }
            emitOutput(output, emit)
        }
    }

    private fun emitOutput(
        output: ByteBuffer,
        emit: (ShortArray) -> Unit,
    ) {
        val bytes = output.remaining()
        check(bytes % BYTES_PER_FRAME == 0) {
            "PEQ/widener produced a partial stereo PCM16 frame"
        }

        if (bytes > 0) {
            val shorts =
                output
                    .slice()
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer()
            val pcm = ShortArray(shorts.remaining())
            shorts.get(pcm)

            // AudioProcessingPipeline retains its output buffers until consumed.
            output.position(output.limit())
            if (pcm.isNotEmpty()) emit(pcm)
        } else {
            output.position(output.limit())
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { pipeline.reset() }
        runCatching { peqEngine.close() }
        runCatching { widenerEngine.close() }
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * Short.SIZE_BYTES
        const val DEFAULT_INPUT_BUFFER_BYTES = 32 * 1024
        const val MAX_INPUT_BUFFER_BYTES = 1024 * 1024
        const val MAX_NORMAL_DRAIN_ITERATIONS = 64
        const val MAX_EOS_DRAIN_ITERATIONS = 256
    }
}
