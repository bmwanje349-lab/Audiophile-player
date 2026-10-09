package com.example.musicplayer.livekaraoke

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bmwanje.audiophile.vocalremover.LiveMdxOnnxVocalModelRunner
import com.bmwanje.audiophile.vocalremover.LiveStreamingVocalRemover
import com.bmwanje.audiophile.vocalremover.MdxModelManager
import com.bmwanje.audiophile.vocalremover.MdxModelSpec
import com.bmwanje.audiophile.vocalremover.MdxSeparatorCore
import com.bmwanje.audiophile.vocalremover.NativeVocalRemover
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import com.example.musicplayer.vocalremoverui.VocalRemoverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LiveKaraokeAndroidInstrumentationTest {

    private val context: Context
        get() = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

    @Test
    fun vocalRemoverActivityLaunchesWithoutCrashing() {
        ActivityScenario.launch(VocalRemoverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window != null)
            }
        }
    }

    @Test
    fun fastLivePathReachesAudioTrackPlaybackWithoutFailure() {
        val wav = File(
            context.cacheDir,
            "live-karaoke-runtime-test.wav",
        )
        writeTestWav(
            file = wav,
            sampleRate = 44_100,
            durationSeconds = 2,
        )

        val playing = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val engine =
            LiveKaraokeEngine(
                context,
                object : LiveKaraokeEngine.Listener {
                    override fun onState(
                        state: LiveKaraokeEngine.State,
                        message: String,
                    ) {
                        if (state == LiveKaraokeEngine.State.PLAYING) {
                            playing.countDown()
                        }
                    }

                    override fun onProgress(
                        positionMs: Long,
                        durationMs: Long,
                    ) = Unit

                    override fun onError(error: Throwable) {
                        errorRef.compareAndSet(null, error)
                        playing.countDown()
                    }

                    override fun onCompleted() = Unit
                },
            )

        try {
            engine.startForTest(
                uri = Uri.fromFile(wav),
                forceDspFallback = true,
            )

            assertTrue(
                "Fast Live did not reach AudioTrack playback",
                playing.await(20, TimeUnit.SECONDS),
            )

            errorRef.get()?.let { throw AssertionError("Fast Live failed", it) }
        } finally {
            engine.close()
            wav.delete()
        }
    }

    @Test
    fun liveKaraokePeqAndWidenerChainPreservesFramesAndDrainsItsTail() {
        val sampleRate = 44_100
        val totalFrames = sampleRate / 2
        val blockFrames = 2_048
        val emitted = ArrayList<ShortArray>()
        val effects = LiveKaraokeAudioEffects(context, sampleRate)

        try {
            var frameStart = 0
            while (frameStart < totalFrames) {
                val frames = minOf(blockFrames, totalFrames - frameStart)
                val pcm = ShortArray(frames * 2)
                for (frame in 0 until frames) {
                    val absoluteFrame = frameStart + frame
                    pcm[frame * 2] =
                        (sin(2.0 * PI * 440.0 * absoluteFrame / sampleRate) * 12_000.0)
                            .toInt()
                            .toShort()
                    pcm[frame * 2 + 1] =
                        (sin(2.0 * PI * 660.0 * absoluteFrame / sampleRate) * 9_000.0)
                            .toInt()
                            .toShort()
                }

                effects.process(pcm) { output -> emitted += output }
                frameStart += frames
            }

            effects.finish { output -> emitted += output }

            assertEquals(
                "PEQ/widener chain dropped or duplicated PCM frames",
                totalFrames * 2,
                emitted.sumOf { it.size },
            )
            assertTrue(
                "PEQ/widener chain produced no audible-range PCM samples",
                emitted.any { block -> block.any { it != 0.toShort() } },
            )
            assertTrue(
                "PEQ/widener chain emitted a partial stereo frame",
                emitted.all { block -> block.size % 2 == 0 },
            )
        } finally {
            effects.close()
        }
    }

    @Test
    fun bundledMdxModelExecutesThroughAndroidOnnxRuntime() {
        val spec = MdxModelSpec.LIGHT_9482
        val file = MdxModelManager.ensureInstalled(context, spec)

        assertEquals(spec.fileSizeBytes, file.length())
        assertEquals(spec.sha256, MdxModelManager.sha256(file))

        val runner =
            LiveMdxOnnxVocalModelRunner(
                modelPath = file.absolutePath,
                modelSpec = spec,
                cpuThreads = 1,
            )

        try {
            val stft = com.bmwanje.audiophile.vocalremover.LiveMdxStft(spec)
            val n = stft.chunkSizeSamples()

            val left = FloatArray(n) { i ->
                (0.20 * sin(
                    2.0 * PI * 440.0 * i / com.bmwanje.audiophile.vocalremover.LiveMdxStft.SAMPLE_RATE
                )).toFloat()
            }
            val right = FloatArray(n) { i ->
                (0.16 * sin(
                    2.0 * PI * 550.0 * i / com.bmwanje.audiophile.vocalremover.LiveMdxStft.SAMPLE_RATE
                )).toFloat()
            }

            val output = runner.separateChunk(left, right)

            assertEquals(n, output.size)
            assertTrue(output.left.all { it.isFinite() })
            assertTrue(output.right.all { it.isFinite() })
            assertEquals(1L, runner.inferenceCount())
        } finally {
            runner.close()
        }
    }

    @Test
    fun realModelFeedsTheBoundedStreamingSeparatorOnAndroid() {
        val spec = MdxModelSpec.LIGHT_9482
        val file = MdxModelManager.ensureInstalled(context, spec)
        val runner =
            LiveMdxOnnxVocalModelRunner(
                modelPath = file.absolutePath,
                modelSpec = spec,
                cpuThreads = 1,
            )

        try {
            val stft = com.bmwanje.audiophile.vocalremover.LiveMdxStft(spec)
            val inputSamples =
                stft.generatedSamplesPerChunk() +
                    stft.edgeTrimSamples()

            val input =
                VocalSeparatorCore.Stereo(
                    left = FloatArray(inputSamples) { i ->
                        (0.18 * sin(
                            2.0 * PI * 330.0 * i / com.bmwanje.audiophile.vocalremover.LiveMdxStft.SAMPLE_RATE
                        )).toFloat()
                    },
                    right = FloatArray(inputSamples) { i ->
                        (0.15 * sin(
                            2.0 * PI * 495.0 * i / com.bmwanje.audiophile.vocalremover.LiveMdxStft.SAMPLE_RATE
                        )).toFloat()
                    },
                )

            val output = ArrayList<VocalSeparatorCore.Stereo>()
            val separator =
                MdxSeparatorCore.StreamingSeparator(
                    modelSpec = spec,
                    runner = runner,
                    emit = { output += it },
                )

            separator.push(input)
            separator.finish()

            val outputFrames = output.sumOf { it.size }
            assertEquals(input.size, outputFrames)
            assertTrue(runner.inferenceCount() >= 1L)
            assertTrue(
                output.all { block ->
                    block.left.all { it.isFinite() } &&
                        block.right.all { it.isFinite() }
                }
            )
        } finally {
            runner.close()
        }
    }


    @Test
    fun neuralStemModeSubtractsAnAlignedKnownVocalStem() {
        val sampleRate = 44_100
        val count = sampleRate * 2
        val instrumental =
            VocalSeparatorCore.Stereo(
                left = FloatArray(count) { i ->
                    (0.07 * kotlin.math.sin(2.0 * PI * 220.0 * i / sampleRate)).toFloat()
                },
                right = FloatArray(count) { i ->
                    (0.06 * kotlin.math.sin(2.0 * PI * 330.0 * i / sampleRate)).toFloat()
                },
            )
        val vocals =
            VocalSeparatorCore.Stereo(
                left = FloatArray(count) { i ->
                    (0.03 * kotlin.math.sin(2.0 * PI * 880.0 * i / sampleRate)).toFloat()
                },
                right = FloatArray(count) { i ->
                    (0.025 * kotlin.math.sin(2.0 * PI * 880.0 * i / sampleRate + 0.2)).toFloat()
                },
            )
        val mix =
            VocalSeparatorCore.Stereo(
                left = FloatArray(count) { i -> instrumental.left[i] + vocals.left[i] },
                right = FloatArray(count) { i -> instrumental.right[i] + vocals.right[i] },
            )

        val processed =
            NativeVocalRemover(sampleRate).use { native ->
                native.setDepth(1f)
                native.setDryWet(1f)
                native.setStemGainDb(0f)
                native.setOutputGainDb(0f)
                native.setCeilingDb(0f)
                native.renderOffline(mix, vocals, blockSize = 2_048)
            }

        var residualEnergy = 0.0
        var vocalEnergy = 0.0
        for (i in 0 until count) {
            val residualL = processed.left[i] - instrumental.left[i]
            val residualR = processed.right[i] - instrumental.right[i]
            residualEnergy += residualL * residualL + residualR * residualR
            vocalEnergy += vocals.left[i] * vocals.left[i] + vocals.right[i] * vocals.right[i]
        }

        assertTrue(
            "Neural stem mode did not sufficiently suppress the supplied vocal estimate",
            residualEnergy < vocalEnergy * 0.10,
        )
    }

    @Test
    fun pendingNeuralAudioTransfersExactlyOnceIntoFastLiveTimeline() {
        val sampleRate = 44_100
        val input =
            VocalSeparatorCore.Stereo(
                left = FloatArray(4_096) { i ->
                    (0.2 * sin(i * 0.03)).toFloat()
                },
                right = FloatArray(4_096) { i ->
                    (0.15 * sin(i * 0.021)).toFloat()
                },
            )

        val native = NativeVocalRemover(sampleRate)
        val neuralStub =
            object : MdxSeparatorCore.Runner {
                override fun separateChunk(
                    left: FloatArray,
                    right: FloatArray,
                ): com.bmwanje.audiophile.vocalremover.MdxStft.StereoChunk {
                    error("The handoff test must not need an MDX inference window")
                }
            }

        val handedOff =
            ArrayList<VocalSeparatorCore.Stereo>()

        val streaming =
            LiveStreamingVocalRemover(
                sampleRate = sampleRate,
                runner = neuralStub,
                modelSpec = MdxModelSpec.LIGHT_9482,
                native = native,
                emit = {},
            )

        val fallbackOutput =
            ArrayList<VocalSeparatorCore.Stereo>()

        val fallback =
            LiveDspFallbackProcessor(
                sampleRate = sampleRate,
                settings =
                    LiveKaraokeSettingsSnapshot(
                        depth = 1f,
                        focus = 0.5f,
                        transientProtection = 0.7f,
                        dryWet = 1f,
                        stemGainDb = 0f,
                        outputGainDb = 0f,
                        ceilingDb = -1f,
                    ),
                emit = { fallbackOutput += it },
            )

        try {
            streaming.push(input)

            streaming.drainPendingMixTo { block ->
                handedOff += block
                fallback.push(block)
            }
            fallback.finish()

            val handedFrames = handedOff.sumOf { it.size }
            val fallbackFrames = fallbackOutput.sumOf { it.size }

            assertEquals(input.size, handedFrames)
            assertEquals(input.size, fallbackFrames)

            val reconstructedL =
                FloatArray(handedFrames)
            val reconstructedR =
                FloatArray(handedFrames)
            var offset = 0
            for (block in handedOff) {
                System.arraycopy(block.left, 0, reconstructedL, offset, block.size)
                System.arraycopy(block.right, 0, reconstructedR, offset, block.size)
                offset += block.size
            }

            assertTrue(reconstructedL.contentEquals(input.left))
            assertTrue(reconstructedR.contentEquals(input.right))
        } finally {
            streaming.close()
            fallback.close()
        }
    }    
    private fun writeTestWav(
        file: File,
        sampleRate: Int,
        durationSeconds: Int,
    ) {
        val frames = sampleRate * durationSeconds
        val dataBytes = frames * 4

        FileOutputStream(file).use { output ->
            fun ascii(value: String) {
                output.write(value.toByteArray(Charsets.US_ASCII))
            }

            fun intLE(value: Int) {
                val b =
                    ByteBuffer
                        .allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(value)
                        .array()
                output.write(b)
            }

            fun shortLE(value: Int) {
                val b =
                    ByteBuffer
                        .allocate(2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(value.toShort())
                        .array()
                output.write(b)
            }

            ascii("RIFF")
            intLE(36 + dataBytes)
            ascii("WAVE")
            ascii("fmt ")
            intLE(16)
            shortLE(1)
            shortLE(2)
            intLE(sampleRate)
            intLE(sampleRate * 4)
            shortLE(4)
            shortLE(16)
            ascii("data")
            intLE(dataBytes)

            val frame = ByteArray(4)
            for (i in 0 until frames) {
                val sample =
                    (
                        kotlin.math.sin(
                            2.0 * PI * 440.0 * i / sampleRate
                        ) * 0.2 * 32767.0
                    )
                        .toInt()
                        .toShort()

                frame[0] = (sample.toInt() and 0xFF).toByte()
                frame[1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
                frame[2] = frame[0]
                frame[3] = frame[1]
                output.write(frame)
            }
        }
    }

}
