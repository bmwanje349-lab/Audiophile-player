package com.example.musicplayer.livekaraoke

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bmwanje.audiophile.vocalremover.NativeVocalRemover
import com.example.musicplayer.vocalremoverui.VocalRemoverActivity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveKaraokeAndroidSmokeTest {

    private val context: Context
        get() = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

    @Test
    fun vocalRemoverActivityLaunchesWithoutCrash() {
        ActivityScenario.launch(VocalRemoverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window != null)
            }
        }
    }

    @Test
    fun nativeVocalRemoverCreatesAndProcessesFiniteAudio() {
        val sampleRate = 44_100
        val count = 8_192
        val mixL = FloatArray(count)
        val mixR = FloatArray(count)
        val vocalL = FloatArray(count)
        val vocalR = FloatArray(count)
        val outL = FloatArray(count)
        val outR = FloatArray(count)

        NativeVocalRemover(sampleRate).use { native ->
            native.reset()
            native.setNeuralStemMode(false)
            native.processBlock(
                mixL,
                mixR,
                vocalL,
                vocalR,
                outL,
                outR,
                count,
            )
        }

        assertEquals(count, outL.size)
        assertTrue(outL.all { it.isFinite() })
        assertTrue(outR.all { it.isFinite() })
    }

    @Test
    fun fastLivePathReachesAudioTrackPlaybackWithoutFailure() {
        val wav =
            File(
                context.cacheDir,
                "live-karaoke-smoke.wav",
            )

        writeTestWav(
            file = wav,
            sampleRate = 44_100,
            durationSeconds = 1,
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

            errorRef.get()?.let { error ->
                throw AssertionError(
                    "Fast Live failed",
                    error,
                )
            }
        } finally {
            engine.close()
            wav.delete()
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
                output.write(
                    value.toByteArray(
                        Charsets.US_ASCII
                    )
                )
            }

            fun intLE(value: Int) {
                output.write(
                    ByteBuffer
                        .allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(value)
                        .array()
                )
            }

            fun shortLE(value: Int) {
                output.write(
                    ByteBuffer
                        .allocate(2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(value.toShort())
                        .array()
                )
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
            repeat(frames) {
                output.write(frame)
            }
        }
    }
}
