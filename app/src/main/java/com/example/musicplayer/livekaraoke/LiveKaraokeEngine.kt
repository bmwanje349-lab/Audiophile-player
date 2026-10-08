package com.example.musicplayer.livekaraoke

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import com.bmwanje.audiophile.vocalremover.MdxModelSpec
import com.bmwanje.audiophile.vocalremover.MdxStft
import com.bmwanje.audiophile.vocalremover.StreamingVocalRemover
import com.bmwanje.audiophile.vocalremover.VocalRemoverPipeline
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Calculates the fixed minimum startup PCM budget for Live Karaoke.
 *
 * A second, one-time safety calculation may increase this minimum when the
 * measured neural producer is slower than real time. That safety target is
 * frozen before playback begins, so it cannot move underneath the user.
 */
internal fun calculateLiveKaraokeStartupBufferFrames(
    sourceSampleRate: Int,
    generatedPerWindow: Int,
    startupBufferSeconds: Int,
    startupBufferWindows: Int,
    maxLookaheadFrames: Int,
): Int {
    require(sourceSampleRate > 0)
    require(generatedPerWindow > 0)
    require(startupBufferSeconds > 0)
    require(startupBufferWindows > 0)
    require(maxLookaheadFrames > 0)

    val timeBased =
        (
            sourceSampleRate.toLong() *
                startupBufferSeconds.toLong()
        )
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    val windowBased =
        generatedPerWindow.toLong() *
            startupBufferWindows.toLong()

    return min(
        maxLookaheadFrames,
        max(
            1,
            max(
                timeBased,
                windowBased
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
            ),
        ),
    )
}

/**
 * Computes the one-time safety buffer needed for a stream whose measured
 * production rate is [measuredProducerRate] times real time.
 *
 * The inequality is:
 *
 *   startupBuffer >= remainingDuration * (1 - rate) + safetyMargin
 *
 * for rate < 1.0. At or above real time, the fixed startup minimum is enough.
 *
 * This target is calculated once after the throughput calibration window is
 * complete. It must never change after playback starts.
 */
internal fun calculateLiveKaraokeSafeBufferFrames(
    sourceSampleRate: Int,
    generatedPerWindow: Int,
    startupBufferSeconds: Int,
    startupBufferWindows: Int,
    remainingSeconds: Double,
    measuredProducerRate: Double,
    safetyMarginSeconds: Double,
    maxLookaheadFrames: Int,
): Int {
    require(sourceSampleRate > 0)
    require(generatedPerWindow > 0)
    require(startupBufferSeconds > 0)
    require(startupBufferWindows > 0)
    require(remainingSeconds >= 0.0)
    require(measuredProducerRate.isFinite() && measuredProducerRate > 0.0)
    require(safetyMarginSeconds >= 0.0)
    require(maxLookaheadFrames > 0)

    val fixedMinimumFrames =
        calculateLiveKaraokeStartupBufferFrames(
            sourceSampleRate = sourceSampleRate,
            generatedPerWindow = generatedPerWindow,
            startupBufferSeconds = startupBufferSeconds,
            startupBufferWindows = startupBufferWindows,
            maxLookaheadFrames = maxLookaheadFrames,
        )

    val requiredSeconds =
        if (measuredProducerRate < 1.0) {
            max(
                startupBufferSeconds.toDouble(),
                remainingSeconds *
                    (1.0 - measuredProducerRate) +
                    safetyMarginSeconds,
            )
        } else {
            startupBufferSeconds.toDouble()
        }

    val timeBased =
        (
            sourceSampleRate.toDouble() *
                requiredSeconds
        )
            .roundToInt()
            .coerceAtLeast(1)

    return min(
        maxLookaheadFrames,
        max(
            fixedMinimumFrames,
            timeBased,
        ),
    )
}

internal fun audioTrackPlaybackHeadFrames(rawPosition: Int): Long =
    rawPosition.toLong() and 0xFFFF_FFFFL

internal fun calculateLiveKaraokePrerollFrames(
    playbackStartMs: Long,
    actualDecodeStartUs: Long,
    sampleRate: Int,
): Int {
    require(playbackStartMs >= 0L)
    require(actualDecodeStartUs >= 0L)
    require(sampleRate > 0)

    val prerollUs =
        (
            playbackStartMs * 1_000L -
                actualDecodeStartUs
        )
            .coerceAtLeast(0L)

    return (
        prerollUs.toDouble() *
            sampleRate.toDouble() /
            1_000_000.0
        )
            .roundToInt()
            .coerceAtLeast(0)
}

internal fun liveKaraokePositionMs(
    playbackStartMs: Long,
    playbackFrames: Long,
    sampleRate: Int,
    durationMs: Long,
): Long {
    require(playbackStartMs >= 0L)
    require(playbackFrames >= 0L)
    require(sampleRate > 0)

    val elapsedMs =
        (playbackFrames.toDouble() * 1000.0 /
            sampleRate.toDouble())
            .roundToInt()
            .toLong()

    return (playbackStartMs + elapsedMs)
        .coerceAtMost(
            durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
}

/**
 * True progressive karaoke path.
 *
 * It reuses the verified MDX-Net + PremiumVocalRemoverDSP pipeline, but sends
 * completed PCM blocks to AudioTrack as soon as the first live window is ready.
 * A bounded queue lets inference continue ahead of the playhead without ever
 * accumulating a complete track.
 *
 * Seeking is a restart of the live pipeline with one MDX window of preroll.
 * The preroll gives the neural window context, then those samples are dropped
 * before playback resumes at the requested position.
 */
class LiveKaraokeEngine(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {

    interface Listener {
        fun onState(state: State, message: String)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onError(error: Throwable)
        fun onCompleted()
    }

    enum class State {
        STOPPED,
        BUFFERING,
        PLAYING,
        PAUSED,
        SEEKING,
    }

    data class Settings(
        val depth: Float = 1f,
        val focus: Float = 0.5f,
        val transientProtection: Float = 0.7f,
        val dryWet: Float = 1f,
        val stemGainDb: Float = 0f,
        val outputGainDb: Float = 0f,
        val ceilingDb: Float = -1f,
    )

    private val appContext = context.applicationContext
    private val modelSpec = MdxModelSpec.LIGHT_9482

    private companion object {
        // A single MDX window is about 5.8 s of generated audio. Starting
        // playback with only one window leaves no margin for normal phone-side
        // inference jitter, decoder work, GC, or thermal throttling. Keep a
        // multi-window queue and require a real safety buffer before play.
        // The neural separator is very close to real-time on some phones.
        // A short queue therefore looks healthy at launch but can still drain
        // after several inference windows. Keep a deep bounded head-start so
        // small throughput deficits and transient CPU/GC stalls are absorbed
        // without ever pausing AudioTrack.
        const val MAX_LOOKAHEAD_FRAMES = 5_292_000 // ~120 s @ 44.1 kHz
        const val STARTUP_BUFFER_SECONDS = 30
        const val STARTUP_BUFFER_WINDOWS = 5
        const val RATE_ESTIMATION_MIN_SECONDS = 10.0
        const val LIVE_SAFETY_MARGIN_SECONDS = 6.0
        const val AUDIO_TRACK_BUFFER_SECONDS = 6
        const val MAX_AUDIO_DRAIN_WAIT_MS = 15_000L
        const val UI_UPDATE_INTERVAL_MS = 250L
        const val SEEK_CONTEXT_MARGIN_MS = 100L
    }

    private val lock = Any()
    private var session: Session? = null
    private var generation = 0L
    private var closed = false

    fun start(uri: Uri, positionMs: Long = 0L, settings: Settings = Settings()) {
        synchronized(lock) {
            check(!closed) { "Live karaoke engine is closed" }
            stopLocked()

            generation += 1L
            val next =
                Session(
                    id = generation,
                    uri = uri,
                    requestedPositionMs = positionMs.coerceAtLeast(0L),
                    settings = settings,
                )
            session = next
            next.start()
        }
    }

    fun pause() {
        synchronized(lock) {
            session?.pause()
        }
    }

    fun resume() {
        synchronized(lock) {
            session?.resume()
        }
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            val current = session ?: return
            stopLocked()

            generation += 1L
            val next =
                Session(
                    id = generation,
                    uri = current.uri,
                    requestedPositionMs = positionMs.coerceAtLeast(0L),
                    settings = current.settings,
                )
            session = next
            listener.onState(
                State.SEEKING,
                "Seeking — rebuilding MDX context…",
            )
            next.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            generation += 1L
        }
    }

    fun currentState(): State =
        synchronized(lock) {
            session?.state ?: State.STOPPED
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopLocked()
        }
    }

    private fun stopLocked() {
        session?.stop()
        session = null
        listener.onState(State.STOPPED, "Live karaoke stopped")
    }

    private fun isCurrent(id: Long): Boolean =
        synchronized(lock) {
            !closed &&
                generation == id &&
                session?.id == id
        }

    private inner class Session(
        val id: Long,
        val uri: Uri,
        val requestedPositionMs: Long,
        val settings: Settings,
    ) {
        @Volatile
        var state: State = State.BUFFERING
            private set

        @Volatile
        private var sourceSampleRate = 44_100

        @Volatile
        private var durationMs = 0L

        @Volatile
        private var playbackStartMs = requestedPositionMs

        @Volatile
        /*
         * Frames submitted to AudioTrack are not the same as frames already
         * heard by the user. AudioTrack can hold several seconds of PCM.
         * Keep this count only for the completion/drain contract; progress is
         * derived from AudioTrack.playbackHeadPosition.
         */
        @Volatile
        private var submittedFrames = 0L

        @Volatile
        private var producerFinished = false

        private var lastUiUpdateNs = 0L

        @Volatile
        private var estimatedProducerRate = Double.NaN

        private var producerRateStartNs = 0L
        private var producerRateFrames = 0L
        private var startupTargetFrames = 0
        private var startupTargetFinalized = false

        private val cancelled = AtomicBoolean(false)
        private val queue =
            LivePcmQueue(
                maxFrames = MAX_LOOKAHEAD_FRAMES,
            )

        private var executor: ExecutorService? = null
        private var audioTrack: AudioTrack? = null
        private var pipeline: VocalRemoverPipeline? = null
        private var consumerStarted = false
        private var droppedPrerollFrames = 0

        fun start() {
            executor =
                Executors.newFixedThreadPool(
                    2,
                ) { runnable ->
                    Thread(
                        runnable,
                        "Audiophile-LiveKaraoke-$id",
                    ).apply { isDaemon = true }
                }

            executor?.submit(::produce)
        }

        fun pause() {
            if (cancelled.get()) return
            audioTrack?.pause()
            state = State.PAUSED
            if (isCurrent(id)) {
                listener.onState(
                    State.PAUSED,
                    "Live karaoke paused",
                )
            }
        }

        fun resume() {
            if (cancelled.get()) return
            state =
                if (consumerStarted) {
                    State.PLAYING
                } else {
                    State.BUFFERING
                }

            if (consumerStarted) {
                audioTrack?.play()
            }

            if (isCurrent(id)) {
                listener.onState(
                    state,
                    if (consumerStarted) {
                        "Live karaoke playing — processing ahead"
                    } else {
                        "Buffering the first MDX window…"
                    },
                )
            }
        }

        fun stop() {
            if (!cancelled.compareAndSet(false, true)) return

            queue.cancel()
            runCatching { audioTrack?.pause() }
            runCatching { audioTrack?.stop() }
            executor?.shutdownNow()

            // The inference thread owns the pipeline lifetime and closes it
            // from its finally block after the current model call unwinds.
        }

        private fun produce() {
            // Inference is the real-time-critical producer. Give its Java/ONNX
            // execution thread a favorable priority so short scheduler/GC
            // interruptions are less likely to drain the live audio headroom.
            runCatching {
                Process.setThreadPriority(
                    Process.THREAD_PRIORITY_MORE_FAVORABLE,
                )
            }

            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var streaming: StreamingVocalRemover? = null

            try {
                if (!isCurrent(id)) return

                extractor = MediaExtractor()
                extractor.setDataSource(appContext, uri, null)

                val trackIndex = findAudioTrack(extractor)
                val inputFormat = extractor.getTrackFormat(trackIndex)
                extractor.selectTrack(trackIndex)

                sourceSampleRate =
                    inputFormat.getIntegerSafely(
                        MediaFormat.KEY_SAMPLE_RATE
                    ) ?: error("Source sample rate is unavailable")

                val channels =
                    inputFormat.getIntegerSafely(
                        MediaFormat.KEY_CHANNEL_COUNT
                    ) ?: error("Source channel count is unavailable")
                require(channels > 0) {
                    "Invalid source channel count: $channels"
                }

                durationMs =
                    (
                        inputFormat.getLongSafely(
                            MediaFormat.KEY_DURATION
                        ) ?: 0L
                    )
                        .coerceAtLeast(0L) / 1000L

                playbackStartMs =
                    requestedPositionMs.coerceIn(
                        0L,
                        durationMs.takeIf { it > 0L }
                            ?: requestedPositionMs,
                    )

                val modelWindowUs =
                    (
                        MdxStft(modelSpec)
                            .chunkSizeSamples()
                            .toDouble() /
                            MdxStft.SAMPLE_RATE.toDouble() *
                            1_000_000.0
                    )
                        .roundToLong()
                        .coerceAtLeast(1L)

                val requestedDecodeStartUs =
                    max(
                        0L,
                        playbackStartMs * 1_000L -
                            modelWindowUs -
                            SEEK_CONTEXT_MARGIN_MS * 1_000L,
                    )

                extractor.seekTo(
                    requestedDecodeStartUs,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                )

                actualDecodeStartMs =
                    (
                        extractor.sampleTime
                            .coerceAtLeast(0L) / 1000L
                    )

                val actualDecodeStartUs =
                    extractor.sampleTime.coerceAtLeast(0L)

                droppedPrerollFrames =
                    calculateLiveKaraokePrerollFrames(
                        playbackStartMs = playbackStartMs,
                        actualDecodeStartUs = actualDecodeStartUs,
                        sampleRate = sourceSampleRate,
                    )

                state = State.BUFFERING
                if (isCurrent(id)) {
                    listener.onState(
                        State.BUFFERING,
                        "Loading bundled MDX-Net 9482…",
                    )
                }

                val localPipeline =
                    VocalRemoverPipeline(
                        appContext,
                        sourceSampleRate,
                        modelSpec,
                    )
                pipeline = localPipeline

                localPipeline.setDepth(settings.depth)
                localPipeline.setFocus(settings.focus)
                localPipeline.setTransientProtection(
                    settings.transientProtection
                )
                localPipeline.setDryWet(settings.dryWet)
                localPipeline.setStemGainDb(settings.stemGainDb)
                localPipeline.setOutputGainDb(settings.outputGainDb)
                localPipeline.setCeilingDb(settings.ceilingDb)

                localPipeline.loadModel()

                if (!isCurrent(id)) return

                streaming =
                    localPipeline.startStreaming { block ->
                        enqueueInstrumental(block)
                    }

                audioTrack =
                    createAudioTrack(sourceSampleRate)

                executor?.submit(::consume)

                if (isCurrent(id)) {
                    listener.onState(
                        State.BUFFERING,
                        "Processing the first MDX window before playback…",
                    )
                }

                val mime =
                    inputFormat.getString(
                        MediaFormat.KEY_MIME
                    ) ?: error("Audio MIME type is missing")

                require(mime.startsWith("audio/")) {
                    "Selected track is not audio: $mime"
                }

                decoder =
                    MediaCodec.createDecoderByType(mime)
                decoder.configure(
                    inputFormat,
                    null,
                    null,
                    0,
                )
                decoder.start()

                decodeLoop(
                    extractor = extractor,
                    decoder = decoder,
                    inputFormat = inputFormat,
                    separator = streaming,
                )

                if (!cancelled.get()) {
                    streaming.finish()
                    producerFinished = true
                    queue.finish()
                }
            } catch (throwable: Throwable) {
                fail(throwable)
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor?.release() }
                runCatching { streaming?.close() }
                runCatching { pipeline?.close() }
                pipeline = null
                // AudioTrack belongs to the consumer once it has been created.
                // The consumer releases it after the final queued PCM block is
                // written. Releasing it here would truncate buffered audio.
                if (!consumerStarted && cancelled.get()) {
                    releaseAudioTrack()
                }
            }
        }

        private fun consume() {
            // Keep the audio-feed thread schedulable while ONNX inference is
            // using multiple CPU workers. This reduces device-side AudioTrack
            // starvation without changing the neural workload itself.
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            }

            try {
                while (!cancelled.get()) {
                    if (state == State.PAUSED) {
                        Thread.sleep(40L)
                        continue
                    }

                    if (!consumerStarted) {
                        val buffered =
                            queue.availableFrames()
                        val required =
                            initialBufferFrames()
                        val ready =
                            if (producerFinished) {
                                buffered > 0
                            } else {
                                startupTargetFinalized &&
                                    buffered >= required
                            }

                        if (!ready) {
                            val nowNs = System.nanoTime()
                            val shouldUpdateUi =
                                lastUiUpdateNs == 0L ||
                                    nowNs - lastUiUpdateNs >=
                                    UI_UPDATE_INTERVAL_MS * 1_000_000L

                            if (
                                shouldUpdateUi &&
                                isCurrent(id)
                            ) {
                                lastUiUpdateNs = nowNs
                                listener.onState(
                                    State.BUFFERING,
                                    if (startupTargetFinalized) {
                                        "Building a safe instrumental buffer — " +
                                            bufferedSeconds(buffered) +
                                            " / " +
                                            bufferedSeconds(required) +
                                            " s"
                                    } else {
                                        "Calibrating MDX throughput — " +
                                            bufferedSeconds(buffered) +
                                            " / " +
                                            bufferedSeconds(required) +
                                            " s minimum"
                                    },
                                )
                                listener.onProgress(
                                    playbackPositionMs(),
                                    durationMs,
                                )
                            }
                            Thread.sleep(30L)
                            continue
                        }

                        audioTrack?.play()
                        consumerStarted = true
                        state = State.PLAYING
                        if (isCurrent(id)) {
                            listener.onState(
                                State.PLAYING,
                                "Live karaoke playing — MDX-Net is processing ahead",
                            )
                        }
                    }

                    val block = queue.take() ?: break
                    var offset = 0

                    while (
                        offset < block.size &&
                        !cancelled.get()
                    ) {
                        val written =
                            audioTrack?.write(
                                block,
                                offset,
                                block.size - offset,
                                AudioTrack.WRITE_BLOCKING,
                            ) ?: 0

                        if (written <= 0) {
                            error(
                                "AudioTrack write failed: $written"
                            )
                        }

                        offset += written
                        submittedFrames +=
                            (written / 2).toLong()

                        val nowNs = System.nanoTime()
                        if (
                            isCurrent(id) &&
                            (
                                lastUiUpdateNs == 0L ||
                                    nowNs - lastUiUpdateNs >=
                                    UI_UPDATE_INTERVAL_MS * 1_000_000L
                            )
                        ) {
                            lastUiUpdateNs = nowNs
                            listener.onProgress(
                                playbackPositionMs(),
                                durationMs,
                            )
                        }
                    }
                }

                if (
                    !cancelled.get() &&
                    isCurrent(id)
                ) {
                    waitForAudioTrackDrain()

                    runCatching { audioTrack?.stop() }
                    runCatching { audioTrack?.release() }
                    audioTrack = null

                    state = State.STOPPED
                    listener.onState(
                        State.STOPPED,
                        "Instrumental playback complete",
                    )
                    listener.onCompleted()
                }
            } catch (throwable: Throwable) {
                fail(throwable)
            } finally {
                releaseAudioTrack()
            }
        }

        private fun fail(throwable: Throwable) {
            if (!cancelled.compareAndSet(false, true)) return
            producerFinished = true
            queue.cancel()
            executor?.shutdownNow()
            if (!consumerStarted) {
                releaseAudioTrack()
            }
            if (isCurrent(id)) {
                listener.onError(throwable)
            }
        }

        private fun releaseAudioTrack() {
            val track =
                synchronized(this) {
                    val current = audioTrack ?: return
                    audioTrack = null
                    current
                }

            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }

        private fun playbackPositionMs(): Long {
            val track =
                audioTrack

            val played =
                if (track != null && consumerStarted) {
                    audioTrackPlaybackHeadFrames(
                        runCatching {
                            track.playbackHeadPosition
                        }.getOrDefault(0),
                    )
                } else {
                    0L
                }

            return liveKaraokePositionMs(
                playbackStartMs = playbackStartMs,
                playbackFrames = played,
                sampleRate = sourceSampleRate,
                durationMs = durationMs,
            )
        }

        private fun waitForAudioTrackDrain() {
            val track =
                audioTrack ?: return

            val deadlineNs =
                System.nanoTime() +
                    MAX_AUDIO_DRAIN_WAIT_MS * 1_000_000L

            while (
                !cancelled.get() &&
                isCurrent(id) &&
                System.nanoTime() < deadlineNs
            ) {
                val head =
                    audioTrackPlaybackHeadFrames(
                        runCatching {
                            track.playbackHeadPosition
                        }.getOrDefault(0),
                    )

                if (head >= submittedFrames) {
                    return
                }

                val nowNs = System.nanoTime()
                if (
                    nowNs - lastUiUpdateNs >=
                    UI_UPDATE_INTERVAL_MS * 1_000_000L
                ) {
                    lastUiUpdateNs = nowNs
                    listener.onProgress(
                        liveKaraokePositionMs(
                            playbackStartMs = playbackStartMs,
                            playbackFrames = head,
                            sampleRate = sourceSampleRate,
                            durationMs = durationMs,
                        ),
                        durationMs,
                    )
                }

                Thread.sleep(20L)
            }
        }

        private fun initialBufferFrames(): Int {
            val generatedPerWindow =
                (
                    MdxStft(modelSpec)
                        .generatedSamplesPerChunk()
                        .toDouble() *
                        sourceSampleRate.toDouble() /
                        MdxStft.SAMPLE_RATE.toDouble()
                )
                    .roundToInt()
                    .coerceAtLeast(1)

            if (startupTargetFinalized) {
                return startupTargetFrames
            }

            val measuredRate =
                estimatedProducerRate
                    .takeIf { it.isFinite() && it > 0.0 }

            if (measuredRate == null) {
                return calculateLiveKaraokeStartupBufferFrames(
                    sourceSampleRate = sourceSampleRate,
                    generatedPerWindow = generatedPerWindow,
                    startupBufferSeconds = STARTUP_BUFFER_SECONDS,
                    startupBufferWindows = STARTUP_BUFFER_WINDOWS,
                    maxLookaheadFrames = MAX_LOOKAHEAD_FRAMES,
                )
            }

            val remainingSeconds =
                (
                    durationMs
                        .coerceAtLeast(0L)
                        .minus(playbackStartMs.coerceAtLeast(0L))
                )
                    .toDouble() /
                    1000.0

            startupTargetFrames =
                calculateLiveKaraokeSafeBufferFrames(
                    sourceSampleRate = sourceSampleRate,
                    generatedPerWindow = generatedPerWindow,
                    startupBufferSeconds = STARTUP_BUFFER_SECONDS,
                    startupBufferWindows = STARTUP_BUFFER_WINDOWS,
                    remainingSeconds = remainingSeconds,
                    measuredProducerRate = measuredRate,
                    safetyMarginSeconds = LIVE_SAFETY_MARGIN_SECONDS,
                    maxLookaheadFrames = MAX_LOOKAHEAD_FRAMES,
                )
            startupTargetFinalized = true
            return startupTargetFrames
        }

        private fun noteProducerThroughput(
            emittedFrames: Int,
        ) {
            val now = System.nanoTime()

            if (producerRateStartNs == 0L) {
                producerRateStartNs = now
            }

            producerRateFrames += emittedFrames.toLong()

            if (estimatedProducerRate.isFinite()) {
                return
            }

            val minimumFrames =
                (
                    sourceSampleRate.toDouble() *
                        RATE_ESTIMATION_MIN_SECONDS
                )
                    .roundToInt()
                    .toLong()
                    .coerceAtLeast(1L)

            if (producerRateFrames < minimumFrames) {
                return
            }

            val elapsedSeconds =
                (
                    now - producerRateStartNs
                )
                    .coerceAtLeast(1L)
                    .toDouble() /
                    1_000_000_000.0

            if (elapsedSeconds <= 0.0) return

            estimatedProducerRate =
                (
                    producerRateFrames.toDouble() /
                        sourceSampleRate.toDouble()
                ) /
                    elapsedSeconds

            /*
             * The target is intentionally frozen by initialBufferFrames() the
             * next time the consumer checks it. Never mutate the target after
             * playback has started.
             */
        }

        private fun bufferedSeconds(frames: Int): String {
            val seconds =
                frames.toDouble() /
                    sourceSampleRate.toDouble()
            return String.format(
                java.util.Locale.US,
                "%.1f",
                seconds,
            )
        }

        private fun enqueueInstrumental(
            block: VocalSeparatorCore.Stereo,
        ) {
            if (
                cancelled.get() ||
                !isCurrent(id)
            ) return

            var start = 0
            var count = block.size

            if (droppedPrerollFrames > 0) {
                val drop =
                    min(
                        droppedPrerollFrames,
                        count,
                    )
                droppedPrerollFrames -= drop
                start += drop
                count -= drop
            }

            if (count <= 0) return

            noteProducerThroughput(count)

            val pcm = ShortArray(count * 2)
            var output = 0

            for (index in start until start + count) {
                pcm[output++] =
                    floatToPcm16(block.left[index])
                pcm[output++] =
                    floatToPcm16(block.right[index])
            }

            queue.put(pcm)
        }

        private fun decodeLoop(
            extractor: MediaExtractor,
            decoder: MediaCodec,
            inputFormat: MediaFormat,
            separator: StreamingVocalRemover,
        ) {
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false

            var encoding =
                inputFormat.getIntegerSafely(
                    MediaFormat.KEY_PCM_ENCODING
                ) ?: AudioFormat.ENCODING_PCM_16BIT

            var decoderChannels =
                inputFormat.getIntegerSafely(
                    MediaFormat.KEY_CHANNEL_COUNT
                ) ?: 2

            require(decoderChannels in 1..2) {
                "Live Karaoke currently supports mono or stereo sources; decoder reported $decoderChannels channels"
            }

            var decoderSampleRate =
                inputFormat.getIntegerSafely(
                    MediaFormat.KEY_SAMPLE_RATE
                ) ?: sourceSampleRate

            while (
                !outputEnded &&
                !cancelled.get()
            ) {
                if (!inputEnded) {
                    val inputIndex =
                        decoder.dequeueInputBuffer(
                            10_000
                        )

                    if (inputIndex >= 0) {
                        val inputBuffer =
                            decoder.getInputBuffer(
                                inputIndex
                            ) ?: error(
                                "Decoder input buffer unavailable"
                            )
                        inputBuffer.clear()

                        val sampleSize =
                            extractor.readSampleData(
                                inputBuffer,
                                0,
                            )

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (
                    val outputIndex =
                        decoder.dequeueOutputBuffer(
                            bufferInfo,
                            10_000,
                        )
                ) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat =
                            decoder.outputFormat

                        decoderSampleRate =
                            outputFormat.getIntegerSafely(
                                MediaFormat.KEY_SAMPLE_RATE
                            ) ?: decoderSampleRate

                        decoderChannels =
                            outputFormat.getIntegerSafely(
                                MediaFormat.KEY_CHANNEL_COUNT
                            ) ?: decoderChannels

                        require(decoderChannels in 1..2) {
                            "Live Karaoke currently supports mono or stereo sources; decoder reported $decoderChannels channels"
                        }

                        encoding =
                            outputFormat.getIntegerSafely(
                                MediaFormat.KEY_PCM_ENCODING
                            ) ?: encoding

                        require(
                            decoderSampleRate ==
                                sourceSampleRate
                        ) {
                            "Decoder sample rate changed during live playback"
                        }
                    }

                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer =
                                decoder.getOutputBuffer(
                                    outputIndex
                                )

                            val isConfig =
                                (
                                    bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                    ) != 0

                            if (
                                outputBuffer != null &&
                                bufferInfo.size > 0 &&
                                !isConfig
                            ) {
                                val duplicate =
                                    outputBuffer
                                        .duplicate()
                                        .order(
                                            ByteOrder.LITTLE_ENDIAN
                                        )

                                duplicate.position(
                                    bufferInfo.offset
                                )
                                duplicate.limit(
                                    bufferInfo.offset +
                                        bufferInfo.size
                                )

                                feedDecodedPcm(
                                    buffer = duplicate,
                                    encoding = encoding,
                                    channels = decoderChannels,
                                    separator = separator,
                                )
                            }

                            val eos =
                                (
                                    bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    ) != 0

                            decoder.releaseOutputBuffer(
                                outputIndex,
                                false,
                            )

                            if (eos) {
                                outputEnded = true
                            }
                        }
                    }
                }
            }
        }

        private fun feedDecodedPcm(
            buffer: ByteBuffer,
            encoding: Int,
            channels: Int,
            separator: StreamingVocalRemover,
        ) {
            require(channels > 0)

            val bytesPerSample =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> 2
                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    AudioFormat.ENCODING_PCM_32BIT -> 4
                    else ->
                        error(
                            "Unsupported decoder PCM encoding: $encoding"
                        )
                }

            val frameBytes =
                bytesPerSample.toLong() *
                    channels.toLong()

            check(frameBytes <= Int.MAX_VALUE)

            val available = buffer.remaining()
            check(
                available.toLong() %
                    frameBytes == 0L
            ) {
                "Decoder PCM is not aligned to complete frames"
            }

            var remaining =
                (
                    available.toLong() /
                        frameBytes
                    ).toInt()

            while (
                remaining > 0 &&
                !cancelled.get()
            ) {
                val count = min(8192, remaining)
                val left = FloatArray(count)
                val right = FloatArray(count)

                for (frame in 0 until count) {
                    if (channels == 1) {
                        val sample =
                            readSample(
                                buffer,
                                encoding,
                            )
                        left[frame] = sample
                        right[frame] = sample
                    } else {
                        left[frame] =
                            readSample(
                                buffer,
                                encoding,
                            )
                        right[frame] =
                            readSample(
                                buffer,
                                encoding,
                            )

                        for (
                            channel in
                            2 until channels
                        ) {
                            readSample(
                                buffer,
                                encoding,
                            )
                        }
                    }
                }

                separator.push(
                    VocalSeparatorCore.Stereo(
                        left,
                        right,
                    )
                )

                remaining -= count
            }
        }

        private fun readSample(
            buffer: ByteBuffer,
            encoding: Int,
        ): Float {
            val value =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT ->
                        buffer.short.toInt() /
                            32768f

                    AudioFormat.ENCODING_PCM_FLOAT ->
                        buffer.float

                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val b0 =
                            buffer.get().toInt() and
                                0xFF
                        val b1 =
                            buffer.get().toInt() and
                                0xFF
                        val b2 =
                            buffer.get().toInt()

                        val packed =
                            b0 or
                                (b1 shl 8) or
                                (b2 shl 16)

                        val signed =
                            if (
                                packed and
                                    0x800000 != 0
                            ) {
                                packed or
                                    -0x1000000
                            } else {
                                packed
                            }

                        signed /
                            8_388_608f
                    }

                    AudioFormat.ENCODING_PCM_32BIT ->
                        buffer.int /
                            2_147_483_648f

                    else ->
                        error(
                            "Unsupported decoder PCM encoding: $encoding"
                        )
                }

            return if (value.isFinite()) {
                value.coerceIn(-1f, 1f)
            } else {
                0f
            }
        }

        private fun createAudioTrack(
            sampleRate: Int,
        ): AudioTrack {
            val minBytes =
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )

            check(minBytes > 0) {
                "AudioTrack minimum buffer size failed: $minBytes"
            }

            val safetyBytes =
                (
                    sampleRate.toLong() *
                        AUDIO_TRACK_BUFFER_SECONDS.toLong() *
                        4L
                )
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()

            val bufferBytes =
                max(
                    minBytes * 8,
                    safetyBytes,
                )

            return AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(
                            android.media.AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(
                            android.media.AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .build()
                )
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .setBufferSizeInBytes(
                    bufferBytes
                )
                .build()
                .also {
                    check(
                        it.state ==
                            AudioTrack.STATE_INITIALIZED
                    ) {
                        "AudioTrack failed to initialize"
                    }
                }
        }

        private fun findAudioTrack(
            extractor: MediaExtractor,
        ): Int {
            for (index in 0 until extractor.trackCount) {
                val format =
                    extractor.getTrackFormat(index)
                val mime =
                    format.getString(
                        MediaFormat.KEY_MIME
                    ) ?: continue

                if (mime.startsWith("audio/")) {
                    return index
                }
            }
            error("No audio track found for " + uri)
        }

        private fun floatToPcm16(
            value: Float,
        ): Short {
            val safe =
                if (value.isFinite()) {
                    value.coerceIn(-1f, 1f)
                } else {
                    0f
                }

            val scaled =
                if (safe < 0f) {
                    safe * 32768f
                } else {
                    safe * 32767f
                }

            return scaled
                .roundToInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }

        private fun MediaFormat.getIntegerSafely(
            key: String,
        ): Int? =
            runCatching {
                getInteger(key)
            }.getOrNull()

        private fun MediaFormat.getLongSafely(
            key: String,
        ): Long? =
            runCatching {
                getLong(key)
            }.getOrNull()

        private var actualDecodeStartMs = 0L

    }
}
