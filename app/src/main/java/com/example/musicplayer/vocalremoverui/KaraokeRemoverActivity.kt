package com.example.musicplayer.vocalremoverui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class KaraokeRemoverActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "karaoke_uri"
        const val EXTRA_TITLE = "karaoke_title"
        const val EXTRA_DEPTH = "karaoke_depth"
        const val EXTRA_FOCUS = "karaoke_focus"
        const val EXTRA_TRANSIENT = "karaoke_transient"
        const val EXTRA_DRY_WET = "karaoke_dry_wet"
        const val EXTRA_STEM_GAIN = "karaoke_stem_gain"
        const val EXTRA_OUTPUT_GAIN = "karaoke_output_gain"
        const val EXTRA_CEILING = "karaoke_ceiling"
    }

    private lateinit var controller: KaraokePlaybackController
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: SeekBar
    private lateinit var timeView: TextView
    private lateinit var bufferView: TextView
    private lateinit var playPause: Button
    private lateinit var stop: Button

    private var userSeeking = false
    private var currentState = KaraokePlaybackController.State.IDLE
    private var currentConfig: KaraokePlaybackController.Config? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = KaraokePlaybackController { snapshot ->
            runOnUiThread { renderSnapshot(snapshot) }
        }

        setContentView(buildUi())

        val uriString = intent.getStringExtra(EXTRA_URI)
        val uri = uriString?.let { android.net.Uri.parse(it) }

        if (uri == null) {
            statusView.text = "No track selected"
            playPause.isEnabled = false
            return
        }

        val config = KaraokePlaybackController.Config(
            uri = uri,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Current track",
            depth = intent.getFloatExtra(EXTRA_DEPTH, 1f),
            focus = intent.getFloatExtra(EXTRA_FOCUS, 0.5f),
            transientProtection = intent.getFloatExtra(EXTRA_TRANSIENT, 0.7f),
            dryWet = intent.getFloatExtra(EXTRA_DRY_WET, 1f),
            stemGainDb = intent.getFloatExtra(EXTRA_STEM_GAIN, 0f),
            outputGainDb = intent.getFloatExtra(EXTRA_OUTPUT_GAIN, 0f),
            ceilingDb = intent.getFloatExtra(EXTRA_CEILING, -1f),
        )

        currentConfig = config
        titleView.text = config.title
        controller.startWithContext(this, config)
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }

        titleView = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            text = "Karaoke"
        }

        statusView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            text = "Preparing…"
        }

        progress = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        userSeeking = true
                    }

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progressValue: Int,
                        fromUser: Boolean,
                    ) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        userSeeking = false
                        val duration = currentDurationMs
                        if (duration > 0L) {
                            val bar = seekBar ?: return
                            val target = duration * bar.progress / 1000L
                            controller.seekWithContext(this@KaraokeRemoverActivity, target)
                        }
                    }
                }
            )
        }

        timeView = TextView(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            text = "0:00 / 0:00"
        }

        bufferView = TextView(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            text = "Buffered ahead: 0.0 s"
        }

        playPause = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                when (currentState) {
                    KaraokePlaybackController.State.PLAYING,
                    KaraokePlaybackController.State.BUFFERING -> controller.pause()

                    KaraokePlaybackController.State.PAUSED -> controller.resume()

                    KaraokePlaybackController.State.COMPLETED -> {
                        currentConfig?.let {
                            controller.startWithContext(this@KaraokeRemoverActivity, it)
                        }
                    }

                    else -> Unit
                }
            }
        }

        stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                controller.stop()
                finish()
            }
        }

        root.addView(titleView)
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        root.addView(
            progress,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(24)
            }
        )
        root.addView(timeView)
        root.addView(bufferView, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        actions.addView(
            playPause,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = dp(6)
            },
        )
        actions.addView(
            stop,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(6)
            },
        )

        root.addView(
            actions,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(20)
            }
        )

        root.addView(
            TextView(this).apply {
                text =
                    "Live karaoke mode waits for the first few MDX-Net windows, starts playback, and continuously processes more audio in the background. Seeking restarts separation from the new position."
                textSize = 13f
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(20)
            }
        )

        return root
    }

    private var currentDurationMs: Long = 0L

    private fun renderSnapshot(
        snapshot: KaraokePlaybackController.Snapshot,
    ) {
        currentState = snapshot.state
        currentDurationMs = snapshot.durationMs

        statusView.text =
            snapshot.message.ifBlank {
                when (snapshot.state) {
                    KaraokePlaybackController.State.PREPARING -> "Preparing…"
                    KaraokePlaybackController.State.BUFFERING -> "Buffering…"
                    KaraokePlaybackController.State.PLAYING -> "Playing"
                    KaraokePlaybackController.State.PAUSED -> "Paused"
                    KaraokePlaybackController.State.DRAINING -> "Finishing…"
                    KaraokePlaybackController.State.COMPLETED -> "Complete"
                    KaraokePlaybackController.State.ERROR -> "Error"
                    KaraokePlaybackController.State.STOPPED -> "Stopped"
                    KaraokePlaybackController.State.IDLE -> "Idle"
                }
            }

        if (!userSeeking && snapshot.durationMs > 0L) {
            progress.progress =
                (snapshot.positionMs * 1000L / snapshot.durationMs)
                    .toInt()
                    .coerceIn(0, 1000)
        }

        timeView.text =
            formatTime(snapshot.positionMs) +
                " / " +
                formatTime(snapshot.durationMs)

        bufferView.text =
            String.format(
                Locale.US,
                "Buffered ahead: %.1f s",
                snapshot.bufferAheadMs / 1000f,
            )

        playPause.text =
            when (snapshot.state) {
                KaraokePlaybackController.State.PLAYING,
                KaraokePlaybackController.State.BUFFERING -> "Pause"

                KaraokePlaybackController.State.PAUSED,
                KaraokePlaybackController.State.ERROR -> "Resume"

                KaraokePlaybackController.State.COMPLETED -> "Replay"

                else -> "Pause"
            }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "%d:%02d",
            total / 60L,
            total % 60L,
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}
