package com.example.musicplayer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackServiceAudioSmokeTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selectedLocalTrackActuallyAdvancesThroughPlaybackService() {
        val wav = File(context.cacheDir, "playback-service-smoke.wav")
        writeToneWav(wav, sampleRate = 44_100, seconds = 3)
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()

        try {
            val controller = future.get(20, TimeUnit.SECONDS)
            controller.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
            controller.prepare()
            controller.play()

            val deadline = SystemClock.elapsedRealtime() + 12_000L
            var furthestPositionMs = 0L
            while (SystemClock.elapsedRealtime() < deadline && furthestPositionMs < 500L) {
                controller.playerError?.let { error ->
                    throw AssertionError("Media3 reported a playback error: ${error.message}", error)
                }
                furthestPositionMs = maxOf(furthestPositionMs, controller.currentPosition)
                SystemClock.sleep(100L)
            }
            assertTrue(
                "Selected WAV never advanced through the playback service; state=${controller.playbackState}, error=${controller.playerError}",
                furthestPositionMs >= 500L,
            )
        } finally {
            MediaController.releaseFuture(future)
            wav.delete()
        }
    }

    private fun writeToneWav(file: File, sampleRate: Int, seconds: Int) {
        val frames = sampleRate * seconds
        val dataBytes = frames * 4
        FileOutputStream(file).use { output ->
            fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
            fun intLe(value: Int) {
                output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            }
            fun shortLe(value: Int) {
                output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            }
            ascii("RIFF"); intLe(36 + dataBytes); ascii("WAVE")
            ascii("fmt "); intLe(16); shortLe(1); shortLe(2)
            intLe(sampleRate); intLe(sampleRate * 4); shortLe(4); shortLe(16)
            ascii("data"); intLe(dataBytes)
            val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { frame ->
                val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * 7000.0).toInt().toShort()
                pcm.putShort(sample); pcm.putShort(sample)
            }
            output.write(pcm.array())
        }
    }
}