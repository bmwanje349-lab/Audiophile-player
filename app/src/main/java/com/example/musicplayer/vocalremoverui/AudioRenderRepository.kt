package com.example.musicplayer.vocalremoverui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.bmwanje.audiophile.vocalremover.StreamingVocalRemover
import com.bmwanje.audiophile.vocalremover.VocalRemoverPipeline
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Bounded-memory offline audio renderer for the AI Vocal Remover.
 *
 * The decoder never accumulates the whole song. Decoder output is converted
 * into small stereo FloatArray blocks, fed into StreamingVocalRemover, and the
 * resulting PCM blocks are written directly to a seekable WAV file.
 */
class AudioRenderRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Audiophile-AudioRender").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var activeTask: Future<*>? = null

    @Synchronized
    fun renderVocalRemovalToWav(
        uri: Uri,
        titleSuffix: String,
        pipelineFactory: (sampleRate: Int) -> VocalRemoverPipeline,
        configurePipeline: (VocalRemoverPipeline) -> Unit,
        onProgress: (fraction: Float) -> Unit,
        onMdxChunks: (count: Long) -> Unit,
        onReady: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (activeTask?.isDone == false) {
            throw IllegalStateException("A vocal-removal render is already running")
        }

        activeTask = executor.submit {
            var pipeline: VocalRemoverPipeline? = null
            var streaming: StreamingVocalRemover? = null
            var writer: StreamingWavWriter? = null
            var track: AudioTrackResources? = null

            try {
                ensureNotInterrupted()
                val audioTrack = findAudioTrack(uri)
                track = audioTrack

                ensureNotInterrupted()
                val localPipeline =
                    pipelineFactory(audioTrack.sampleRate)
                pipeline = localPipeline

                ensureNotInterrupted()
                configurePipeline(localPipeline)
                ensureNotInterrupted()
                localPipeline.loadModel()
                ensureNotInterrupted()

                val output =
                    createOutputFile(titleSuffix)
                val wavWriter =
                    StreamingWavWriter(
                        file = output,
                        sampleRate = audioTrack.sampleRate,
                    )
                writer = wavWriter

                val renderer =
                    localPipeline.startStreaming { block ->
                        wavWriter.write(block)
                    }
                streaming = renderer

                ensureNotInterrupted()
                decodeTrack(
                    extractor = audioTrack.extractor,
                    inputFormat = audioTrack.format,
                    processor = renderer,
                    expectedDurationUs = audioTrack.durationUs,
                    onProgress = onProgress,
                )

                ensureNotInterrupted()
                renderer.finish()
                ensureNotInterrupted()
                check(renderer.inputSamples() == wavWriter.framesWritten) {
                    "Input/output frame mismatch: " +
                        renderer.inputSamples() +
                        " input vs " +
                        wavWriter.framesWritten +
                        " output"
                }

                ensureNotInterrupted()
                wavWriter.finish()

                ensureNotInterrupted()
                onMdxChunks(renderer.mdxInferenceCount())
                onProgress(1f)
                onReady(Uri.fromFile(output))
                writer = null
            } catch (throwable: Throwable) {
                if (!Thread.currentThread().isInterrupted) {
                    onError(throwable)
                }
            } finally {
                runCatching { streaming?.close() }
                runCatching { pipeline?.close() }
                runCatching { writer?.closeSilently() }
                runCatching { track?.extractor?.release() }
            }
        }
    }

    @Synchronized
    fun cancel() {
        activeTask?.takeIf { !it.isDone }?.cancel(true)
    }

    fun close() {
        cancel()
        executor.shutdownNow()
    }

    private fun ensureNotInterrupted() {
        check(!Thread.currentThread().isInterrupted) {
            "Vocal-removal render cancelled"
        }
    }

    private fun findAudioTrack(uri: Uri): AudioTrackResources {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(appContext, uri, null)

            var audioTrack = -1
            var format: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime =
                    candidate.getString(MediaFormat.KEY_MIME)
                        ?: continue

                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    format = candidate
                    break
                }
            }

            check(audioTrack >= 0 && format != null) {
                "No audio track was found in $uri"
            }

            extractor.selectTrack(audioTrack)

            return AudioTrackResources(
                extractor = extractor,
                format = format,
                sampleRate =
                    format.getIntegerSafely(
                        MediaFormat.KEY_SAMPLE_RATE
                    )
                        ?: error("Audio sample rate is missing"),
                durationUs =
                    format.getLongSafely(MediaFormat.KEY_DURATION),
            )
        } catch (throwable: Throwable) {
            extractor.release()
            throw throwable
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        processor: StreamingVocalRemover,
        expectedDurationUs: Long?,
        onProgress: (Float) -> Unit,
    ) {
        val mime =
            inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio MIME type is missing")

        val decoder =
            MediaCodec.createDecoderByType(mime)

        var decoderStarted = false

        val bufferInfo = MediaCodec.BufferInfo()

        var sampleRate =
            inputFormat.getIntegerSafely(MediaFormat.KEY_SAMPLE_RATE)
                ?: error("Audio sample rate is missing")

        var channels =
            inputFormat.getIntegerSafely(MediaFormat.KEY_CHANNEL_COUNT)
                ?: error("Audio channel count is missing")

        require(channels in 1..2) {
            "AI Vocal Remover currently supports mono or stereo sources; decoder reported $channels channels"
        }

        var encoding =
            inputFormat.getIntegerSafely(MediaFormat.KEY_PCM_ENCODING)
                ?: AudioFormat.ENCODING_PCM_16BIT

        val expectedFrames =
            if (expectedDurationUs != null && expectedDurationUs > 0L) {
                expectedDurationUs * sampleRate / 1_000_000L
            } else {
                -1L
            }

        var inputEnded = false
        var outputEnded = false
        var decodedFrames = 0L

        try {
            decoder.configure(
                inputFormat,
                null,
                null,
                0,
            )
            decoder.start()
            decoderStarted = true

            while (!outputEnded) {
                ensureNotInterrupted()
                if (!inputEnded) {
                    val inputIndex =
                        decoder.dequeueInputBuffer(10_000)

                    if (inputIndex >= 0) {
                        val inputBuffer =
                            decoder.getInputBuffer(inputIndex)
                                ?: error("Decoder input buffer is unavailable")

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
                        val outputFormat = decoder.outputFormat

                        sampleRate =
                            outputFormat.getIntegerSafely(
                                MediaFormat.KEY_SAMPLE_RATE
                            ) ?: sampleRate

                        channels =
                            outputFormat.getIntegerSafely(
                                MediaFormat.KEY_CHANNEL_COUNT
                            ) ?: channels

                        require(channels in 1..2) {
                            "AI Vocal Remover currently supports mono or stereo sources; decoder reported $channels channels"
                        }

                        encoding =
                            outputFormat.getIntegerSafely(
                                MediaFormat.KEY_PCM_ENCODING
                            ) ?: encoding
                    }

                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer =
                                decoder.getOutputBuffer(outputIndex)

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
                                        .order(ByteOrder.LITTLE_ENDIAN)

                                duplicate.position(bufferInfo.offset)
                                duplicate.limit(
                                    bufferInfo.offset + bufferInfo.size
                                )

                                decodedFrames +=
                                    feedDecodedPcm(
                                        buffer = duplicate,
                                        encoding = encoding,
                                        channels = channels,
                                        processor = processor,
                                    )

                                if (expectedFrames > 0L) {
                                    onProgress(
                                        (
                                            decodedFrames.toDouble() /
                                                expectedFrames.toDouble()
                                        )
                                            .toFloat()
                                            .coerceIn(0f, 0.98f)
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
        } finally {
            if (decoderStarted) {
                runCatching { decoder.stop() }
            }
            decoder.release()
        }
    }

    private fun feedDecodedPcm(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        processor: StreamingVocalRemover,
    ): Long {
        require(channels > 0) {
            "Decoder produced an invalid channel count: $channels"
        }

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
            bytesPerSample.toLong() * channels.toLong()
        check(frameBytes <= Int.MAX_VALUE)

        val availableBytes = buffer.remaining()
        check(
            availableBytes.toLong() % frameBytes == 0L
        ) {
            "Decoder PCM is not aligned to complete audio frames"
        }

        var remainingFrames =
            (availableBytes.toLong() / frameBytes).toInt()

        var decodedFrames = 0L

        while (remainingFrames > 0) {
            val count = minOf(8192, remainingFrames)
            val left = FloatArray(count)
            val right = FloatArray(count)

            for (frame in 0 until count) {
                if (channels == 1) {
                    val sample = readSample(buffer, encoding)
                    left[frame] = sample
                    right[frame] = sample
                } else {
                    left[frame] =
                        readSample(buffer, encoding)
                    right[frame] =
                        readSample(buffer, encoding)

                    for (channel in 2 until channels) {
                        readSample(buffer, encoding)
                    }
                }
            }

            processor.push(
                VocalSeparatorCore.Stereo(
                    left,
                    right,
                )
            )

            decodedFrames += count.toLong()
            remainingFrames -= count
        }

        return decodedFrames
    }

    private fun readSample(
        buffer: ByteBuffer,
        encoding: Int,
    ): Float {
        val value =
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT ->
                    buffer.short.toInt() / 32768f

                AudioFormat.ENCODING_PCM_FLOAT ->
                    buffer.float

                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val b0 = buffer.get().toInt() and 0xFF
                    val b1 = buffer.get().toInt() and 0xFF
                    val b2 = buffer.get().toInt()

                    val unsigned =
                        b0 or
                            (b1 shl 8) or
                            (b2 shl 16)

                    val signed =
                        if ((unsigned and 0x800000) != 0) {
                            unsigned or -0x1000000
                        } else {
                            unsigned
                        }

                    signed / 8_388_608f
                }

                AudioFormat.ENCODING_PCM_32BIT ->
                    buffer.int / 2_147_483_648f

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

    private fun createOutputFile(titleSuffix: String): File {
        val safeSuffix =
            titleSuffix
                .replace(
                    Regex("[^A-Za-z0-9._-]+"),
                    "_",
                )
                .trim('_')
                .ifBlank { "Instrumental" }

        return File(
            appContext.cacheDir,
            "audiophile_" +
                System.currentTimeMillis() +
                "_" +
                safeSuffix +
                ".wav",
        )
    }

    private data class AudioTrackResources(
        val extractor: MediaExtractor,
        val format: MediaFormat,
        val sampleRate: Int,
        val durationUs: Long?,
    )

    private fun MediaFormat.getIntegerSafely(
        key: String,
    ): Int? =
        runCatching { getInteger(key) }.getOrNull()

    private fun MediaFormat.getLongSafely(
        key: String,
    ): Long? =
        runCatching { getLong(key) }.getOrNull()
}

/**
 * Streaming PCM16 stereo WAV writer.
 *
 * A seekable file is used so the RIFF/data lengths can be patched only after
 * the full render has completed. Memory usage remains fixed at 8192 frames.
 */
private class StreamingWavWriter(
    private val file: File,
    private val sampleRate: Int,
) {
    private val random = RandomAccessFile(file, "rw")
    private val byteBuffer =
        ByteBuffer
            .allocate(8192 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

    var framesWritten: Long = 0L
        private set

    init {
        require(sampleRate >= 8_000)
        random.setLength(44L)
        random.seek(44L)
    }

    fun write(block: VocalSeparatorCore.Stereo) {
        require(block.left.size == block.right.size)
        var pos = 0

        while (pos < block.size) {
            val count = minOf(
                8192,
                block.size - pos,
            )

            byteBuffer.clear()
            for (i in 0 until count) {
                byteBuffer.putShort(
                    floatToPcm16(block.left[pos + i])
                )
                byteBuffer.putShort(
                    floatToPcm16(block.right[pos + i])
                )
            }

            random.write(
                byteBuffer.array(),
                0,
                count * 4,
            )

            framesWritten += count.toLong()
            pos += count
        }
    }

    fun finish() {
        val dataSize =
            Math.multiplyExact(
                framesWritten,
                4L,
            )

        check(
            dataSize <=
                0xFFFF_FFFFL - 36L
        ) {
            "Processed WAV is too large for RIFF/WAV"
        }

        random.seek(0L)
        writeAscii("RIFF")
        writeIntLE((36L + dataSize).toInt())
        writeAscii("WAVE")

        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(2)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 4)
        writeShortLE(4)
        writeShortLE(16)

        writeAscii("data")
        writeIntLE(dataSize.toInt())

        random.fd.sync()
        random.close()
    }

    fun closeSilently() {
        runCatching {
            random.close()
        }
        file.delete()
    }

    private fun writeAscii(value: String) {
        random.write(
            value.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun writeIntLE(value: Int) {
        random.write(value and 0xFF)
        random.write((value ushr 8) and 0xFF)
        random.write((value ushr 16) and 0xFF)
        random.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLE(value: Int) {
        random.write(value and 0xFF)
        random.write((value ushr 8) and 0xFF)
    }

    private fun floatToPcm16(value: Float): Short {
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
            .toInt()
            .coerceIn(-32768, 32767)
            .toShort()
    }
}
