package com.example.musicplayer.vocalremoverui

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.musicplayer.PlaybackService
import com.google.common.util.concurrent.ListenableFuture

class VocalRemoverActivity :
    AppCompatActivity(),
    VocalRemoverUi.Host {

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var audioRenderRepository: AudioRenderRepository
    private lateinit var vocalRemoverUi: VocalRemoverUi

    private var processedTrackTitle = "Instrumental copy"

    companion object {
        const val EXTRA_TRACK_URI = "extra_track_uri"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioRenderRepository =
            AudioRenderRepository(this)

        vocalRemoverUi =
            VocalRemoverUi(this)

        val root = FrameLayout(this)

        root.addView(
            vocalRemoverUi.build(root)
        )

        setContentView(root)
    }

    override fun currentTrack():
        VocalRemoverUi.TrackSource? {

        val uriString =
            intent.getStringExtra(EXTRA_TRACK_URI)
                ?: return null

        val uri =
            runCatching { Uri.parse(uriString) }
                .getOrNull()
                ?: return null

        val title =
            intent.getStringExtra(EXTRA_TRACK_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: "Current track"

        return VocalRemoverUi.TrackSource(
            title = title,
            uri = uri,
        )
    }

    override fun decodeCurrentTrackToStereoPcm(
        onReady: (VocalRemoverUi.PcmTrack) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val source =
            currentTrack()
                ?: run {
                    onError(
                        IllegalStateException(
                            "No current track is available"
                        )
                    )
                    return
                }

        audioRenderRepository.decodeToStereoPcm(
            uri = source.uri,
            onReady = { sampleRate, stereo ->
                onReady(
                    VocalRemoverUi.PcmTrack(
                        sampleRate = sampleRate,
                        stereo = stereo,
                    )
                )
            },
            onError = onError,
        )
    }

    override fun encodeProcessedTrack(
        pcm: VocalRemoverUi.PcmTrack,
        titleSuffix: String,
        onReady: (Uri) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val sourceTitle =
            currentTrack()?.title
                ?: "Track"

        processedTrackTitle =
            sourceTitle + titleSuffix

        audioRenderRepository.encodeStereoPcmToWav(
            pcm = pcm.stereo,
            sampleRate = pcm.sampleRate,
            titleSuffix = titleSuffix,
            onReady = onReady,
            onError = onError,
        )
    }

    override fun playProcessedUri(
        uri: Uri,
    ) {
        fun playWith(player: MediaController) {
            val mediaItem =
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(processedTrackTitle)
                            .setArtist("Audiophile Player")
                            .setAlbumTitle("AI Vocal Remover")
                            .build()
                    )
                    .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }

        controller?.let {
            runCatching { playWith(it) }
            return
        }

        val future =
            runCatching {
                MediaController.Builder(
                    this,
                    SessionToken(
                        this,
                        ComponentName(
                            this,
                            PlaybackService::class.java,
                        ),
                    ),
                ).buildAsync()
            }.getOrNull()
                ?: return

        controllerFuture = future

        future.addListener(
            {
                runCatching {
                    val connected = future.get()

                    if (controllerFuture !== future) {
                        connected.release()
                        return@runCatching
                    }

                    controller = connected
                    playWith(connected)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun openBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroy() {
        vocalRemoverUi.close()
        audioRenderRepository.close()

        val future =
            controllerFuture

        controllerFuture = null

        if (future != null && !future.isDone) {
            future.cancel(true)
        }

        controller?.release()
        controller = null

        super.onDestroy()
    }



}
