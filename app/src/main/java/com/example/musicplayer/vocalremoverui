package com.example.musicplayer.vocalremoverui

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.bmwanje.audiophile.vocalremover.ModelManager
import com.bmwanje.audiophile.vocalremover.VocalRemoverPipeline
import com.bmwanje.audiophile.vocalremover.VocalSeparatorCore
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Programmatic screen designed to match Audiophile Player's existing
 * "Midnight Audiophile" Sound/DSP UI. It deliberately does not touch the
 * existing EQ/Widener DSP processors.
 */
class VocalRemoverUi(
    private val host: Host,
) {
    interface Host {
        fun currentTrack(): TrackSource?
        fun decodeCurrentTrackToStereoPcm(onReady: (PcmTrack) -> Unit, onError: (Throwable) -> Unit)
        fun encodeProcessedTrack(pcm: PcmTrack, titleSuffix: String, onReady: (Uri) -> Unit, onError: (Throwable) -> Unit)
        fun playProcessedUri(uri: Uri)
        fun openBack()
    }

    data class TrackSource(val title: String, val uri: Uri)
    data class PcmTrack(val sampleRate: Int, val stereo: VocalSeparatorCore.Stereo)

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Audiophile-VocalRemover").apply { isDaemon = true }
    }
    private var activeJob: Future<*>? = null
    private var pipeline: VocalRemoverPipeline? = null

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(23, 26, 31)
    private val surface2 = Color.rgb(29, 33, 39)
    private val text = Color.rgb(242, 245, 247)
    private val secondary = Color.rgb(157, 167, 177)
    private val accent = Color.rgb(44, 190, 214)

    private lateinit var status: TextView
    private lateinit var track: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var action: Button
    private lateinit var modelButton: Button

    private var depth = 1f
    private var focus = 0.5f
    private var transientProtection = 0.7f
    private var dryWet = 1f
    private var stemGainDb = 0f
    private var outputGainDb = 0f
    private var ceilingDb = -1f

    fun build(parent: ViewGroup): View {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val toolbar = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg)
        }
        toolbar.addView(Button(parent.context).apply {
            text = "‹"
            setTextColor(text)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { host.openBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(TextView(parent.context).apply {
            this.text = "AI Vocal Remover"
            setTextColor(text)
            textSize = 20f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)

        val scroll = ScrollView(parent.context)
        val col = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        track = label("Current track")
        col.addView(track)
        status = value("Ready to process a clean instrumental copy")
        col.addView(status, marginTop(4))

        col.addView(card().apply {
            addView(sectionTitle("Model"))
            progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                visibility = View.GONE
            }
            addView(progress, marginTop(10))
            progressText = value("The AI model is stored privately on the device.")
            addView(progressText, marginTop(6))
            modelButton = Button(context).apply {
                text = "Prepare model"
                setOnClickListener { ensureModel() }
            }
            addView(modelButton, marginTop(10))
        })

        col.addView(card().apply {
            addView(sectionTitle("Vocal removal"))
            addView(makeSlider("Removal depth", 0, 100, (depth * 100).toInt()) { depth = it / 100f }, marginTop(10))
            addView(makeSlider("Focus", 0, 100, (focus * 100).toInt()) { focus = it / 100f }, marginTop(10))
            addView(makeSlider("Transient protection", 0, 100, (transientProtection * 100).toInt()) {
                transientProtection = it / 100f
            }, marginTop(10))
            addView(makeSlider("Dry / Wet", 0, 100, (dryWet * 100).toInt()) { dryWet = it / 100f }, marginTop(10))
        })

        col.addView(card().apply {
            addView(sectionTitle("Output"))
            addView(makeSlider("Vocal stem gain", -240, 240, (stemGainDb * 10).toInt()) {
                stemGainDb = it / 10f
            }, marginTop(10))
            addView(makeSlider("Output gain", -240, 240, (outputGainDb * 10).toInt()) {
                outputGainDb = it / 10f
            }, marginTop(10))
            addView(makeSlider("True-peak ceiling", -120, 0, (ceilingDb * 10).toInt()) {
                ceilingDb = it / 10f
            }, marginTop(10))
        })

        col.addView(card().apply {
            addView(sectionTitle("Processing"))
            addView(value("Creates a separate instrumental copy. The original track and Audiophile EQ / Parametric EQ / Stereo Widener settings remain untouched."), marginTop(8))
            val keep = Switch(context).apply {
                text = "Use current DSP output as input"
                setTextColor(text)
                isChecked = false
                // Deliberately defaults OFF: vocal-remover should receive decoded source audio
                // unless the host explicitly supplies its post-DSP PCM path.
            }
            addView(keep, marginTop(10))
        })

        action = Button(parent.context).apply {
            text = "Remove vocals from current track"
            setOnClickListener { processCurrentTrack() }
        }
        col.addView(action, marginTop(14))

        updateTrackText()
        return root
    }

    fun close() {
        activeJob?.cancel(true)
        activeJob = null
        pipeline?.close()
        pipeline = null
        executor.shutdownNow()
    }

    private fun ensureModel() {
        val ctx = status.context
        if (ModelManager.isInstalled(ctx)) {
            status.text = "AI model ready"
            modelButton.text = "Model ready"
            modelButton.isEnabled = false
            return
        }
        modelButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Preparing AI model…"
        activeJob?.cancel(true)
        activeJob = executor.submit {
            try {
                ModelManager.ensureInstalled(ctx) { done, total ->
                    val p = if (total > 0) ((done * 1000) / total).toInt().coerceIn(0, 1000) else 0
                    main.post {
                        progress.progress = p
                        progressText.text = String.format(Locale.US, "Model download: %.0f%%", p / 10f)
                    }
                }
                main.post {
                    progress.visibility = View.GONE
                    status.text = "AI model ready"
                    progressText.text = "The model is verified and stored locally."
                    modelButton.text = "Model ready"
                    modelButton.isEnabled = false
                }
            } catch (t: Throwable) {
                main.post {
                    progress.visibility = View.GONE
                    status.text = "Model preparation failed: ${t.message ?: "unknown error"}"
                    modelButton.isEnabled = true
                }
            }
        }
    }

    private fun processCurrentTrack() {
        val current = host.currentTrack() ?: run {
            status.text = "No track is currently selected"
            return
        }
        updateTrackText(current.title)
        if (!ModelManager.isInstalled(action.context)) {
            status.text = "Prepare the AI model first"
            ensureModel()
            return
        }

        action.isEnabled = false
        modelButton.isEnabled = false
        status.text = "Separating vocals…"
        activeJob?.cancel(true)
        activeJob = executor.submit {
            try {
                main.post { status.text = "Decoding current track…" }
                host.decodeCurrentTrackToStereoPcm({ pcm ->
                    activeJob = executor.submit {
                        var localPipeline: VocalRemoverPipeline? = null
                        try {
                            // The decoded PCM rate is authoritative. Do not force 44.1 kHz here;
                            // VocalRemoverPipeline will resample internally for MDX when needed.
                            localPipeline = VocalRemoverPipeline(action.context, pcm.sampleRate)
                            localPipeline.setDepth(depth)
                            localPipeline.setFocus(focus)
                            localPipeline.setTransientProtection(transientProtection)
                            localPipeline.setDryWet(dryWet)
                            localPipeline.setStemGainDb(stemGainDb)
                            localPipeline.setOutputGainDb(outputGainDb)
                            localPipeline.setCeilingDb(ceilingDb)
                            localPipeline.loadModel()
                            pipeline?.close()
                            pipeline = localPipeline

                            main.post { status.text = "Running MDX-Net + premium post-processing…" }
                            val out = localPipeline.separateAndRemove(pcm.stereo)
                            main.post { status.text = "Encoding instrumental copy…" }
                            host.encodeProcessedTrack(PcmTrack(pcm.sampleRate, out), " — Instrumental") { uri ->
                                main.post {
                                    status.text = "Instrumental copy ready"
                                    action.isEnabled = true
                                    modelButton.isEnabled = true
                                    host.playProcessedUri(uri)
                                }
                            } onError@{ err ->
                                main.post {
                                    status.text = "Encoding failed: ${err.message ?: "unknown error"}"
                                    action.isEnabled = true
                                    modelButton.isEnabled = true
                                }
                            }
                            localPipeline = null // retained by the screen until the next job/close().
                        } catch (t: Throwable) {
                            localPipeline?.close()
                            main.post {
                                status.text = "Vocal removal failed: ${t.message ?: "unknown error"}"
                                action.isEnabled = true
                                modelButton.isEnabled = true
                            }
                        }
                    }
                }, { err ->
                    main.post {
                        status.text = "Decode failed: ${err.message ?: "unknown error"}"
                        action.isEnabled = true
                        modelButton.isEnabled = true
                    }
                })
            } catch (t: Throwable) {
                main.post {
                    status.text = "Vocal remover unavailable: ${t.message ?: "unknown error"}"
                    action.isEnabled = true
                    modelButton.isEnabled = true
                }
            }
        }
    }

    private fun updateTrackText(titleOverride: String? = null) {
        val source = host.currentTrack()
        track.text = "Current track: ${titleOverride ?: source?.title ?: "None"}"
    }

    private fun card(): LinearLayout = LinearLayout(status.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(surface)
        layoutParams = marginTop(14)
    }

    private fun sectionTitle(s: String) = TextView(status.context).apply {
        text = s
        setTextColor(text)
        textSize = 16f
    }

    private fun label(s: String) = TextView(status.context).apply {
        text = s
        setTextColor(secondary)
        textSize = 13f
    }

    private fun value(s: String) = TextView(status.context).apply {
        text = s
        setTextColor(text)
        textSize = 14f
    }

    private fun makeSlider(title: String, min: Int, max: Int, initial: Int, onChanged: (Int) -> Unit): View {
        val box = LinearLayout(status.context).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(status.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(status.context).apply {
            text = title
            setTextColor(text)
            textSize = 14f
        }
        val valueView = TextView(status.context).apply {
            setTextColor(secondary)
            textSize = 13f
            gravity = Gravity.END
        }
        row.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(84), -2))
        box.addView(row)
        val seek = SeekBar(status.context).apply {
            max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    valueView.text = formatValue(title, v)
                    onChanged(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(-1, -2))
        valueView.text = formatValue(title, initial)
        return box
    }

    private fun formatValue(title: String, value: Int): String = when {
        title.contains("gain", true) || title.contains("ceiling", true) -> String.format(Locale.US, "%.1f dB", value / 10f)
        else -> "$value%"
    }

    private fun dp(v: Int): Int = (v * status.context.resources.displayMetrics.density).toInt()
    private fun marginTop(v: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(v) }
}
