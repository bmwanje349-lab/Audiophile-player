package com.example.musicplayer.vocalremoverui

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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

    private var audioRenderRepository: AudioRenderRepository? = null
    private var vocalRemoverUi: VocalRemoverUi? = null

    private var processedTrackTitle = "Instrumental copy"

    companion object {
        const val EXTRA_TRACK_URI = "extra_track_uri"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        private const val TAG = "VocalRemoverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val ui = VocalRemoverUi(this)
            vocalRemoverUi = ui

            val root = FrameLayout(this)
            root.addView(ui.build(root))
            setContentView(root)
        } catch (throwable: Throwable) {
            Log.e(TAG, "AI Vocal Remover failed to initialize", throwable)
            showInitializationError(throwable)
        }
    }

    private fun showInitializationError(throwable: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(0xFF0A0C0F.toInt())
        }

        root.addView(
            TextView(this).apply {
                text = "AI Vocal Remover could not open"
                setTextColor(0xFFF2F5F7.toInt())
                textSize = 22f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        val cause = generateSequence(throwable) { it.cause }.lastOrNull()
            ?: throwable

        root.addView(
            TextView(this).apply {
                text = buildString {
                    append(cause.javaClass.simpleName)
                    val message = cause.message?.takeIf { it.isNotBlank() }
                    if (message != null) {
                        append(": ")
                        append(message)
                    }
                }
                setTextColor(0xFFA7AFBA.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(16))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        root.addView(
            TextView(this).apply {
                text = "The failure was caught before audio processing started. The app can remain open while the problem is diagnosed."
                setTextColor(0xFF737C88.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        root.addView(
            Button(this).apply {
                text = "Back"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(-2, dp(48)).apply {
                topMargin = dp(20)
                gravity = Gravity.CENTER_HORIZONTAL
            },
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

    override fun renderCurrentTrack(
        depth: Float,
        focus: Float,
        transientProtection: Float,
        dryWet: Float,
        stemGainDb: Float,
        outputGainDb: Float,
        ceilingDb: Float,
        onProgress: (Float) -> Unit,
        onMdxChunks: (Long) -> Unit,
        onReady: (Uri) -> Unit,
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

        val sourceTitle =
            source.title
                .takeIf { it.isNotBlank() }
                ?: "Track"

        val titleSuffix = " — Instrumental"
        processedTrackTitle = sourceTitle + titleSuffix

        val repository =
            runCatching {
                audioRenderRepository ?: AudioRenderRepository(this).also {
                    audioRenderRepository = it
                }
            }.getOrElse { throwable ->
                onError(
                    IllegalStateException(
                        "Unable to start the audio renderer",
                        throwable,
                    ),
                )
                return
            }

        runCatching {
            repository.renderVocalRemovalToWav(
                uri = source.uri,
                titleSuffix = titleSuffix,
                pipelineFactory = { sampleRate ->
                    com.bmwanje.audiophile.vocalremover.VocalRemoverPipeline(
                        this,
                        sampleRate,
                    )
                },
                configurePipeline = { pipeline ->
                    pipeline.setDepth(depth)
                    pipeline.setFocus(focus)
                    pipeline.setTransientProtection(transientProtection)
                    pipeline.setDryWet(dryWet)
                    pipeline.setStemGainDb(stemGainDb)
                    pipeline.setOutputGainDb(outputGainDb)
                    pipeline.setCeilingDb(ceilingDb)
                },
                onProgress = onProgress,
                onMdxChunks = onMdxChunks,
                onReady = onReady,
                onError = onError,
            )
        }.onFailure { throwable ->
            onError(
                IllegalStateException(
                    "Unable to start the vocal-removal render",
                    throwable,
                ),
            )
        }
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
        vocalRemoverUi?.close()
        audioRenderRepository?.close()

        val future = controllerFuture
        controllerFuture = null

        if (future != null && !future.isDone) {
            future.cancel(true)
        }

        controller?.release()
        controller = null

        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
