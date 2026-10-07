private data class RenderBlock(
    val generation: Long,
    val audio: VocalSeparatorCore.Stereo,
) {
    val size: Int get() = audio.size
}

package com.example.musicplayer.vocalremoverui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.bmwanje.audiophile.vocalremover.VocalRemoverPipeline
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

class KaraokePlaybackController(
    private val onSnapshot: (Snapshot) -> Unit,
) : AutoCloseable {

    enum class State { IDLE, PREPARING, BUFFERING, PLAYING, PAUSED, DRAINING, COMPLETED, ERROR, STOPPED }

    data class Snapshot(
        val state: State,
        val positionMs: Long,
        val durationMs: Long,
        val bufferAheadMs: Long,
        val message: String,
    )

    data class Config(
        val uri: Uri,
        val title: String,
        val depth: Float = 1f,
        val focus: Float = 0.5f,
        val transientProtection: Float = 0.7f,
        val dryWet: Float = 1f,
        val stemGainDb: Float = 0f,
        val outputGainDb: Float = 0f,
        val ceilingDb: Float = -1f,
    )

    private val processExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "Audiophile-Karaoke-Process").apply { isDaemon = true }
    }
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "Audiophile-Karaoke-Audio").apply { isDaemon = true }
    }

    private val queue = BlockQueue(12_000)
    private val audioLock = Any()

    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile private var autoplay = true
    @Volatile private var primed = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var sampleRate = 0
    @Volatile private var writtenFrames = 0L

    private var config: Config? = null
    private var processFuture: Future<*>? = null
    private var pipeline: VocalRemoverPipeline? = null
    private var durationMs = 0L
    private var startPositionMs = 0L

    init {
        audioExecutor.execute { audioLoop() }
    }

    fun pause() {
        if (closed) return
        paused = true
        autoplay = false
        synchronized(audioLock) { track?.pause() }
        publish(State.PAUSED, "Paused — processing remains ahead")
    }

    fun resume() {
        if (closed) return
        paused = false
        autoplay = true
        synchronized(audioLock) {
            if (primed) track?.play()
        }
        publish(if (primed) State.PLAYING else State.BUFFERING, "Resuming karaoke…")
    }

    fun stop() {
        stopCurrent(true)
    }

    override fun close() {
        if (closed) return
        closed = true
        stopCurrent(true)
        queue.cancel()
        audioExecutor.shutdownNow()
        processExecutor.shutdownNow()
    }

    private fun stopCurrent(markClosed: Boolean) {
        if (markClosed) closed = true
        generation += 1L
        processFuture?.cancel(true)
        processFuture = null
        queue.reset()
        primed = false
        synchronized(audioLock) {
            track?.runCatching { pause() }
            track?.runCatching { flush() }
            track?.runCatching { stop() }
            track?.runCatching { release() }
            track = null
            sampleRate = 0
            writtenFrames = 0L
        }
        pipeline?.close()
        pipeline = null
        if (markClosed) publish(State.STOPPED, "Karaoke stopped")
    }

    private fun nextGeneration(positionMs: Long) {
        generation += 1L
        queue.reset()
        primed = false
        writtenFrames = 0L
        startPositionMs = positionMs
    }

    private fun setupTrack(rate: Int) {
        synchronized(audioLock) {
            if (track != null && sampleRate == rate) {
                track?.pause()
                track?.flush()
                writtenFrames = 0L
                return
            }

            track?.runCatching { release() }

            val mask = AudioFormat.CHANNEL_OUT_STEREO
            val minBytes = AudioTrack.getMinBufferSize(
                rate,
                mask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBytes > 0) { "AudioTrack does not support this sample rate" }

            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBytes * 2, rate * 4 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { check(it.state == AudioTrack.STATE_INITIALIZED) }

            sampleRate = rate
            writtenFrames = 0L
        }
    }

    private fun audioLoop() {
        val pcm = ShortArray(8192 * 2)
        val conversion = ByteBuffer.allocate(8192 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        var lastUpdate = 0L

        while (!closed) {
            try {
                if (!primed) {
                    val ready = queue.awaitAtLeast(framesForMs(3000))
                    if (closed) break
                    if (!ready) continue
                    primed = true
                    synchronized(audioLock) {
                        if (autoplay && !paused) track?.play()
                    }
                    publish(
                        if (autoplay && !paused) State.PLAYING else State.PAUSED,
                        "First karaoke windows ready",
                    )
                }

                val block = queue.take() ?: run {
                    if (queue.finishedAndEmpty()) {
                        drainAudio()
                    }
                    continue
                }

                if (block.generation != generation) continue

                var offset = 0
                while (offset < block.audio.size && !closed) {
                    if (block.generation != generation) break

                    val count = min(8192, block.audio.size - offset)
                    conversion.clear()

                    for (i in 0 until count) {
                        val l = (block.audio.left[offset + i].coerceIn(-1f, 1f) * 32767f)
                            .toInt().coerceIn(-32768, 32767).toShort()
                        val r = (block.audio.right[offset + i].coerceIn(-1f, 1f) * 32767f)
                            .toInt().coerceIn(-32768, 32767).toShort()
                        conversion.putShort(l)
                        conversion.putShort(r)
                    }

                    conversion.flip()
                    conversion.asShortBuffer().get(pcm, 0, count * 2)

                    val written = synchronized(audioLock) {
                        if (block.generation != generation) 0
                        else track?.write(
                            pcm,
                            0,
                            count * 2,
                            AudioTrack.WRITE_NON_BLOCKING,
                        ) ?: 0
                    }

                    if (written <= 0) {
                        SystemClock.sleep(5L)
                        continue
                    }

                    writtenFrames += (written / 2).toLong()
                    offset += written / 2
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 250L) {
                    lastUpdate = now
                    publish(currentState(), null)
                }
            } catch (_: InterruptedException) {
                if (closed) break
            } catch (t: Throwable) {
                if (!closed) publish(State.ERROR, "Audio output failed: " + (t.message ?: "unknown error"))
            }
        }
    }

    private fun drainAudio() {
        while (!closed && audioAheadFrames() > 0L) {
            SystemClock.sleep(40L)
            publish(currentState(), null)
        }

        if (!closed) {
            synchronized(audioLock) {
                track?.pause()
                track?.flush()
            }
            publish(State.COMPLETED, "Karaoke playback complete")
        }
    }

    private fun runDecode(
        extractor: MediaExtractor,
        format: MediaFormat,
        processor: com.bmwanje.audiophile.vocalremover.StreamingVocalRemover,
        localGeneration: Long,
    ) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var channels = format.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        var encoding = format.getIntegerSafe(MediaFormat.KEY_PCM_ENCODING)
            ?: AudioFormat.ENCODING_PCM_16BIT

        try {
            while (!outputDone && isCurrent(localGeneration)) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: error("Missing codec input")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        channels = out.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        encoding = out.getIntegerSafe(MediaFormat.KEY_PCM_ENCODING) ?: encoding
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (buffer != null && info.size > 0 && !config) {
                            val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            copy.position(info.offset)
                            copy.limit(info.offset + info.size)
                            feed(copy, encoding, channels, processor)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun feed(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        processor: com.bmwanje.audiophile.vocalremover.StreamingVocalRemover,
    ) {
        val bytesPer = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> error("Unsupported PCM encoding")
        }
        val frameBytes = bytesPer.toLong() * channels
        check(buffer.remaining().toLong() % frameBytes == 0L)

        var remaining = (buffer.remaining().toLong() / frameBytes).toInt()
        while (remaining > 0) {
            val count = min(8192, remaining)
            val left = FloatArray(count)
            val right = FloatArray(count)

            for (i in 0 until count) {
                if (channels == 1) {
                    val s = readSample(buffer, encoding)
                    left[i] = s
                    right[i] = s
                } else {
                    left[i] = readSample(buffer, encoding)
                    right[i] = readSample(buffer, encoding)
                    for (c in 2 until channels) readSample(buffer, encoding)
                }
            }

            processor.push(VocalSeparatorCore.Stereo(left, right))
            remaining -= count
        }
    }

    private fun readSample(buffer: ByteBuffer, encoding: Int): Float {
        val v = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> buffer.short.toInt() / 32768f
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val b0 = buffer.get().toInt() and 0xff
                val b1 = buffer.get().toInt() and 0xff
                val b2 = buffer.get().toInt()
                val u = b0 or (b1 shl 8) or (b2 shl 16)
                val s = if ((u and 0x800000) != 0) u or -0x1000000 else u
                s / 8_388_608f
            }
            AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
            else -> 0f
        }
        return if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
    }

    private fun currentState(): State =
        if (paused) State.PAUSED else State.PLAYING

    private fun publish(state: State, message: String?) {
        onSnapshot(
            Snapshot(
                state = state,
                positionMs = positionMs(),
                durationMs = durationMs,
                bufferAheadMs = bufferAheadMs(),
                message = message ?: "",
            )
        )
    }

    private fun positionMs(): Long {
        val rate = sampleRate.takeIf { it > 0 } ?: return startPositionMs
        val head = synchronized(audioLock) {
            track?.playbackHeadPosition?.toLong()?.and(0xFFFF_FFFFL)
        } ?: 0L
        return (startPositionMs + head * 1000L / rate).coerceIn(0L, durationMs)
    }

    private fun bufferAheadMs(): Long {
        val rate = sampleRate.takeIf { it > 0 } ?: return 0L
        val queued = queue.frames().toLong()
        val audioAhead = audioAheadFrames()
        return (queued + audioAhead) * 1000L / rate
    }

    private fun audioAheadFrames(): Long {
        val head = synchronized(audioLock) {
            track?.playbackHeadPosition?.toLong()?.and(0xFFFF_FFFFL)
        } ?: 0L
        return (writtenFrames - head).coerceAtLeast(0L)
    }

    private fun framesForMs(ms: Long): Int {
        val rate = sampleRate.takeIf { it > 0 } ?: 44100
        return (rate.toLong() * ms / 1000L).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isCurrent(localGeneration: Long): Boolean =
        !closed && localGeneration == generation

    private fun publish(state: State, message: String?) {
        onSnapshot(
            Snapshot(
                state,
                positionMs(),
                durationMs,
                bufferAheadMs(),
                message ?: "",
            )
        )
    }

    private fun launchWithContext(context: android.content.Context, positionMs: Long) {
        val localGeneration = generation
        processFuture = processExecutor.submit {
            runProcessingWithContext(context, localGeneration, positionMs)
        }
    }

    private fun runProcessingWithContext(
        context: android.content.Context,
        localGeneration: Long,
        positionMs: Long,
    ) {
        val cfg = config ?: return
        var extractor: MediaExtractor? = null
        var streaming: com.bmwanje.audiophile.vocalremover.StreamingVocalRemover? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, cfg.uri, null)

            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }

            val audioFormat = format ?: error("No audio track found")
            val rate = audioFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE)
                ?: error("Missing sample rate")
            durationMs = ((audioFormat.getLongSafe(MediaFormat.KEY_DURATION) ?: 0L) / 1000L).coerceAtLeast(0L)

            setupTrack(rate)

            val p = VocalRemoverPipeline(context.applicationContext, rate)
            p.setDepth(cfg.depth)
            p.setFocus(cfg.focus)
            p.setTransientProtection(cfg.transientProtection)
            p.setDryWet(cfg.dryWet)
            p.setStemGainDb(cfg.stemGainDb)
            p.setOutputGainDb(cfg.outputGainDb)
            p.setCeilingDb(cfg.ceilingDb)
            p.loadModel()

            pipeline?.close()
            pipeline = p

            streaming = p.startStreaming { block ->
                if (!isCurrent(localGeneration)) return@startStreaming
                queue.put(RenderBlock(localGeneration, block))
            }

            if (positionMs > 0L) {
                extractor.seekTo(positionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            publish(State.BUFFERING, "Processing karaoke audio…")
            runDecode(extractor, audioFormat, streaming, localGeneration)
            if (!isCurrent(localGeneration)) return

            streaming.finish()
            queue.finish()
            publish(State.DRAINING, "Finishing the current karaoke buffer…")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            if (isCurrent(localGeneration)) publish(State.ERROR, "Karaoke failed: " + (t.message ?: "unknown error"))
        } finally {
            runCatching { streaming?.close() }
            runCatching { extractor?.release() }
        }
    }

    // Rebind the public start/seek methods to the context-aware processing implementation.
    private fun launchProcessingFromContext(context: android.content.Context, positionMs: Long) =
        launchWithContext(context, positionMs)

    fun startWithContext(context: android.content.Context, newConfig: Config) {
        stopCurrent(false)
        closed = false
        paused = false
        autoplay = true
        primed = false
        config = newConfig
        startPositionMs = 0L
        durationMs = 0L
        nextGeneration(0L)
        publish(State.PREPARING, "Preparing MDX-Net karaoke…")
        launchProcessingFromContext(context.applicationContext, 0L)
    }

    fun seekWithContext(context: android.content.Context, positionMs: Long) {
        if (closed) return
        val target = positionMs.coerceIn(0L, durationMs)
        val keepPlaying = !paused
        autoplay = keepPlaying
        paused = !keepPlaying
        nextGeneration(target)
        synchronized(audioLock) {
            track?.pause()
            track?.flush()
            writtenFrames = 0L
        }
        publish(State.PREPARING, "Rebuilding the separation window…")
        launchProcessingFromContext(context.applicationContext, target)
    }
}

private class BlockQueue(
    maxMs: Long,
) {
    private val lock = Object()
    private val blocks = ArrayDeque<RenderBlock>()
    private val maxFrames = (48_000L * maxMs / 1000L).toInt()
    private var frames = 0
    private var finished = false
    private var cancelled = false

    fun reset() = synchronized(lock) {
        blocks.clear()
        frames = 0
        finished = false
        cancelled = false
        lock.notifyAll()
    }

    fun cancel() = synchronized(lock) {
        cancelled = true
        finished = true
        blocks.clear()
        frames = 0
        lock.notifyAll()
    }

    fun put(block: RenderBlock) = synchronized(lock) {
        while (!cancelled && frames + block.size > maxFrames) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            lock.wait(50L)
        }
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException()
        blocks.addLast(block)
        frames += block.size
        lock.notifyAll()
    }

    fun take(): RenderBlock? = synchronized(lock) {
        while (blocks.isEmpty() && !finished && !cancelled) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            lock.wait(50L)
        }
        if (cancelled || blocks.isEmpty()) null
        else blocks.removeFirst().also { frames -= it.size; lock.notifyAll() }
    }

    fun awaitAtLeast(n: Int): Boolean = synchronized(lock) {
        while (frames < n && !finished && !cancelled) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            lock.wait(50L)
        }
        frames >= n
    }

    fun finishedAndEmpty(): Boolean = synchronized(lock) { finished && blocks.isEmpty() }
    fun frames(): Int = synchronized(lock) { frames }
}
