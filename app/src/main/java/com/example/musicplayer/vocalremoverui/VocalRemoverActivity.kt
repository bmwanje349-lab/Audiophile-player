package com.example.musicplayer.vocalremoverui

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.musicplayer.PlaybackService
import com.example.musicplayer.TrackItem
import com.google.common.util.concurrent.ListenableFuture

class VocalRemoverActivity :
    AppCompatActivity(),
    VocalRemoverUi.Host {

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var audioRenderRepository: AudioRenderRepository
    private lateinit var vocalRemoverUi: VocalRemoverUi

    private var processedTrackTitle = "Instrumental copy"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioRenderRepository =
            AudioRenderRepository(this)

        vocalRemoverUi =
            VocalRemoverUi(this)

        setContentView(
            vocalRemoverUi.build(this)
        )

        connectController()
    }

    override fun currentTrack():
        VocalRemoverUi.TrackSource? {

        val item =
            controller?.currentMediaItem
                ?: return null

        val uri =
            item.localConfiguration?.uri
                ?: return null

        val title =
            item.mediaMetadata.title
                ?.toString()
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
        val player =
            controller
                ?: throw IllegalStateException(
                    "Media controller is not connected"
                )

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

    private fun connectController() {
        if (
            controller != null ||
            controllerFuture != null
        ) {
            return
        }

        val token =
            SessionToken(
                this,
                ComponentName(
                    this,
                    PlaybackService::class.java,
                ),
            )

        val future =
            MediaController.Builder(
                this,
                token,
            ).buildAsync()

        controllerFuture = future

        future.addListener(
            {
                runCatching {

                    val connected =
                        future.get()

                    if (
                        controllerFuture !== future
                    ) {
                        connected.release()
                        return@runCatching
                    }

                    controller =
                        connected
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
}
