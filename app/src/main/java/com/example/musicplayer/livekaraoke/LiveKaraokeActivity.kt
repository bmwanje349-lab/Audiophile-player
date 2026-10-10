package com.example.musicplayer.livekaraoke

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/**
 * UI for the progressive/live karaoke path.
 *
 * This is deliberately separate from the existing offline AI Vocal Remover
 * activity. The live screen only orchestrates the new foreground service.
 */
class LiveKaraokeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK_URI =
            "extra_live_karaoke_track_uri"
        const val EXTRA_TRACK_TITLE =
            "extra_live_karaoke_track_title"
        const val EXTRA_TRACK_POSITION_MS =
            "extra_live_karaoke_track_position_ms"

        // Resolved by name so this bundle compiles without the separate
        // AI Vocal Remover bundle (values match VocalRemoverActivity).
        private const val VOCAL_REMOVER_ACTIVITY =
            "com.example.musicplayer.vocalremoverui.VocalRemoverActivity"
        private const val VOCAL_REMOVER_EXTRA_URI = "extra_track_uri"
        private const val VOCAL_REMOVER_EXTRA_TITLE = "extra_track_title"
        private const val PLAYBACK_SERVICE =
            "com.example.musicplayer.PlaybackService"

        private const val BG =
            0xFF0B0D10.toInt()
        private const val SURFACE =
            0xFF14171C.toInt()
        private const val TEXT =
            0xFFF5F7FA.toInt()
        private const val TEXT_SECONDARY =
            0xFFA7AFBA.toInt()
        private const val MUTED =
            0xFF737C88.toInt()
        private const val BORDER =
            0xFF272C33.toInt()
        private const val ACCENT =
            0xFF54B8FF.toInt()
    }

    private var service: LiveKaraokeService? = null
    private var bound = false

    private var trackUri: String? = null
    private var trackTitle = "Live Karaoke"
    private var initialPositionMs = 0L

    private lateinit var status: TextView
    private lateinit var progress: SeekBar
    private lateinit var progressLabel: TextView
    private lateinit var action: MaterialButton
    private lateinit var stop: MaterialButton

    private var userSeeking = false
    private var autoStartRequested = false
    private var lastLivePositionMs = 0L

    private val listener =
        object : LiveKaraokeEngine.Listener {
            override fun onState(
                state: LiveKaraokeEngine.State,
                message: String,
            ) {
                runOnUiThread {
                    status.text = message
                    action.text =
                        when (state) {
                            LiveKaraokeEngine.State.PLAYING ->
                                "Pause Live Karaoke"

                            LiveKaraokeEngine.State.PAUSED ->
                                "Resume Live Karaoke"

                            LiveKaraokeEngine.State.BUFFERING,
                            LiveKaraokeEngine.State.SEEKING ->
                                "Buffering…"

                            LiveKaraokeEngine.State.STOPPED ->
                                "Start Live Karaoke"
                        }

                    action.isEnabled =
                        trackUri != null &&
                            state != LiveKaraokeEngine.State.BUFFERING &&
                            state != LiveKaraokeEngine.State.SEEKING

                    stop.isEnabled =
                        state != LiveKaraokeEngine.State.STOPPED
                }
            }

            override fun onProgress(
                positionMs: Long,
                durationMs: Long,
            ) {
                lastLivePositionMs = positionMs.coerceAtLeast(0L)
                runOnUiThread {
                    if (!userSeeking) {
                        progress.max =
                            durationMs
                                .coerceIn(
                                    1L,
                                    Int.MAX_VALUE.toLong(),
                                )
                                .toInt()

                        progress.progress =
                            positionMs
                                .coerceIn(
                                    0L,
                                    progress.max.toLong(),
                                )
                                .toInt()
                    }

                    progressLabel.text =
                        formatTime(positionMs) +
                            " / " +
                            formatTime(durationMs)
                }
            }

            override fun onError(
                error: Throwable,
            ) {
                runOnUiThread {
                    status.text =
                        "Live karaoke failed: " +
                            (
                                error.message
                                    ?: "unknown error"
                            )
                    action.text = "Start Live Karaoke"
                    action.isEnabled = trackUri != null
                    stop.isEnabled = false
                    recoverNormalPlayback()
                }
            }

            override fun onCompleted() {
                runOnUiThread {
                    status.text =
                        "Live karaoke complete"
                    action.text = "Start Live Karaoke"
                    action.isEnabled = trackUri != null
                    stop.isEnabled = false
                    recoverNormalPlayback()
                }
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                val local =
                    binder as? LiveKaraokeService.LocalBinder
                service = local?.service()
                service?.addListener(listener)
                bound = service != null

                service?.let {
                    val currentState = it.currentState()
                    status.text =
                        when (currentState) {
                            LiveKaraokeEngine.State.STOPPED ->
                                if (autoStartRequested) {
                                    "Ready — tap Start Live Karaoke."
                                } else {
                                    "Starting live karaoke automatically…"
                                }

                            LiveKaraokeEngine.State.BUFFERING,
                            LiveKaraokeEngine.State.SEEKING ->
                                "Preparing the live vocal-removal stream…"

                            LiveKaraokeEngine.State.PLAYING ->
                                "Live karaoke is playing"

                            LiveKaraokeEngine.State.PAUSED ->
                                "Live karaoke paused"
                        }
                    if (currentState == LiveKaraokeEngine.State.STOPPED) {
                        autoStartLiveKaraoke()
                    }
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?,
            ) {
                service = null
                bound = false
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        trackUri =
            intent
                .getStringExtra(EXTRA_TRACK_URI)
                ?.takeIf { it.isNotBlank() }

        trackTitle =
            intent
                .getStringExtra(EXTRA_TRACK_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: "Live Karaoke"

        initialPositionMs =
            intent.getLongExtra(
                EXTRA_TRACK_POSITION_MS,
                0L,
            )
                .coerceAtLeast(0L)

        setContentView(buildUi())
        progress.progress =
            initialPositionMs
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    override fun onStart() {
        super.onStart()

        val serviceIntent =
            Intent(
                this,
                LiveKaraokeService::class.java,
            )

        bindService(
            serviceIntent,
            connection,
            BIND_AUTO_CREATE,
        )

    }

    override fun onStop() {
        service?.removeListener(listener)

        if (bound) {
            unbindService(connection)
            bound = false
        }

        super.onStop()
    }

    private fun buildUi(): LinearLayout {
        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(28),
                )
                setBackgroundColor(BG)
            }

        val toolbar =
            LinearLayout(this).apply {
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        toolbar.addView(
            TextView(this).apply {
                text = "Live Karaoke"
                setTextColor(TEXT)
                textSize = 27f
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD,
                )
            },
            LinearLayout.LayoutParams(
                0,
                -2,
                1f,
            ),
        )

        toolbar.addView(
            TextView(this).apply {
                text = "LIVE"
                setTextColor(ACCENT)
                textSize = 11f
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD,
                )
            },
        )

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                -1,
                dp(52),
            ),
        )

        val card =
            MaterialCardView(this).apply {
                radius = dp(22).toFloat()
                setCardBackgroundColor(SURFACE)
                strokeWidth = 1
                strokeColor = BORDER
            }

        val body =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(18),
                )
            }

        body.addView(
            TextView(this).apply {
                text = trackTitle
                setTextColor(TEXT)
                textSize = 20f
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD,
                )
                maxLines = 2
                ellipsize =
                    android.text.TextUtils.TruncateAt.END
            },
        )

        body.addView(
            TextView(this).apply {
                text =
                    "Neural AI separation when sustainable → bounded buffer → instrumental playback"
                setTextColor(TEXT_SECONDARY)
                textSize = 13f
                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(12),
                )
            },
        )

        status =
            TextView(this).apply {
                text =
                    if (trackUri == null) {
                        "No current local track was supplied."
                    } else {
                        "Ready — Live Karaoke tries neural AI separation first. If the phone cannot sustain it, it switches to Fast DSP suppression, which may leave vocals audible."
                    }
                setTextColor(MUTED)
                textSize = 12f
            }

        body.addView(
            status,
            LinearLayout.LayoutParams(
                -1,
                -2,
            ),
        )

        progress =
            SeekBar(this).apply {
                max = 1
                progress = 0
                setPadding(
                    0,
                    dp(10),
                    0,
                    dp(4),
                )
            }

        body.addView(
            progress,
            LinearLayout.LayoutParams(
                -1,
                dp(52),
            ),
        )

        progressLabel =
            TextView(this).apply {
                text = "0:00 / 0:00"
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
                gravity = Gravity.END
            }

        body.addView(
            progressLabel,
            LinearLayout.LayoutParams(
                -1,
                dp(26),
            ),
        )

        action =
            MaterialButton(this).apply {
                text = "Start Live Karaoke"
                isEnabled = trackUri != null
                setOnClickListener {
                    togglePlayback()
                }
            }

        body.addView(
            action,
            LinearLayout.LayoutParams(
                -1,
                dp(52),
            ),
        )

        stop =
            MaterialButton(this).apply {
                text = "Stop"
                isEnabled = false
                setOnClickListener {
                    service?.stopPlayback()
                    recoverNormalPlayback()
                }
            }

        body.addView(
            stop,
            LinearLayout.LayoutParams(
                -1,
                dp(48),
            ),
        )

        body.addView(
            SwitchMaterial(this).apply {
                text = "AI mode (better, starts after a few seconds)"
                setTextColor(TEXT)
                isChecked =
                    getSharedPreferences(
                        LiveKaraokeService.PREFS_NAME,
                        MODE_PRIVATE,
                    ).getBoolean(LiveKaraokeService.PREF_NEURAL, false)
                setOnCheckedChangeListener { _, on ->
                    getSharedPreferences(
                        LiveKaraokeService.PREFS_NAME,
                        MODE_PRIVATE,
                    ).edit().putBoolean(LiveKaraokeService.PREF_NEURAL, on).apply()
                    status.text =
                        if (on) "AI mode on - applies the next time Live Karaoke starts."
                        else "Instant mode on - applies the next time Live Karaoke starts."
                }
            },
            LinearLayout.LayoutParams(
                -1,
                dp(54),
            ),
        )

        body.addView(
            TextView(this).apply {
                text =
                    "Live Karaoke checks the first complete MDX-Net window. Phones with clear " +
                        "processing headroom can start sooner; borderline phones wait for a " +
                        "two-window throughput check and may build a larger bounded buffer " +
                        "(up to 24 seconds). If AI cannot safely keep up or the phone is critically " +
                        "hot, it may switch to Fast DSP suppression; that mode is not AI separation " +
                        "and can leave vocals audible. Use Offline AI Vocal Remover for a complete " +
                        "rendered AI instrumental."
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
                setPadding(
                    0,
                    dp(14),
                    0,
                    0,
                )
            },
        )

        card.addView(body)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                -1,
                -2,
            ),
        )

        root.addView(
            MaterialButton(this).apply {
                text = "Open Offline AI Vocal Remover"
                setOnClickListener {
                    trackUri?.let { uri ->
                        runCatching {
                            startActivity(
                                Intent().setClassName(
                                    this@LiveKaraokeActivity,
                                    VOCAL_REMOVER_ACTIVITY,
                                ).apply {
                                    putExtra(
                                        VOCAL_REMOVER_EXTRA_URI,
                                        uri,
                                    )
                                    putExtra(
                                        VOCAL_REMOVER_EXTRA_TITLE,
                                        trackTitle,
                                    )
                                },
                            )
                        }.onFailure {
                            status.text =
                                "Offline AI Vocal Remover is not available in this build."
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(
                -1,
                dp(50),
            ),
        )

        progress.setOnSeekBarChangeListener(
            object :
                SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        userSeeking = true
                        progressLabel.text =
                            formatTime(value.toLong()) +
                                " / " +
                                formatTime(
                                    progress.max.toLong()
                                )
                    }
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar,
                ) = Unit

                override fun onStopTrackingTouch(
                    seekBar: SeekBar,
                ) {
                    userSeeking = false
                    service?.seekTo(
                        seekBar.progress.toLong()
                    )
                }
            },
        )

        return root
    }

    private fun autoStartLiveKaraoke() {
        if (autoStartRequested) return

        val uri = trackUri ?: return
        autoStartRequested = true

        val startIntent =
            Intent(
                this,
                LiveKaraokeService::class.java,
            ).apply {
                putExtra(
                    LiveKaraokeService.EXTRA_URI,
                    uri,
                )
                putExtra(
                    LiveKaraokeService.EXTRA_TITLE,
                    trackTitle,
                )
                putExtra(
                    LiveKaraokeService.EXTRA_POSITION_MS,
                    initialPositionMs,
                )
            }

        runCatching {
            ContextCompat.startForegroundService(
                this,
                startIntent,
            )
        }.onFailure { throwable ->
            autoStartRequested = false
            status.text =
                "Live karaoke could not start: " +
                    (throwable.message ?: throwable.javaClass.simpleName)
            action.text = "Start Live Karaoke"
            action.isEnabled = true
            stop.isEnabled = false
            recoverNormalPlayback()
        }
    }

    private fun recoverNormalPlayback() {
        runCatching {
            val future =
                androidx.media3.session.MediaController.Builder(
                    this,
                    androidx.media3.session.SessionToken(
                        this,
                        ComponentName(
                            this,
                            PLAYBACK_SERVICE,
                        ),
                    ),
                ).buildAsync()

            future.addListener(
                {
                    runCatching {
                        val controller = future.get()
                        val target =
                            if (lastLivePositionMs > 0L) {
                                lastLivePositionMs
                            } else {
                                initialPositionMs
                            }
                        controller.seekTo(target.coerceAtLeast(0L))
                        controller.play()
                        controller.release()
                    }
                },
                ContextCompat.getMainExecutor(this),
            )
        }.onFailure { throwable ->
            status.text =
                "Normal playback could not resume: " +
                    (throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun togglePlayback() {
        val active = service ?: return

        when (active.currentState()) {
            LiveKaraokeEngine.State.PLAYING ->
                active.pausePlayback()

            LiveKaraokeEngine.State.PAUSED ->
                active.resumePlayback()

            LiveKaraokeEngine.State.STOPPED -> {
                val uri = trackUri ?: return
                autoStartRequested = true

                // After a finished track the seek bar sits at the end; restart
                // from the beginning instead of instantly completing again.
                val resumeAtMs =
                    if (
                        progress.max > 1 &&
                        progress.progress >= progress.max - 1_000
                    ) {
                        0L
                    } else {
                        progress.progress.toLong()
                    }

                val startIntent =
                    Intent(
                        this,
                        LiveKaraokeService::class.java,
                    ).apply {
                        putExtra(
                            LiveKaraokeService.EXTRA_URI,
                            uri,
                        )
                        putExtra(
                            LiveKaraokeService.EXTRA_TITLE,
                            trackTitle,
                        )
                        putExtra(
                            LiveKaraokeService.EXTRA_POSITION_MS,
                            resumeAtMs,
                        )
                    }

                ContextCompat.startForegroundService(
                    this,
                    startIntent,
                )
            }

            LiveKaraokeEngine.State.BUFFERING,
            LiveKaraokeEngine.State.SEEKING -> Unit
        }
    }

    private fun formatTime(
        milliseconds: Long,
    ): String {
        val totalSeconds =
            (
                milliseconds
                    .coerceAtLeast(0L) /
                    1000L
            ).toInt()

        return String.format(
            Locale.US,
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60,
        )
    }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
        ).toInt()

}