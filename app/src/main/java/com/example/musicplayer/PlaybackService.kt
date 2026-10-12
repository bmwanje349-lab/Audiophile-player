package com.example.musicplayer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.audio.ImmersionNative
import com.example.audio.ImmersionProcessor
import com.example.audio.ImmersionSettings
import com.example.audio.FloatToPcm16Processor
import com.example.audio.ToFloatProcessor
import com.example.peq.PeqAudioProcessor
import com.example.peq.PeqEngine

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var peqEngine: PeqEngine

    override fun onCreate() {
        super.onCreate()

        peqEngine = PeqEngine(16)
        DspRuntime.peqEngine = peqEngine

        EqSettingsStore(this).applyTo(peqEngine)
        ImmersionSettings.load(this)
        if (!ImmersionNative.loadAssets(assets)) {
            Log.e("AudiophilePlayer", "Failed to load the verified SADIE II HRIR/BRIR assets; immersion will use its safe fallback.")
        }

        val peqProcessor = PeqAudioProcessor(peqEngine)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink? {
                // DSP requires PCM processing. Offload is deliberately disabled.
                return DefaultAudioSink.Builder(context)
                    // Float end-to-end: avoids 16-bit quantisation/clipping between DSP stages.
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(ToFloatProcessor(), peqProcessor, ImmersionProcessor(), FloatToPcm16Processor()))
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    /**
     * Keep the service alive while playback is requested, including during
     * buffering. Player.isPlaying is false during buffering, so delegating
     * to the default isPlaying-based task-removal policy can stop a valid
     * stream and tear down the DSP engines prematurely.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!::player.isInitialized) {
            stopSelf()
            return
        }

        val keepPlaybackService =
            player.playWhenReady &&
                player.mediaItemCount > 0 &&
                player.playbackState != Player.STATE_ENDED

        if (!keepPlaybackService) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()

        DspRuntime.peqEngine = null

        if (::peqEngine.isInitialized) peqEngine.close()
        super.onDestroy()
    }
}

