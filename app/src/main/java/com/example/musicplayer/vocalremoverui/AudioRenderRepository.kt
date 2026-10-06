package com.example.musicplayer.vocalremoverui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Offline audio I/O for the AI Vocal Remover.
 *
 * This class is deliberately separate from the live Media3 DSP chain.
 *
 * MediaStore Uri
 *   -> MediaExtractor / MediaCodec
 *   -> stereo Float PCM
 *   -> VocalRemoverPipeline
 *   -> 16-bit PCM WAV
 *   -> file Uri
 *
 * The decoder preserves the source sample rate. MDX-Net resampling is handled
 * internally by the separator.
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

    fun decodeToStereoPcm(
        uri: Uri,
        onReady: (
            sampleRate: Int,
            stereo: com.bmwanje.audiophile.vocalremover.VocalSeparatorCore.Stereo,
        ) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        executor.execute {
            try {
                val decoded = decode(uri)

                onReady(
                    decoded.sampleRate,
                    decoded.toStereo().toSeparatorStereo(),
                )
            } catch (throwable: Throwable) {
                onError(throwable)
            }
        }
    }

    fun encodeStereoPcmToWav(
        pcm: com.bmwanje.audiophile.vocalremover.VocalSeparatorCore.Stereo,
        sampleRate: Int,
        titleSuffix: String,
        onReady: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        executor.execute {
            try {
                val output = createWavFile(
                    pcm = pcm,
                    sampleRate = sampleRate,
                    titleSuffix = titleSuffix,
                )

                onReady(Uri.fromFile(output))
            } catch (throwable: Throwable) {
                onError(throwable)
            }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun decode(uri: Uri): DecodedPcm {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(appContext, uri, null)

            var audioTrack = -1
            var inputFormat: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)

                val mime =
                    candidate.getString(MediaFormat.KEY_MIME)
                        ?: continue

                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = candidate
                    break
                }
            }

            check(audioTrack >= 0 && inputFormat != null) {
                "No audio track was found in $uri"
            }

            extractor.selectTrack(audioTrack)

            val mime =
                inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: error("Audio MIME type is missing")

            val decoder =
                MediaCodec.createDecoderByType(mime)

            try {
                decoder.configure(
                    inputFormat,
                    null,
                    null,
                    0,
                )

                decoder.start()

                return decodeWithCodec(
                    extractor = extractor,
                    decoder = decoder,
                    inputFormat = inputFormat,
                )
            } finally {
                runCatching {
                    decoder.stop()
                }

                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeWithCodec(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        inputFormat: MediaFormat,
    ): DecodedPcm {

        val bufferInfo =
            MediaCodec.BufferInfo()

        var sampleRate =
            inputFormat.getIntegerSafely(
                MediaFormat.KEY_SAMPLE_RATE
            ) ?: error("Audio sample rate is missing")

        var channels =
            inputFormat.getIntegerSafely(
                MediaFormat.KEY_CHANNEL_COUNT
            ) ?: error("Audio channel count is missing")

        var encoding =
            inputFormat.getIntegerSafely(
                MediaFormat.KEY_PCM_ENCODING
            ) ?: AudioFormat.ENCODING_PCM_16BIT

        val samples =
            FloatArrayAccumulator()

        var inputEnded = false
        var outputEnded = false

        while (!outputEnded) {

            if (!inputEnded) {
                val inputIndex =
                    decoder.dequeueInputBuffer(10_000)

                if (inputIndex >= 0) {
                    val inputBuffer =
                        decoder.getInputBuffer(inputIndex)
                            ?: error(
                                "Decoder input buffer is unavailable"
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

                    sampleRate =
                        outputFormat.getIntegerSafely(
                            MediaFormat.KEY_SAMPLE_RATE
                        ) ?: sampleRate

                    channels =
                        outputFormat.getIntegerSafely(
                            MediaFormat.KEY_CHANNEL_COUNT
                        ) ?: channels

                    encoding =
                        outputFormat.getIntegerSafely(
                            MediaFormat.KEY_PCM_ENCODING
                        ) ?: encoding
                }

                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> {

                    if (outputIndex >= 0) {

                        val outputBuffer =
                            decoder.getOutputBuffer(
                                outputIndex
                            )

                        if (
                            outputBuffer != null &&
                            bufferInfo.size > 0
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

                            appendPcm(
                                buffer = duplicate,
                                encoding = encoding,
                                output = samples,
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

        check(channels > 0) {
            "Decoder produced an invalid channel count: $channels"
        }

        check(sampleRate >= 8_000) {
            "Decoder produced an invalid sample rate: $sampleRate"
        }

        check(samples.size > 0) {
            "Decoder produced no PCM audio"
        }

        check(samples.size % channels == 0) {
            "Decoded PCM is not aligned to complete audio frames"
        }

        return DecodedPcm(
            sampleRate = sampleRate,
            channels = channels,
            pcm = samples.toArray(),
        )
    }

    private fun appendPcm(
        buffer: ByteBuffer,
        encoding: Int,
        output: FloatArrayAccumulator,
    ) {

        when (encoding) {

            AudioFormat.ENCODING_PCM_16BIT -> {

                check(buffer.remaining() % 2 == 0) {
                    "Invalid PCM16 output size"
                }

                while (buffer.remaining() >= 2) {
                    output.add(
                        (
                            buffer.short.toInt() /
                                32768f
                        ).coerceIn(-1f, 1f)
                    )
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {

                check(buffer.remaining() % 4 == 0) {
                    "Invalid PCM float output size"
                }

                while (buffer.remaining() >= 4) {
                    output.add(
                        buffer.float.sanitizeSample()
                    )
                }
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {

                check(buffer.remaining() % 3 == 0) {
                    "Invalid packed PCM24 output size"
                }

                while (buffer.remaining() >= 3) {

                    val b0 =
                        buffer.get().toInt() and 0xFF

                    val b1 =
                        buffer.get().toInt() and 0xFF

                    val b2 =
                        buffer.get().toInt()

                    val unsigned =
                        b0 or
                            (b1 shl 8) or
                            (b2 shl 16)

                    val signed =
                        if (
                            (unsigned and 0x800000) != 0
                        ) {
                            unsigned or -0x1000000
                        } else {
                            unsigned
                        }

                    output.add(
                        (
                            signed /
                                8_388_608f
                        ).coerceIn(-1f, 1f)
                    )
                }
            }

            AudioFormat.ENCODING_PCM_32BIT -> {

                check(buffer.remaining() % 4 == 0) {
                    "Invalid PCM32 output size"
                }

                while (buffer.remaining() >= 4) {
                    output.add(
                        (
                            buffer.int /
                                2_147_483_648f
                        ).coerceIn(-1f, 1f)
                    )
                }
            }

            else -> {
                error(
                    "Unsupported decoder PCM encoding: $encoding"
                )
            }
        }
    }

    private fun DecodedPcm.toStereo(): StereoPcm {

        val frameCount =
            pcm.size / channels

        val left =
            FloatArray(frameCount)

        val right =
            FloatArray(frameCount)

        if (channels == 1) {

            for (frame in 0 until frameCount) {
                val value = pcm[frame]

                left[frame] = value
                right[frame] = value
            }

            return StereoPcm(
                sampleRate = sampleRate,
                left = left,
                right = right,
            )
        }

        /*
         * Android multichannel PCM is interleaved.
         *
         * The first two channels are treated as the conventional
         * front-left/front-right pair for the two-channel separator.
         */
        for (frame in 0 until frameCount) {

            val base =
                frame * channels

            left[frame] =
                pcm[base].sanitizeSample()

            right[frame] =
                pcm[base + 1].sanitizeSample()
        }

        return StereoPcm(
            sampleRate = sampleRate,
            left = left,
            right = right,
        )
    }

    private fun StereoPcm.toSeparatorStereo():
        com.bmwanje.audiophile.vocalremover.VocalSeparatorCore.Stereo {

        return com.bmwanje.audiophile.vocalremover.VocalSeparatorCore.Stereo(
            left = left,
            right = right,
        )
    }

    private fun createWavFile(
        pcm: com.bmwanje.audiophile.vocalremover.VocalSeparatorCore.Stereo,
        sampleRate: Int,
        titleSuffix: String,
    ): File {

        check(sampleRate >= 8_000) {
            "Invalid output sample rate: $sampleRate"
        }

        check(pcm.left.isNotEmpty()) {
            "Processed PCM is empty"
        }

        check(pcm.left.size == pcm.right.size) {
            "Processed stereo channels have different lengths"
        }

        val frameCount =
            pcm.left.size

        val dataSize =
            frameCount.toLong() * 4L

        check(
            dataSize <=
                0xFFFF_FFFFL - 36L
        ) {
            "Processed WAV is too large for RIFF/WAV"
        }

        val safeSuffix =
            titleSuffix
                .replace(
                    Regex("[^A-Za-z0-9._-]+"),
                    "_",
                )
                .trim('_')
                .ifBlank {
                    "Instrumental"
                }

        val file =
            File(
                appContext.cacheDir,
                "audiophile_" +
                    "${System.currentTimeMillis()}_" +
                    "$safeSuffix.wav",
            )

        try {

            FileOutputStream(file).use { output ->

                // RIFF header
                writeAscii(
                    output,
                    "RIFF",
                )

                writeIntLE(
                    output,
                    (36L + dataSize).toInt(),
                )

                writeAscii(
                    output,
                    "WAVE",
                )

                // fmt chunk
                writeAscii(
                    output,
                    "fmt ",
                )

                writeIntLE(
                    output,
                    16,
                )

                // PCM format
                writeShortLE(
                    output,
                    1,
                )

                // Stereo
                writeShortLE(
                    output,
                    2,
                )

                writeIntLE(
                    output,
                    sampleRate,
                )

                // byte rate
                writeIntLE(
                    output,
                    sampleRate * 4,
                )

                // block align
                writeShortLE(
                    output,
                    4,
                )

                // bits per sample
                writeShortLE(
                    output,
                    16,
                )

                // data chunk
                writeAscii(
                    output,
                    "data",
                )

                writeIntLE(
                    output,
                    dataSize.toInt(),
                )

                val buffer =
                    ByteBuffer
                        .allocate(8192)
                        .order(
                            ByteOrder.LITTLE_ENDIAN
                        )

                var frame = 0

                while (frame < frameCount) {

                    buffer.clear()

                    var bytesWritten = 0

                    while (
                        frame < frameCount &&
                        buffer.remaining() >= 4
                    ) {

                        buffer.putShort(
                            floatToPcm16(
                                pcm.left[frame]
                            )
                        )

                        buffer.putShort(
                            floatToPcm16(
                                pcm.right[frame]
                            )
                        )

                        frame++
                        bytesWritten += 4
                    }

                    output.write(
                        buffer.array(),
                        0,
                        bytesWritten,
                    )
                }
            }

        } catch (throwable: Throwable) {

            file.delete()
            throw throwable
        }

        return file
    }

    private fun Float.sanitizeSample(): Float {

        if (!isFinite()) {
            return 0f
        }

        return coerceIn(
            -1f,
            1f,
        )
    }

    private fun floatToPcm16(
        value: Float,
    ): Short {

        val safe =
            value.sanitizeSample()

        val scaled =
            if (safe < 0f) {
                safe * 32768f
            } else {
                safe * 32767f
            }

        return scaled
            .toInt()
            .coerceIn(
                -32768,
                32767,
            )
            .toShort()
    }

    private fun writeAscii(
        output: FileOutputStream,
        value: String,
    ) {
        output.write(
            value.toByteArray(
                Charsets.US_ASCII
            )
        )
    }

    private fun writeIntLE(
        output: FileOutputStream,
        value: Int,
    ) {
        output.write(
            value and 0xFF
        )

        output.write(
            (value ushr 8) and 0xFF
        )

        output.write(
            (value ushr 16) and 0xFF
        )

        output.write(
            (value ushr 24) and 0xFF
        )
    }

    private fun writeShortLE(
        output: FileOutputStream,
        value: Int,
    ) {
        output.write(
            value and 0xFF
        )

        output.write(
            (value ushr 8) and 0xFF
        )
    }

    private fun MediaFormat.getIntegerSafely(
        key: String,
    ): Int? {
        return runCatching {
            getInteger(key)
        }.getOrNull()
    }

    private data class DecodedPcm(
        val sampleRate: Int,
        val channels: Int,
        val pcm: FloatArray,
    )

    private data class StereoPcm(
        val sampleRate: Int,
        val left: FloatArray,
        val right: FloatArray,
    )

    private class FloatArrayAccumulator(
        initialCapacity: Int = 16_384,
    ) {

        private var values =
            FloatArray(initialCapacity)

        var size: Int = 0
            private set

        fun add(value: Float) {

            ensureCapacity(
                size + 1
            )

            values[size++] =
                value
        }

        fun toArray(): FloatArray =
            values.copyOf(size)

        private fun ensureCapacity(
            required: Int,
        ) {

            if (required <= values.size) {
                return
            }

            var capacity =
                values.size

            while (capacity < required) {

                capacity =
                    (capacity * 2)
                        .coerceAtMost(
                            Int.MAX_VALUE - 8
                        )

                if (
                    capacity < required &&
                    capacity ==
                    Int.MAX_VALUE - 8
                ) {
                    throw OutOfMemoryError(
                        "Decoded PCM is too large"
                    )
                }
            }

            values =
                values.copyOf(
                    capacity
                )
        }
    }
}
