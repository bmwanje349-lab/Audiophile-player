package com.example.musicplayer.livekaraoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Separate foreground service for live karaoke.
 *
 * The normal Media3 PlaybackService and offline AI Vocal Remover are left
 * untouched.
 */
class LiveKaraokeService : Service() {

    companion object {
        const val EXTRA_URI = "live_karaoke_uri"
        const val EXTRA_TITLE = "live_karaoke_title"
        const val EXTRA_POSITION_MS = "live_karaoke_position_ms"
        const val EXTRA_DEPTH = "live_karaoke_depth"
        const val EXTRA_FOCUS = "live_karaoke_focus"
        const val EXTRA_TRANSIENT = "live_karaoke_transient"
        const val EXTRA_DRY_WET = "live_karaoke_dry_wet"
        const val EXTRA_STEM_GAIN = "live_karaoke_stem_gain"
        const val EXTRA_OUTPUT_GAIN = "live_karaoke_output_gain"
        const val EXTRA_CEILING = "live_karaoke_ceiling"
        const val ACTION_STOP =
            "com.example.musicplayer.livekaraoke.STOP"

        private const val CHANNEL_ID = "live_karaoke"
        private const val NOTIFICATION_ID = 49_021
    }

    private val binder = LocalBinder()
    private val listeners =
        java.util.concurrent.CopyOnWriteArrayList<
            LiveKaraokeEngine.Listener
        >()

    private lateinit var engine: LiveKaraokeEngine
    private var trackTitle = "Live Karaoke"

    inner class LocalBinder : Binder() {
        fun service(): LiveKaraokeService = this@LiveKaraokeService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        engine =
            LiveKaraokeEngine(
                applicationContext,
                object : LiveKaraokeEngine.Listener {
                    override fun onState(
                        state: LiveKaraokeEngine.State,
                        message: String,
                    ) {
                        updateNotification(message)
                        for (listener in listeners) {
                            listener.onState(
                                state,
                                message,
                            )
                        }
                    }

                    override fun onProgress(
                        positionMs: Long,
                        durationMs: Long,
                    ) {
                        for (listener in listeners) {
                            listener.onProgress(
                                positionMs,
                                durationMs,
                            )
                        }
                    }

                    override fun onError(
                        error: Throwable,
                    ) {
                        updateNotification(
                            "Live karaoke error: " +
                                (
                                    error.message
                                        ?: "unknown error"
                                ),
                        )
                        for (listener in listeners) {
                            listener.onError(error)
                        }
                    }

                    override fun onCompleted() {
                        stopForeground(
                            STOP_FOREGROUND_REMOVE
                        )
                        for (listener in listeners) {
                            listener.onCompleted()
                        }
                        stopSelf()
                    }
                },
            )
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }

        val uri =
            intent
                ?.getStringExtra(EXTRA_URI)
                ?.takeIf { it.isNotBlank() }

        if (uri != null) {
            trackTitle =
                intent.getStringExtra(EXTRA_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Live Karaoke"

            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    "Preparing live karaoke…"
                ),
            )

            engine.start(
                uri = android.net.Uri.parse(uri),
                positionMs =
                    intent.getLongExtra(
                        EXTRA_POSITION_MS,
                        0L,
                    ),
                settings =
                    LiveKaraokeEngine.Settings(
                        depth =
                            intent.getFloatExtra(
                                EXTRA_DEPTH,
                                1f,
                            ),
                        focus =
                            intent.getFloatExtra(
                                EXTRA_FOCUS,
                                0.5f,
                            ),
                        transientProtection =
                            intent.getFloatExtra(
                                EXTRA_TRANSIENT,
                                0.7f,
                            ),
                        dryWet =
                            intent.getFloatExtra(
                                EXTRA_DRY_WET,
                                1f,
                            ),
                        stemGainDb =
                            intent.getFloatExtra(
                                EXTRA_STEM_GAIN,
                                0f,
                            ),
                        outputGainDb =
                            intent.getFloatExtra(
                                EXTRA_OUTPUT_GAIN,
                                0f,
                            ),
                        ceilingDb =
                            intent.getFloatExtra(
                                EXTRA_CEILING,
                                -1f,
                            ),
                    ),
            )
        }

        return START_NOT_STICKY
    }

    fun addListener(
        listener: LiveKaraokeEngine.Listener,
    ) {
        listeners.add(listener)
    }

    fun removeListener(
        listener: LiveKaraokeEngine.Listener,
    ) {
        listeners.remove(listener)
    }

    fun currentState(): LiveKaraokeEngine.State =
        engine.currentState()

    fun pausePlayback() = engine.pause()

    fun resumePlayback() = engine.resume()

    fun seekTo(positionMs: Long) =
        engine.seekTo(positionMs)

    fun stopPlayback() {
        engine.stop()
        stopForeground(
            STOP_FOREGROUND_REMOVE
        )
        stopSelf()
    }

    private fun buildNotification(
        message: String,
    ): Notification {
        val launch =
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID + 1,
                Intent(
                    this,
                    LiveKaraokeActivity::class.java,
                ).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
            )

        val stop =
            PendingIntent.getService(
                this,
                NOTIFICATION_ID + 2,
                Intent(
                    this,
                    LiveKaraokeService::class.java,
                ).apply {
                    action = ACTION_STOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID,
        )
            .setSmallIcon(
                android.R.drawable.ic_media_play
            )
            .setContentTitle(trackTitle)
            .setContentText(message)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stop,
            )
            .setCategory(
                NotificationCompat.CATEGORY_TRANSPORT
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification(
        message: String,
    ) {
        runCatching {
            getSystemService(
                NotificationManager::class.java
            ).notify(
                NOTIFICATION_ID,
                buildNotification(message)
            )
        }
    }

    private fun createNotificationChannel() {
        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Live Karaoke",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description =
                    "Foreground playback for live MDX-Net karaoke"
            },
        )
    }

    override fun onDestroy() {
        engine.close()
        listeners.clear()
        super.onDestroy()
    }
}
