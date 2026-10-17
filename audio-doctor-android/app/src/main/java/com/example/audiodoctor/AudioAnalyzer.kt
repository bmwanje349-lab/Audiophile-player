package com.example.audiodoctor

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object AudioAnalyzer {
    private const val FFT_SIZE = 8192
    private const val WAVE_BINS = 1200
    private const val MAX_SPECTRUM_WINDOWS = 10

    private data class SourceInfo(
        val name: String,
        val size: Long,
        val title: String?,
        val artist: String?,
        val album: String?,
        val bitRate: Long?,
        val durationMs: Long?,
        val cover: Boolean
    )

    fun analyze(context: Context, uri: Uri, onProgress: (Int) -> Unit): AudioReport {
        val source = readSourceInfo(context, uri)
        val extractor = MediaExtractor()
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Android could not open the selected audio file.")
            descriptor.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                var audioTrack = -1
                var inputFormat: MediaFormat? = null
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrack = index
                        inputFormat = format
                        break
                    }
                }
                if (audioTrack < 0 || inputFormat == null) {
                    throw IllegalArgumentException("No decodable audio stream was found in this file.")
                }
                extractor.selectTrack(audioTrack)
                val trackFormat = inputFormat
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalArgumentException("Audio codec metadata is missing.")
                val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    trackFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    (source.durationMs ?: 0L) * 1000L
                }
                val decoder = MediaCodec.createDecoderByType(mime)
                var accumulator: PcmAccumulator? = null
                var inputFinished = false
                var outputFinished = false
                var outputFormat: MediaFormat? = null
                var idleLoops = 0
                try {
                    decoder.configure(trackFormat, null, null, 0)
                    decoder.start()
                    val info = MediaCodec.BufferInfo()
                    while (!outputFinished) {
                        var madeProgress = false
                        if (!inputFinished) {
                            val inputIndex = decoder.dequeueInputBuffer(10_000)
                            if (inputIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inputIndex)
                                    ?: throw IllegalStateException("Decoder input buffer was unavailable.")
                                inputBuffer.clear()
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputFinished = true
                                } else {
                                    val presentationTime = extractor.sampleTime.coerceAtLeast(0L)
                                    decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                                    extractor.advance()
                                }
                                madeProgress = true
                            }
                        }

                        val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                        when {
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                outputFormat = decoder.outputFormat
                                accumulator = createAccumulatorIfPossible(
                                    accumulator, outputFormat, mime, durationUs, source, ::readPcmSample
                                )
                                madeProgress = true
                            }
                            outputIndex >= 0 -> {
                                val format = outputFormat ?: decoder.outputFormat.also { outputFormat = it }
                                accumulator = createAccumulatorIfPossible(
                                    accumulator, format, mime, durationUs, source, ::readPcmSample
                                )
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                if (info.size > 0 && outputBuffer != null) {
                                    val acc = accumulator
                                        ?: throw IllegalStateException("Audio decoder produced samples before its output format was available.")
                                    val slice = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    slice.position(info.offset)
                                    slice.limit(info.offset + info.size)
                                    acc.accept(slice.slice().order(ByteOrder.LITTLE_ENDIAN), info.size, format)
                                    val fraction = if (acc.expectedFrames > 0L) {
                                        (acc.frameCount.toDouble() / acc.expectedFrames.toDouble()).coerceIn(0.0, 1.0)
                                    } else 0.0
                                    onProgress((fraction * 100.0).toInt().coerceIn(1, 99))
                                }
                                outputFinished = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                decoder.releaseOutputBuffer(outputIndex, false)
                                madeProgress = true
                            }
                        }
                        if (madeProgress) {
                            idleLoops = 0
                        } else {
                            idleLoops++
                            if (inputFinished && idleLoops > 100) {
                                throw IllegalStateException("The audio decoder stopped before it reached the end of the track.")
                            }
                        }
                    }
                } finally {
                    try { decoder.stop() } catch (_: Exception) { }
                    decoder.release()
                }
                onProgress(100)
                val acc = accumulator ?: throw IllegalArgumentException("The selected file decoded no audio samples.")
                return acc.finish(source, mime)
            }
        } finally {
            extractor.release()
        }
    }

    private fun readSourceInfo(context: Context, uri: Uri): SourceInfo {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "selected-audio"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) cursor.getString(nameColumn)?.let { name = it }
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
        } catch (_: Exception) { }
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var bitrate: Long? = null
        var duration: Long? = null
        var cover = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            cover = retriever.embeddedPicture != null
        } catch (_: Exception) {
            // Some document providers expose a decoder stream but not metadata.
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
        if (size < 0L) size = -1L
        return SourceInfo(name, size, title, artist, album, bitrate, duration, cover)
    }

    private fun createAccumulatorIfPossible(
        current: PcmAccumulator?,
        format: MediaFormat,
        mime: String,
        durationUs: Long,
        source: SourceInfo,
        sampleReader: (ByteBuffer, Int) -> Float
    ): PcmAccumulator? {
        if (current != null) return current
        if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE) || !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) return null
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (sampleRate <= 0 || channels <= 0) return null
        val expectedFrames = if (durationUs > 0L) (durationUs * sampleRate / 1_000_000L) else 0L
        return PcmAccumulator(sampleRate, channels, expectedFrames, source.size, mime, sampleReader)
    }

    private fun readPcmSample(buffer: ByteBuffer, encoding: Int): Float {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-4.0f, 4.0f)
            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128.0f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var value = (buffer.get().toInt() and 0xff) or
                    ((buffer.get().toInt() and 0xff) shl 8) or
                    ((buffer.get().toInt() and 0xff) shl 16)
                if ((value and 0x800000) != 0) value = value or -0x1000000
                value / 8388608.0f
            }
            AudioFormat.ENCODING_PCM_32BIT -> (buffer.int / 2147483648.0).toFloat()
            else -> buffer.short / 32768.0f
        }
    }

    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = if (y.isFinite()) y else 0.0
            return y1
        }
    }

    private fun highPass(sampleRate: Int): Biquad {
        val frequency = 38.135470876
        val q = 0.500327
        val w = 2.0 * Math.PI * frequency / sampleRate
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(((1.0 + c) / 2.0) / a0, (-(1.0 + c)) / a0,
            ((1.0 + c) / 2.0) / a0, (-2.0 * c) / a0, (1.0 - alpha) / a0)
    }

    private fun highShelf(sampleRate: Int): Biquad {
        val frequency = 1681.974450955533
        val gainDb = 3.999843853973347
        val q = 0.7071752369554196
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * Math.PI * frequency / sampleRate
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val rootA = sqrt(a)
        val b0 = a * ((a + 1.0) + (a - 1.0) * c + 2.0 * rootA * alpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * c)
        val b2 = a * ((a + 1.0) + (a - 1.0) * c - 2.0 * rootA * alpha)
        val a0 = (a + 1.0) - (a - 1.0) * c + 2.0 * rootA * alpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * c)
        val a2 = (a + 1.0) - (a - 1.0) * c - 2.0 * rootA * alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private class PcmAccumulator(
        val sampleRate: Int,
        private val decoderChannels: Int,
        val expectedFrames: Long,
        private val fileSize: Long,
        private val mime: String,
        private val sampleReader: (ByteBuffer, Int) -> Float
    ) {
        val frameCount: Long get() = frames
        private val metricChannels = if (decoderChannels <= 1) 1 else 2
        private val waveMin = DoubleArray(WAVE_BINS) { Double.POSITIVE_INFINITY }
        private val waveMax = DoubleArray(WAVE_BINS) { Double.NEGATIVE_INFINITY }
        private val spectrumSum = DoubleArray(FFT_SIZE / 2 + 1)
        private val spectrumWindow = DoubleArray(FFT_SIZE)
        private val spectrumStride = max(FFT_SIZE.toLong(), if (expectedFrames > 0) expectedFrames / MAX_SPECTRUM_WINDOWS else sampleRate.toLong() * 2L)
        private var nextSpectrumFrame = 0L
        private var spectrumWindowFill = 0
        private var spectrumWindowCount = 0
        private var frames = 0L
        private var peak = 0.0
        private var truePeak = 0.0
        private var rmsSquaresLeft = 0.0
        private var rmsSquaresRight = 0.0
        private var monoSquares = 0.0
        private var sumLeft = 0.0
        private var sumRight = 0.0
        private var sumLeftSquares = 0.0
        private var sumRightSquares = 0.0
        private var sumCross = 0.0
        private var clipped = 0L
        private var plateauPairs = 0L
        private var previousLeft = 0.0
        private var previousRight = 0.0
        private var hasPrevious = false
        private var firstActiveFrame = -1L
        private var lastActiveFrame = -1L
        private val silenceThreshold = 0.001
        private val rmsWindowFrames = max(1, sampleRate / 10)
        private var rmsWindowCount = 0
        private var rmsWindowSquares = 0.0
        private val levelWindows = ArrayList<Double>()
        private var kWeightingLeft = arrayOf(highShelf(sampleRate), highPass(sampleRate))
        private var kWeightingRight = arrayOf(highShelf(sampleRate), highPass(sampleRate))
        private var loudWindowFrames = 0
        private var loudWindowLeft = 0.0
        private var loudWindowRight = 0.0
        private val loudWindowEnergies = ArrayList<Double>()
        private var loudBlockCount = 0
        private var loudBlockLeft = 0.0
        private var loudBlockRight = 0.0

        fun accept(buffer: ByteBuffer, byteCount: Int, format: MediaFormat) {
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> 2
            }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else decoderChannels
            if (channels <= 0) return
            val totalFrames = min(byteCount, buffer.remaining()) / (bytesPerSample * channels)
            var frame = 0
            while (frame < totalFrames) {
                var left = 0.0
                var right = 0.0
                for (channel in 0 until channels) {
                    val value = sampleReader(buffer, encoding).toDouble()
                    if (channel == 0) left = value
                    else if (channel == 1) right = value
                }
                if (channels == 1) right = left
                acceptFrame(left, right)
                frame++
            }
        }

        private fun acceptFrame(left: Double, right: Double) {
            val mono = (left + right) * 0.5
            peak = max(peak, max(abs(left), abs(right)))
            if (hasPrevious) {
                for (step in 1..3) {
                    val fraction = step / 4.0
                    truePeak = max(truePeak, abs(previousLeft + (left - previousLeft) * fraction))
                    if (metricChannels > 1) truePeak = max(truePeak, abs(previousRight + (right - previousRight) * fraction))
                }
                if (abs(left) >= 0.985 && abs(previousLeft) >= 0.985 && abs(left - previousLeft) < 1.0e-7) plateauPairs++
                if (metricChannels > 1 && abs(right) >= 0.985 && abs(previousRight) >= 0.985 && abs(right - previousRight) < 1.0e-7) plateauPairs++
            }
            truePeak = max(truePeak, max(abs(left), abs(right)))
            if (abs(left) >= 0.999) clipped++
            if (metricChannels > 1 && abs(right) >= 0.999) clipped++
            previousLeft = left; previousRight = right; hasPrevious = true

            rmsSquaresLeft += left * left
            rmsSquaresRight += right * right
            monoSquares += mono * mono
            sumLeft += left; sumRight += right
            sumLeftSquares += left * left
            if (metricChannels > 1) {
                sumRightSquares += right * right
                sumCross += left * right
            }
            if (firstActiveFrame < 0L && (abs(left) > silenceThreshold || abs(right) > silenceThreshold)) firstActiveFrame = frames
            if (abs(left) > silenceThreshold || abs(right) > silenceThreshold) lastActiveFrame = frames

            if (expectedFrames > 0L) {
                val bin = ((frames.toDouble() / expectedFrames.toDouble()) * WAVE_BINS).toInt().coerceIn(0, WAVE_BINS - 1)
                waveMin[bin] = min(waveMin[bin], mono)
                waveMax[bin] = max(waveMax[bin], mono)
            } else {
                val bin = (frames / max(1L, sampleRate.toLong() / 20L)).toInt().coerceIn(0, WAVE_BINS - 1)
                waveMin[bin] = min(waveMin[bin], mono)
                waveMax[bin] = max(waveMax[bin], mono)
            }

            if (spectrumWindowFill > 0 || (spectrumWindowCount < MAX_SPECTRUM_WINDOWS && frames >= nextSpectrumFrame)) {
                val idx = spectrumWindowFill
                spectrumWindow[idx] = mono * (0.5 - 0.5 * cos(2.0 * Math.PI * idx / (FFT_SIZE - 1)))
                spectrumWindowFill++
                if (spectrumWindowFill == FFT_SIZE) {
                    val real = spectrumWindow.copyOf()
                    val imaginary = DoubleArray(FFT_SIZE)
                    AnalysisMath.fft(real, imaginary)
                    for (bin in spectrumSum.indices) spectrumSum[bin] += hypot(real[bin], imaginary[bin])
                    spectrumWindowCount++
                    spectrumWindowFill = 0
                    nextSpectrumFrame = frames + spectrumStride
                }
            }

            val kLeft = kWeightingLeft[1].process(kWeightingLeft[0].process(left))
            val kRight = if (metricChannels > 1) kWeightingRight[1].process(kWeightingRight[0].process(right)) else 0.0
            loudWindowLeft += kLeft * kLeft
            loudWindowRight += kRight * kRight
            loudWindowFrames++
            if (loudWindowFrames >= rmsWindowFrames) {
                loudWindowEnergies.add((loudWindowLeft + loudWindowRight) / max(1, loudWindowFrames))
                loudWindowLeft = 0.0
                loudWindowRight = 0.0
                loudWindowFrames = 0
            }

            rmsWindowSquares += left * left + if (metricChannels > 1) right * right else 0.0
            rmsWindowCount++
            if (rmsWindowCount >= rmsWindowFrames) {
                val energy = rmsWindowSquares / (rmsWindowCount * metricChannels)
                val db = AnalysisMath.dbfs(sqrt(max(0.0, energy)))
                if (db > -70.0) levelWindows.add(db)
                rmsWindowSquares = 0.0
                rmsWindowCount = 0
            }
            frames++
        }

        private fun integratedLufsEstimate(): Double? {
            if (loudWindowEnergies.size < 4) return null
            val windows = ArrayList<Double>()
            for (i in 0..loudWindowEnergies.size - 4) {
                windows.add((loudWindowEnergies[i] + loudWindowEnergies[i + 1] +
                    loudWindowEnergies[i + 2] + loudWindowEnergies[i + 3]) / 4.0)
            }
            val absoluteGate = 10.0.pow((-70.0 + 0.691) / 10.0)
            val absolutePassed = windows.filter { it >= absoluteGate && it > 1.0e-15 }
            if (absolutePassed.isEmpty()) return null
            val ungatedMean = absolutePassed.average()
            val preliminary = -0.691 + 10.0 * log10(ungatedMean)
            val relativeGate = 10.0.pow(((preliminary - 10.0) + 0.691) / 10.0)
            val threshold = max(absoluteGate, relativeGate)
            val gated = absolutePassed.filter { it >= threshold }
            if (gated.isEmpty()) return null
            return -0.691 + 10.0 * log10(gated.average())
        }

        fun finish(source: SourceInfo, mime: String): AudioReport {
            if (frames <= 0L) throw IllegalArgumentException("The decoder returned an empty audio stream.")
            val rmsEnergy = (rmsSquaresLeft + if (metricChannels > 1) rmsSquaresRight else 0.0) / (frames * metricChannels)
            val rmsDb = AnalysisMath.dbfs(sqrt(max(0.0, rmsEnergy)))
            val peakDb = AnalysisMath.dbfs(peak)
            val truePeakDb = AnalysisMath.dbfs(truePeak)
            val rmsWindowsSorted = levelWindows.sorted()
            val spread = if (rmsWindowsSorted.size >= 4) {
                val lowIndex = (rmsWindowsSorted.size * 0.10).toInt().coerceIn(0, rmsWindowsSorted.lastIndex)
                val highIndex = (rmsWindowsSorted.size * 0.95).toInt().coerceIn(0, rmsWindowsSorted.lastIndex)
                rmsWindowsSorted[highIndex] - rmsWindowsSorted[lowIndex]
            } else null
            val correlation = if (metricChannels > 1) AnalysisMath.correlation(
                sumLeft, sumRight, sumLeftSquares, sumRightSquares, sumCross, frames
            ) else null
            val monoRms = sqrt(max(0.0, monoSquares / frames))
            val stereoRms = sqrt(max(0.0, (sumLeftSquares + sumRightSquares) / (2.0 * frames)))
            val monoChange = if (metricChannels > 1 && stereoRms > 1.0e-12) {
                AnalysisMath.dbfs(monoRms) - AnalysisMath.dbfs(stereoRms)
            } else null
            val duration = frames.toDouble() / sampleRate
            val leading = if (firstActiveFrame < 0L) duration else firstActiveFrame.toDouble() / sampleRate
            val trailing = if (lastActiveFrame < 0L) duration else max(0.0, (frames - 1L - lastActiveFrame).toDouble() / sampleRate)
            val spectrumMagnitudes = DoubleArray(spectrumSum.size) { index ->
                if (spectrumWindowCount > 0) spectrumSum[index] / spectrumWindowCount else 0.0
            }
            val spectrumDb = spectrumMagnitudes.map { (20.0 * log10(max(it, 1.0e-12))).toFloat() }
            val key = AnalysisMath.estimateKey(spectrumMagnitudes, sampleRate, FFT_SIZE)
            val bpm = AnalysisMath.estimateTempo(levelWindows.map { 10.0.pow(it / 20.0) }, 10.0)
            val cutoff = AnalysisMath.estimateSpectralCutoff(spectrumMagnitudes, sampleRate, FFT_SIZE)
            val mins = waveMin.map { if (it.isFinite()) it.toFloat() else 0f }
            val maxs = waveMax.map { if (it.isFinite()) it.toFloat() else 0f }
            val warnings = ArrayList<String>()
            if (decoderChannels > 2) warnings.add("Multichannel source: stereo metrics use the first two decoded channels only.")
            if (decoderChannels == 1) warnings.add("Mono source: stereo correlation and stereo fold-down metrics are not applicable.")
            if (spectrumWindowCount == 0) warnings.add("Track was too short to fill an 8192-sample FFT window.")
            if (source.size < 0L) warnings.add("File size was not exposed by the document provider.")
            warnings.add("True peak is estimated by 4x linear interpolation, not a standards-certified oversampled peak meter.")
            warnings.add("Integrated loudness is an EBU-style K-weighted/gated estimate, not a certified BS.1770 conformance result.")
            warnings.add("Tempo, musical key and high-frequency cutoff are heuristic estimates; confirm by listening or specialist analysis.")
            val title = source.title ?: inferTitle(source.name)
            val artist = source.artist ?: inferArtist(source.name)
            val effectiveBitrate = source.bitRate ?: if (source.size > 0L && duration > 0.0) {
                (source.size.toDouble() * 8.0 / duration).toLong()
            } else null
            return AudioReport(
                fileName = source.name,
                fileSizeBytes = source.size,
                codec = mime.substringAfter('/', mime),
                durationSeconds = duration,
                sampleRateHz = sampleRate,
                sourceChannels = decoderChannels,
                bitRateBps = effectiveBitrate,
                titleTag = title,
                artistTag = artist,
                albumTag = source.album,
                coverArtPresent = source.cover,
                samplePeakDbfs = peakDb,
                truePeakEstimateDbtp = truePeakDb,
                rmsLevelDbfs = rmsDb,
                integratedLufsEstimate = integratedLufsEstimate(),
                crestFactorDb = peakDb - rmsDb,
                clippedSampleCount = clipped,
                clippedSampleRatio = clipped.toDouble() / max(1L, frames * metricChannels).toDouble(),
                hardClipPlateauPairs = plateauPairs,
                dcOffsetLeft = sumLeft / frames,
                dcOffsetRight = if (metricChannels > 1) sumRight / frames else sumLeft / frames,
                leadingSilenceSeconds = leading,
                trailingSilenceSeconds = trailing,
                shortTermLevelSpreadDb = spread,
                stereoCorrelation = correlation,
                monoSumLevelChangeDb = monoChange,
                tempoBpmEstimate = bpm,
                musicalKeyEstimate = key?.name,
                keyConfidence = key?.confidence,
                estimatedSpectralCutoffHz = cutoff,
                decodeWarnings = warnings,
                waveformMinimum = mins,
                waveformMaximum = maxs,
                spectrumDb = spectrumDb,
                notes = listOf(
                    "This app analyzes the selected file locally and does not upload it.",
                    "An MP3 exported elsewhere may already contain clipping or lossy compression artifacts; analysis cannot reconstruct information absent from the source.",
                    "No audio file is modified or overwritten by this analysis-only Android app."
                )
            )
        }
    }

    private fun inferTitle(filename: String): String {
        val stem = filename.substringBeforeLast('.', filename)
            .replace(Regex("(?i)[_ ]?(256k|320k|192k|128k|kbps)$"), "")
            .replace(Regex("(?i)[_ ]*(official)?[_ ]*(music[_ ]*)?(video|audio|lyrics?)$"), "")
            .replace('_', ' ').trim(' ', '-')
        return if (stem.contains(" - ")) stem.substringAfter(" - ").trim() else stem
    }

    private fun inferArtist(filename: String): String? {
        val stem = filename.substringBeforeLast('.', filename).replace('_', ' ').trim()
        return if (stem.contains(" - ")) stem.substringBefore(" - ").trim().takeIf { it.isNotBlank() } else null
    }
}
