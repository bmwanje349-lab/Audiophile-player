package com.example.musicplayer.livekaraoke

import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.bmwanje.audiophile.vocalremover.LiveMdxOnnxVocalModelRunner
import com.bmwanje.audiophile.vocalremover.MdxModelManager
import com.bmwanje.audiophile.vocalremover.MdxModelSpec
import com.bmwanje.audiophile.vocalremover.MdxStft
import com.bmwanje.audiophile.vocalremover.NativeVocalRemover
import com.bmwanje.audiophile.vocalremover.LiveStreamingVocalRemover
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
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

internal object LiveKaraokePerformancePolicy {
    /**
     * Keep the audio look-ahead bounded to a few tens of seconds. The old
     * 120-second cap could reserve ~20+ MiB of PCM16 stereo by itself, while
     * neural/STFT buffers already consume tens of MiB.
     */
    fun maxLookaheadSeconds(memoryClassMb: Int): Int =
        when {
            memoryClassMb <= 192 -> 20
            memoryClassMb <= 256 -> 24
            memoryClassMb <= 384 -> 32
            else -> 48
        }

    const val MIX_QUEUE_MAX_SAMPLES = 393_216
    const val AUDIO_TRACK_BUFFER_SECONDS = 2
    const val STARTUP_BUFFER_SECONDS = 6
    const val STARTUP_BUFFER_WINDOWS = 1
    const val MIN_NEURAL_SUSTAINED_RATE = 0.95
    const val MAX_NEURAL_STARTUP_SECONDS = 18.0
}

internal fun maxLiveKaraokeLookaheadFrames(
    sourceSampleRate: Int,
    maxLookaheadSeconds: Int = 32,
): Int {
    require(sourceSampleRate > 0)
    require(maxLookaheadSeconds > 0)

    val frames =
        sourceSampleRate.toLong() *
            maxLookaheadSeconds.toLong()

    require(frames <= Int.MAX_VALUE.toLong()) {
        "Live Karaoke look-ahead is too large for Int frame counts"
    }
    return frames.toInt()
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
    val requiredFrames =
        calculateLiveKaraokeRequiredBufferFrames(
            sourceSampleRate = sourceSampleRate,
            generatedPerWindow = generatedPerWindow,
            startupBufferSeconds = startupBufferSeconds,
            startupBufferWindows = startupBufferWindows,
            remainingSeconds = remainingSeconds,
            measuredProducerRate = measuredProducerRate,
            safetyMarginSeconds = safetyMarginSeconds,
        )

    if (requiredFrames > maxLookaheadFrames.toLong()) {
        throw NeuralLiveNotViableException(
            "Neural throughput requires " +
                requiredFrames +
                " frames, but the bounded Live Karaoke queue can hold only " +
                maxLookaheadFrames +
                " frames.",
        )
    }

    return requiredFrames.toInt()
}

internal fun calculateLiveKaraokeRequiredBufferFrames(
    sourceSampleRate: Int,
    generatedPerWindow: Int,
    startupBufferSeconds: Int,
    startupBufferWindows: Int,
    remainingSeconds: Double,
    measuredProducerRate: Double,
    safetyMarginSeconds: Double,
): Long {
    require(sourceSampleRate > 0)
    require(generatedPerWindow > 0)
    require(startupBufferSeconds > 0)
    require(startupBufferWindows > 0)
    require(remainingSeconds >= 0.0)
    require(measuredProducerRate.isFinite() && measuredProducerRate > 0.0)
    require(safetyMarginSeconds >= 0.0)

    val fixedMinimumFrames =
        calculateLiveKaraokeStartupBufferFrames(
            sourceSampleRate = sourceSampleRate,
            generatedPerWindow = generatedPerWindow,
            startupBufferSeconds = startupBufferSeconds,
            startupBufferWindows = startupBufferWindows,
            maxLookaheadFrames = Int.MAX_VALUE,
        )
            .toLong()

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

    /*
     * A seamless-playback guarantee is an inequality. Never round the
     * required sample count down; use ceiling so the buffered audio is at
     * least the mathematically required duration.
     */
    val timeBased =
        ceil(
            sourceSampleRate.toDouble() *
                requiredSeconds
        )
            .toLong()
            .coerceAtLeast(1L)

    return max(
        fixedMinimumFrames,
        timeBased,
    )
}

internal class NeuralLiveNotViableException(
    message: String,
) : IllegalStateException(message)

internal fun recommendedLiveKaraokeCpuThreads(
    availableProcessors: Int,
): Int {
    require(availableProcessors > 0)
    /*
     * Reserve roughly half of the logical CPUs for decoding, AudioTrack,
     * Android housekeeping, and ORT/OS overhead while still scaling XNNPACK
     * beyond the old fixed two-thread setting on larger phones.
     */
    return ((availableProcessors + 1) / 2)
        .coerceIn(1, 4)
}

internal fun isNeuralLiveStartupViable(
    measuredProducerRate: Double,
    requiredFrames: Long,
    sourceSampleRate: Int,
    minimumProducerRate: Double = 0.95,
    maximumStartupSeconds: Double = 18.0,
): Boolean {
    require(measuredProducerRate.isFinite() && measuredProducerRate > 0.0)
    require(requiredFrames >= 0L)
    require(sourceSampleRate > 0)
    require(minimumProducerRate > 0.0)
    require(maximumStartupSeconds > 0.0)

    val startupSeconds =
        requiredFrames.toDouble() /
            sourceSampleRate.toDouble()

    return measuredProducerRate >= minimumProducerRate &&
        startupSeconds <= maximumStartupSeconds
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

    val numerator =
        prerollUs * sampleRate.toLong()

    /*
     * Seeking must never resume before the requested source position.
     * Round upward to the next complete source frame rather than to nearest.
     */
    val frames =
        if (numerator == 0L) {
            0L
        } else {
            (numerator + 999_999L) / 1_000_000L
        }

    return frames
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

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
            .roundToLong()

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
private data class LiveDecodedBlock(
    val audio: VocalSeparatorCore.Stereo?,
    val end: Boolean = false,
)

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
        const val STARTUP_BUFFER_SECONDS = 6
        const val STARTUP_BUFFER_WINDOWS = 1
        const val RATE_ESTIMATION_MIN_SECONDS = 5.5
        const val LIVE_SAFETY_MARGIN_SECONDS = 6.0
        const val AUDIO_TRACK_BUFFER_SECONDS = 2
        const val NEURAL_CALIBRATION_TIMEOUT_MS = 12_000L
        const val MIN_NEURAL_SUSTAINED_RATE = 0.95
        const val MAX_NEURAL_STARTUP_SECONDS = 18.0
        const val DSP_STARTUP_BUFFER_SECONDS = 1
        const val MAX_AUDIO_DRAIN_WAIT_MS = 15_000L
        const val UI_UPDATE_INTERVAL_MS = 250L
        const val SEEK_CONTEXT_MARGIN_MS = 100L
        const val THERMAL_POLL_INTERVAL_MS = 1_000L
        const val SUSTAINED_RATE_FALLBACK = 0.90
        const val MIN_BUFFER_BEFORE_RATE_FALLBACK_SECONDS = 5.0
        const val PROFILE_TAG = "LiveKaraoke"
    }

    private val lock = Any()
    private var session: Session? = null
    private var generation = 0L
    private var closed = false
    private var cachedLiveRunner: LiveMdxOnnxVocalModelRunner? = null

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
                    forceDspFallback = false,
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
                    forceDspFallback = current.forceDspFallback,
                )
            session = next
            listener.onState(
                State.SEEKING,
                "Seeking — reusing the loaded MDX model…",
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
        val sessionToAwait =
            synchronized(lock) {
                if (closed) {
                    null
                } else {
                    closed = true
                    val current = session
                    stopLocked()
                    current
                }
            }

        closeCachedLiveRunnerAsync(sessionToAwait)
    }

    private fun stopLocked() {
        session?.stop()
        session = null
        /*
         * Keep the verified ONNX session warm across stop/start and seek
         * transitions. Releasing it here can synchronously wait for an
         * in-flight inference and would make lifecycle calls block.
         * The final close() above releases it asynchronously.
         */
        listener.onState(State.STOPPED, "Live karaoke stopped")
    }

    private fun obtainCachedLiveRunner(modelPath: String, profiler: LiveKaraokeStageProfiler): LiveMdxOnnxVocalModelRunner {
        synchronized(lock) {
            val existing = cachedLiveRunner
            if (existing != null) {
                existing.setProfiler(profiler)
                return existing
            }

            val created =
                LiveMdxOnnxVocalModelRunner(
                    modelPath = modelPath,
                    modelSpec = modelSpec,
                    cpuThreads =
                        recommendedLiveKaraokeCpuThreads(
                            Runtime.getRuntime().availableProcessors(),
                        ),
                    profiler = profiler,
                )
            cachedLiveRunner = created
            return created
        }
    }

    private fun closeCachedLiveRunnerAsync(sessionToAwait: Session?) {
        val runner =
            synchronized(lock) {
                val current = cachedLiveRunner
                cachedLiveRunner = null
                current
            } ?: return

        Thread(
            {
                /*
                 * A previous session may still be unwinding a synchronized
                 * ONNX call. Wait off the caller thread. The synchronized
                 * runner.close() remains the final safety barrier.
                 */
                sessionToAwait?.awaitTermination(30_000L)
                runCatching { runner.clearProfiler() }
                runCatching { runner.close() }
            },
            "Audiophile-LiveKaraoke-RunnerCleanup",
        ).apply {
            isDaemon = true
            start()
        }
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
        val forceDspFallback: Boolean,
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
        private var rollingProducerRate = Double.NaN
        private var thermalStatus: Int? = null
        private var lastHealthPollNs = 0L
        private var lastRateSampleNs = 0L
        private var lastRateSampleFrames = 0L

        private var producerRateStartNs = 0L
        private var producerRateFrames = 0L
        private var startupTargetFrames = 0
        private var startupTargetFinalized = false
        private val profiler = LiveKaraokeStageProfiler()

        private val cancelled = AtomicBoolean(false)
        private var queue: LivePcmQueue? = null

        private var executor: ExecutorService? = null
        private var inferenceFuture: Future<*>? = null
        private var audioTrack: AudioTrack? = null
        private var fastDsp: LiveDspFallbackProcessor? = null
        @Volatile
        private var usingFastFallback = false
        private val fallbackRequested = AtomicBoolean(false)
        private var consumerStarted = false
        private var droppedPrerollFrames = 0

        private val decodedBlocks =
            ArrayBlockingQueue<LiveDecodedBlock>(4)

        private fun liveLookaheadSeconds(): Int {
            val manager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return LiveKaraokePerformancePolicy.maxLookaheadSeconds(
                manager?.memoryClass ?: 256,
            )
        }

        fun start() {
            executor =
                Executors.newFixedThreadPool(
                    if (forceDspFallback) 2 else 3,
                ) { runnable ->
                    Thread(
                        runnable,
                        "Audiophile-LiveKaraoke-$id",
                    ).apply { isDaemon = true }
                }

            executor?.submit(
                if (forceDspFallback) {
                    ::produceDspFallback
                } else {
                    ::produce
                }
            )
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

            queue?.cancel()
            decodedBlocks.clear()
            runCatching { audioTrack?.pause() }
            runCatching { audioTrack?.stop() }
            executor?.shutdownNow()

            /*
             * Do not await here. stop()/seek are lifecycle operations and must
             * return promptly. The final engine close performs the worker wait
             * on a daemon cleanup thread before releasing ORT.
             */
        }

        fun awaitTermination(timeoutMs: Long): Boolean {
            val currentExecutor = executor ?: return true
            return runCatching {
                currentExecutor.awaitTermination(
                    timeoutMs,
                    TimeUnit.MILLISECONDS,
                )
            }.getOrDefault(true)
        }

        private fun produce() {
            // Neural mode overlaps codec/PCM work with model execution so the
            // decoder is not sitting idle while ONNX is computing.
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
            var streaming: LiveStreamingVocalRemover? = null

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

                queue =
                    LivePcmQueue(
                        maxFrames =
                            maxLiveKaraokeLookaheadFrames(
                                sourceSampleRate,
                                liveLookaheadSeconds(),
                            ),
                    )

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

                val modelFile =
                    MdxModelManager.ensureInstalled(
                        appContext,
                        modelSpec,
                    )

                val native =
                    NativeVocalRemover(sourceSampleRate).apply {
                        reset()
                        setDepth(settings.depth)
                        setFocus(settings.focus)
                        setTransientProtection(settings.transientProtection)
                        setDryWet(settings.dryWet)
                        setStemGainDb(settings.stemGainDb)
                        setOutputGainDb(settings.outputGainDb)
                        setCeilingDb(settings.ceilingDb)
                    }

                val liveRunner =
                    obtainCachedLiveRunner(
                        modelPath = modelFile.absolutePath,
                        profiler = profiler,
                    )
                liveRunner.setProfiler(profiler)

                if (!isCurrent(id)) {
                    runCatching { native.close() }
                    return
                }

                streaming =
                    LiveStreamingVocalRemover(
                        sampleRate = sourceSampleRate,
                        runner = liveRunner,
                        modelSpec = modelSpec,
                        native = native,
                        emit = { block -> enqueueInstrumental(block) },
                        profiler = profiler,
                    )

                audioTrack =
                    createAudioTrack(sourceSampleRate)

                executor?.submit(::consume)
                inferenceFuture =
                    executor?.submit {
                        inferNeuralBlocks(streaming)
                    }

                if (isCurrent(id)) {
                    listener.onState(
                        State.BUFFERING,
                        "Preparing Live Karaoke — measuring sustained MDX throughput…",
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
                ) { block ->
                    decodedBlocks.put(
                        LiveDecodedBlock(block)
                    )
                }

                if (!cancelled.get()) {
                    decodedBlocks.put(
                        LiveDecodedBlock(
                            audio = null,
                            end = true,
                        )
                    )
                    inferenceFuture?.get()
                    producerFinished = true
                    queue?.finish()
                }
            } catch (throwable: Throwable) {
                fail(throwable)
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor?.release() }
                runCatching { streaming?.close() }
                runCatching { fastDsp?.close() }
                Log.i(
                    PROFILE_TAG,
                    profiler.report(sourceSampleRate),
                )
                // AudioTrack belongs to the consumer once it has been created.
                // The consumer releases it after the final queued PCM block is
                // written. Releasing it here would truncate buffered audio.
                if (!consumerStarted && cancelled.get()) {
                    releaseAudioTrack()
                }
            }
        }

        private fun inferNeuralBlocks(
            separator: LiveStreamingVocalRemover,
        ) {
            runCatching {
                Process.setThreadPriority(
                    Process.THREAD_PRIORITY_MORE_FAVORABLE,
                )
            }

            var startedClock = false
            var switchedToFallback = false

            while (!cancelled.get()) {
                val item = decodedBlocks.take()
                if (item.end) break

                val block =
                    item.audio
                        ?: error("Decoded block was unexpectedly empty")

                if (!startedClock) {
                    producerRateStartNs = System.nanoTime()
                    startedClock = true
                }

                if (
                    !switchedToFallback &&
                    fallbackRequested.get()
                ) {
                    activateFastFallback(separator)
                    switchedToFallback = true
                }

                if (switchedToFallback) {
                    fastDsp?.push(block)
                } else {
                    separator.push(block)
                }
            }

            if (cancelled.get()) return

            if (switchedToFallback || fallbackRequested.get()) {
                if (!switchedToFallback) {
                    activateFastFallback(separator)
                }
                fastDsp?.finish()
                return
            }

            separator.finish()
        }

        private fun activateFastFallback(
            separator: LiveStreamingVocalRemover,
        ) {
            if (usingFastFallback) return

            val fallback =
                LiveDspFallbackProcessor(
                    sampleRate = sourceSampleRate,
                    settings =
                        LiveKaraokeSettingsSnapshot(
                            depth = settings.depth,
                            focus = settings.focus,
                            transientProtection =
                                settings.transientProtection,
                            dryWet = settings.dryWet,
                            stemGainDb = settings.stemGainDb,
                            outputGainDb = settings.outputGainDb,
                            ceilingDb = settings.ceilingDb,
                        ),
                    { block ->
                        enqueueInstrumental(block)
                    },
                    profiler = profiler,
                )

            fastDsp = fallback
            usingFastFallback = true

            /*
             * MDX-Net has already consumed decoded PCM beyond the last vocal
             * stem it has emitted. That pending source audio is retained by
             * the neural separator. Transfer it into the same Fast Live queue
             * before accepting newly decoded blocks. This keeps one continuous
             * source timeline and avoids restarting AudioTrack or the decoder.
             */
            separator.drainPendingMixTo { block ->
                fallback.push(block)
            }
            separator.close()

            if (isCurrent(id)) {
                state =
                    if (consumerStarted) {
                        State.PLAYING
                    } else {
                        State.BUFFERING
                    }
                listener.onState(
                    state,
                    if (consumerStarted) {
                        "Fast Live engaged — continuing without restarting playback."
                    } else {
                        "Fast Live engaged — building a short startup buffer."
                    },
                )
            }
        }

        private fun requestFastFallback(reason: String) {
            if (
                forceDspFallback ||
                usingFastFallback
            ) {
                return
            }

            if (fallbackRequested.compareAndSet(false, true)) {
                Log.w(
                    PROFILE_TAG,
                    reason,
                )
                if (isCurrent(id)) {
                    listener.onState(
                        if (consumerStarted) {
                            State.PLAYING
                        } else {
                            State.BUFFERING
                        },
                        if (consumerStarted) {
                            "Switching to Fast Live — keeping the current playback stream."
                        } else {
                            "Neural Live is too slow on this device — switching to Fast Live."
                        },
                    )
                }
            }
        }

        private fun produceDspFallback() {
            runCatching {
                Process.setThreadPriority(
                    Process.THREAD_PRIORITY_MORE_FAVORABLE,
                )
            }

            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null

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

                queue =
                    LivePcmQueue(
                        maxFrames =
                            maxLiveKaraokeLookaheadFrames(
                                sourceSampleRate,
                                liveLookaheadSeconds(),
                            ),
                    )

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

                extractor.seekTo(
                    max(
                        0L,
                        playbackStartMs * 1_000L -
                            SEEK_CONTEXT_MARGIN_MS * 1_000L,
                    ),
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                )

                droppedPrerollFrames =
                    calculateLiveKaraokePrerollFrames(
                        playbackStartMs = playbackStartMs,
                        actualDecodeStartUs =
                            extractor.sampleTime.coerceAtLeast(0L),
                        sampleRate = sourceSampleRate,
                    )

                fastDsp =
                    LiveDspFallbackProcessor(
                        sampleRate = sourceSampleRate,
                        settings =
                            LiveKaraokeSettingsSnapshot(
                                depth = settings.depth,
                                focus = settings.focus,
                                transientProtection =
                                    settings.transientProtection,
                                dryWet = settings.dryWet,
                                stemGainDb = settings.stemGainDb,
                                outputGainDb = settings.outputGainDb,
                                ceilingDb = settings.ceilingDb,
                            ),
                    { block ->
                        enqueueInstrumental(block)
                    },
                    profiler = profiler,
                )

                startupTargetFrames =
                    (
                        sourceSampleRate.toLong() *
                            DSP_STARTUP_BUFFER_SECONDS.toLong()
                    )
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                startupTargetFinalized = true

                audioTrack =
                    createAudioTrack(sourceSampleRate)
                executor?.submit(::consume)

                if (isCurrent(id)) {
                    listener.onState(
                        State.BUFFERING,
                        "Fast Live mode — avoiding a long neural prebuffer on this device.",
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
                ) { block ->
                    fastDsp?.push(block)
                }

                if (!cancelled.get()) {
                    fastDsp?.finish()
                    producerFinished = true
                    queue?.finish()
                }
            } catch (throwable: Throwable) {
                fail(throwable)
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor?.release() }
                runCatching { fastDsp?.close() }
                fastDsp = null
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

                    pollRuntimeHealth(System.nanoTime())

                    if (!consumerStarted) {
                        val buffered =
                            queue?.availableFrames() ?: 0
                        val required =
                            if (forceDspFallback || fallbackRequested.get()) {
                                dspStartupBufferFrames()
                            } else {
                                try {
                                    initialBufferFrames()
                                } catch (tooSlow: NeuralLiveNotViableException) {
                                    requestFastFallback(
                                        tooSlow.message
                                            ?: "Neural Live Karaoke is too slow on this device.",
                                    )
                                    dspStartupBufferFrames()
                                }
                            }
                        val ready =
                            if (producerFinished) {
                                // Even with nothing buffered (very short or
                                // empty decode) fall through to take(), which
                                // returns null once the queue is finished, so
                                // the session completes instead of hanging in
                                // "Building a safe instrumental buffer".
                                true
                            } else {
                                (
                                    forceDspFallback ||
                                        fallbackRequested.get() ||
                                        startupTargetFinalized
                                ) &&
                                    buffered >= required
                            }

                        if (!ready) {
                            val nowNs = System.nanoTime()
                            val shouldUpdateUi =
                                lastUiUpdateNs == 0L ||
                                    nowNs - lastUiUpdateNs >=
                                    UI_UPDATE_INTERVAL_MS * 1_000_000L

                            if (
                                !forceDspFallback &&
                                !fallbackRequested.get() &&
                                !startupTargetFinalized &&
                                producerRateStartNs > 0L &&
                                nowNs - producerRateStartNs >=
                                    NEURAL_CALIBRATION_TIMEOUT_MS * 1_000_000L
                            ) {
                                requestFastFallback(
                                    "MDX-Net did not reach a safe real-time rate during calibration.",
                                )
                            }

                            if (
                                shouldUpdateUi &&
                                isCurrent(id)
                            ) {
                                lastUiUpdateNs = nowNs
                                listener.onState(
                                    State.BUFFERING,
                                    if (forceDspFallback || fallbackRequested.get()) {
                                        "Fast Live — " +
                                            bufferedSeconds(buffered) +
                                            " / " +
                                            bufferedSeconds(required) +
                                            " s"
                                    } else if (startupTargetFinalized) {
                                        "Building a safe neural buffer — " +
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
                                if (forceDspFallback || usingFastFallback) {
                                    "Live karaoke playing — fast local vocal suppression"
                                } else {
                                    "Live karaoke playing — MDX-Net is processing ahead"
                                },
                            )
                        }
                    }

                    val block = queue?.take() ?: break
                    var offset = 0

                    while (
                        offset < block.size &&
                        !cancelled.get()
                    ) {
                        val writeStartNs = System.nanoTime()
                        val written =
                            audioTrack?.write(
                                block,
                                offset,
                                block.size - offset,
                                AudioTrack.WRITE_BLOCKING,
                            ) ?: 0
                        profiler.record(
                            LiveKaraokeStageProfiler.Stage.AUDIOTRACK_WRITE,
                            System.nanoTime() - writeStartNs,
                        )

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
                            !forceDspFallback &&
                            !usingFastFallback &&
                            !fallbackRequested.get() &&
                            consumerStarted &&
                            shouldSwitchToFallback(nowNs)
                        ) {
                            requestFastFallback(fallbackHealthReason())
                        }

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
            queue?.cancel()
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
            if (forceDspFallback || fallbackRequested.get()) {
                return (
                    sourceSampleRate.toLong() *
                        DSP_STARTUP_BUFFER_SECONDS.toLong()
                )
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }

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

            val maxLookaheadFrames =
                maxLiveKaraokeLookaheadFrames(
                    sourceSampleRate,
                    liveLookaheadSeconds(),
                )

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
                    maxLookaheadFrames = maxLookaheadFrames,
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

            if (measuredRate < MIN_NEURAL_SUSTAINED_RATE) {
                throw NeuralLiveNotViableException(
                    "Measured MDX-Net rate is %.2fx real-time; below the %.2fx sustained threshold."
                        .format(
                            measuredRate,
                            MIN_NEURAL_SUSTAINED_RATE,
                        )
                )
            }

            val target =
                calculateLiveKaraokeSafeBufferFrames(
                    sourceSampleRate = sourceSampleRate,
                    generatedPerWindow = generatedPerWindow,
                    startupBufferSeconds = STARTUP_BUFFER_SECONDS,
                    startupBufferWindows = STARTUP_BUFFER_WINDOWS,
                    remainingSeconds = remainingSeconds,
                    measuredProducerRate = measuredRate,
                    safetyMarginSeconds = LIVE_SAFETY_MARGIN_SECONDS,
                    maxLookaheadFrames = maxLookaheadFrames,
                )

            val targetSeconds =
                target.toDouble() /
                    sourceSampleRate.toDouble()

            if (
                !isNeuralLiveStartupViable(
                    measuredProducerRate = measuredRate,
                    requiredFrames = target.toLong(),
                    sourceSampleRate = sourceSampleRate,
                    minimumProducerRate = MIN_NEURAL_SUSTAINED_RATE,
                    maximumStartupSeconds = MAX_NEURAL_STARTUP_SECONDS,
                )
            ) {
                throw NeuralLiveNotViableException(
                    "Measured MDX-Net rate is %.2fx real-time; neural startup would require %.1f s."
                        .format(
                            measuredRate,
                            targetSeconds,
                        )
                )
            }

            startupTargetFrames = target
            startupTargetFinalized = true
            return startupTargetFrames
        }

        private fun noteProducerThroughput(
            emittedFrames: Int,
        ) {
            val now = System.nanoTime()

            if (producerRateStartNs == 0L) {
                producerRateStartNs = now
                lastRateSampleNs = now
                lastRateSampleFrames = 0L
            }

            producerRateFrames += emittedFrames.toLong()

            val minimumFrames =
                (
                    sourceSampleRate.toDouble() *
                        RATE_ESTIMATION_MIN_SECONDS
                )
                    .roundToInt()
                    .toLong()
                    .coerceAtLeast(1L)

            if (
                !estimatedProducerRate.isFinite() &&
                producerRateFrames >= minimumFrames
            ) {
                val elapsedSeconds =
                    (now - producerRateStartNs)
                        .coerceAtLeast(1L)
                        .toDouble() /
                        1_000_000_000.0

                estimatedProducerRate =
                    if (elapsedSeconds > 0.0) {
                        (
                            producerRateFrames.toDouble() /
                                sourceSampleRate.toDouble()
                        ) / elapsedSeconds
                    } else {
                        Double.NaN
                    }
            }

            if (
                lastRateSampleNs > 0L &&
                now - lastRateSampleNs >= 1_000_000_000L
            ) {
                val deltaFrames =
                    producerRateFrames - lastRateSampleFrames
                val deltaSeconds =
                    (now - lastRateSampleNs).toDouble() /
                        1_000_000_000.0
                if (deltaSeconds > 0.0) {
                    rollingProducerRate =
                        (
                            deltaFrames.toDouble() /
                                sourceSampleRate.toDouble()
                        ) / deltaSeconds
                }
                lastRateSampleFrames = producerRateFrames
                lastRateSampleNs = now
            }
        }

        private fun pollRuntimeHealth(nowNs: Long) {
            if (
                lastHealthPollNs != 0L &&
                nowNs - lastHealthPollNs < THERMAL_POLL_INTERVAL_MS * 1_000_000L
            ) {
                return
            }
            lastHealthPollNs = nowNs

            thermalStatus =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        (
                            appContext.getSystemService(
                                Context.POWER_SERVICE
                            ) as? PowerManager
                        )?.currentThermalStatus
                    }.getOrNull()
                } else {
                    null
                }
        }

        private fun dspStartupBufferFrames(): Int =
            (
                sourceSampleRate.toLong() *
                    DSP_STARTUP_BUFFER_SECONDS.toLong()
            )
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        private fun shouldSwitchToFallback(nowNs: Long): Boolean {
            pollRuntimeHealth(nowNs)

            val bufferSeconds =
                (queue?.availableFrames() ?: 0).toDouble() /
                    sourceSampleRate.toDouble()

            val thermalCritical =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    (
                        thermalStatus == PowerManager.THERMAL_STATUS_SEVERE ||
                            thermalStatus == PowerManager.THERMAL_STATUS_CRITICAL
                    )

            return thermalCritical ||
                (
                    rollingProducerRate.isFinite() &&
                        rollingProducerRate < SUSTAINED_RATE_FALLBACK &&
                        bufferSeconds <= MIN_BUFFER_BEFORE_RATE_FALLBACK_SECONDS
                )
        }

        private fun fallbackHealthReason(): String {
            val rate =
                if (rollingProducerRate.isFinite()) {
                    String.format(
                        java.util.Locale.US,
                        "%.2fx",
                        rollingProducerRate,
                    )
                } else {
                    "unknown"
                }
            val thermal =
                thermalStatus?.toString() ?: "unavailable"
            return "Live neural throughput became unsafe (rate=$rate, thermal=$thermal)."
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

            // Count only neural output toward the neural throughput estimate.
            // Fast Live uses the same queue after takeover and must not inflate
            // the neural rate sample with its much faster DSP throughput.
            if (!forceDspFallback && !usingFastFallback) {
                noteProducerThroughput(block.size)
            }

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

            val pcm = ShortArray(count * 2)
            var output = 0

            for (index in start until start + count) {
                pcm[output++] =
                    floatToPcm16(block.left[index])
                pcm[output++] =
                    floatToPcm16(block.right[index])
            }

            val queueStartNs = System.nanoTime()
            queue?.put(pcm) ?: error("Live PCM queue is not initialized")
            profiler.record(
                LiveKaraokeStageProfiler.Stage.QUEUE_ENQUEUE,
                System.nanoTime() - queueStartNs,
            )
        }

        private fun decodeLoop(
            extractor: MediaExtractor,
            decoder: MediaCodec,
            inputFormat: MediaFormat,
            onPcmBlock: (VocalSeparatorCore.Stereo) -> Unit,
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

                                profiler.measure(
                                    LiveKaraokeStageProfiler.Stage.DECODE_LOOP
                                ) {
                                    feedDecodedPcm(
                                        buffer = duplicate,
                                        encoding = encoding,
                                        channels = decoderChannels,
                                        onPcmBlock = onPcmBlock,
                                    )
                                }
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
            onPcmBlock: (VocalSeparatorCore.Stereo) -> Unit,
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

                onPcmBlock(
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

    }
}
