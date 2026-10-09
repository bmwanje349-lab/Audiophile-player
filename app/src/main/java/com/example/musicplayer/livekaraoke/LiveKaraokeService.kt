package com.example.musicplayer.livekaraoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

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

    private val audioFocusLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeAfterFocusGain = false
    private var noisyReceiverRegistered = false

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    val shouldResume =
                        synchronized(audioFocusLock) {
                            val resume = hasAudioFocus && resumeAfterFocusGain
                            resumeAfterFocusGain = false
                            resume
                        }
                    if (shouldResume) engine.resume()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    val current = engine.currentState()
                    if (
                        current == LiveKaraokeEngine.State.PLAYING ||
                        current == LiveKaraokeEngine.State.BUFFERING ||
                        current == LiveKaraokeEngine.State.SEEKING
                    ) {
                        synchronized(audioFocusLock) {
                            resumeAfterFocusGain = true
                        }
                        engine.pause()
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    synchronized(audioFocusLock) {
                        resumeAfterFocusGain = false
                    }
                    engine.stop()
                    abandonAudioFocus()
                    leaveForeground()
                    stopSelf()
                }
            }
        }

    private val becomingNoisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return

                val current = engine.currentState()
                if (
                    current == LiveKaraokeEngine.State.PLAYING ||
                    current == LiveKaraokeEngine.State.BUFFERING ||
                    current == LiveKaraokeEngine.State.SEEKING
                ) {
                    // A route change such as unplugging headphones must not
                    // resume automatically to the device speaker.
                    synchronized(audioFocusLock) {
                        resumeAfterFocusGain = false
                    }
                    engine.pause()
                }
            }
        }

    /*
     * True only while this service owns its foreground notification. The
     * engine reports STOPPED from stop()/close() *after* the notification was
     * removed (and again from onDestroy), and notify() would re-post the
     * ongoing notification as an orphan nobody can dismiss. Guard every
     * notification write with this flag, under a lock shared with removal.
     */
    private val notificationLock = Any()
    private var foregroundActive = false
    private var trackTitle = "Live Karaoke"
    private var trackUri: String? = null

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
                        abandonAudioFocus()
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

                        /*
                         * A failed engine is no longer producing audio. Do not
                         * leave an orphaned foreground service/notification
                         * running after the listener has received the error.
                         */
                        leaveForeground()
                        stopSelf()
                    }

                    override fun onCompleted() {
                        abandonAudioFocus()
                        leaveForeground()
                        for (listener in listeners) {
                            listener.onCompleted()
                        }
                        stopSelf()
                    }
                },
            )

        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiverRegistered = true
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
            trackUri = uri

            synchronized(notificationLock) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        "Preparing live karaoke…"
                    ),
                )
                foregroundActive = true
            }

            if (!requestAudioFocusForPlayback()) {
                val error = IllegalStateException(
                    "Android denied audio focus. Stop other audio playback and try again.",
                )
                leaveForeground()
                for (listener in listeners) {
                    runCatching { listener.onError(error) }
                }
                stopSelf()
                return START_NOT_STICKY
            }

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

    fun resumePlayback() {
        if (requestAudioFocusForPlayback()) {
            synchronized(audioFocusLock) {
                resumeAfterFocusGain = false
            }
            engine.resume()
        } else {
            val message = "Another app currently owns audio focus. Resume when it is available."
            for (listener in listeners) {
                runCatching {
                    listener.onState(LiveKaraokeEngine.State.PAUSED, message)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) =
        engine.seekTo(positionMs)

    fun stopPlayback() {
        engine.stop()
        abandonAudioFocus()
        leaveForeground()
        stopSelf()
    }

    private fun requestAudioFocusForPlayback(): Boolean {
        val manager =
            audioManager ?: getSystemService(AudioManager::class.java)
                ?: return false
        audioManager = manager

        val existing =
            synchronized(audioFocusLock) {
                audioFocusRequest
            }
        val request =
            existing ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()

        val result =
            runCatching {
                manager.requestAudioFocus(request)
            }.getOrElse {
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }

        synchronized(audioFocusLock) {
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            audioFocusRequest =
                if (hasAudioFocus) request else null
            if (!hasAudioFocus) resumeAfterFocusGain = false
        }

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            runCatching { manager.abandonAudioFocusRequest(request) }
            return false
        }
        return true
    }

    private fun abandonAudioFocus() {
        val (manager, request) =
            synchronized(audioFocusLock) {
                val heldRequest = audioFocusRequest
                audioFocusRequest = null
                hasAudioFocus = false
                resumeAfterFocusGain = false
                audioManager to heldRequest
            }

        if (manager != null && request != null) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
    }

    private fun leaveForeground() {
        synchronized(notificationLock) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
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
                    trackUri?.let {
                        putExtra(
                            LiveKaraokeActivity.EXTRA_TRACK_URI,
                            it,
                        )
                    }
                    putExtra(
                        LiveKaraokeActivity.EXTRA_TRACK_TITLE,
                        trackTitle,
                    )
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
        synchronized(notificationLock) {
            if (!foregroundActive) return
            runCatching {
                getSystemService(
                    NotificationManager::class.java
                ).notify(
                    NOTIFICATION_ID,
                    buildNotification(message)
                )
            }
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
        synchronized(notificationLock) {
            foregroundActive = false
        }
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(becomingNoisyReceiver) }
            noisyReceiverRegistered = false
        }
        abandonAudioFocus()
        engine.close()
        listeners.clear()
        super.onDestroy()
    }
}
