package com.example.audiodoctor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_PICK_AUDIO = 1001
        private const val REQUEST_EXPORT_REPORT = 1002
    }

    private val ink = Color.parseColor("#F0F5F9")
    private val muted = Color.parseColor("#8A9BAD")
    private val mint = Color.parseColor("#67F2C2")
    private val page = Color.parseColor("#080D13")
    private val cardColor = Color.parseColor("#121A24")
    private val border = Color.parseColor("#2A3745")
    private val amber = Color.parseColor("#FFCA75")
    private val red = Color.parseColor("#FF7B87")
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var chooseButton: Button
    private lateinit var exportButton: Button
    private lateinit var selectedFile: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var waveformGraph: AudioGraphView
    private lateinit var spectrumGraph: AudioGraphView
    private lateinit var metricsContainer: LinearLayout
    private lateinit var findingsText: TextView
    private lateinit var sourceDetailsText: TextView

    private var currentUri: Uri? = null
    private var currentReport: AudioReport? = null
    private var analysing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        window.decorView.systemUiVisibility = 0
        buildInterface()
        showEmptyState()
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        val pageColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(pageColumn, ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(scroll)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandMark = TextView(this).apply {
            text = "〽"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(5, 24, 17))
            background = rounded(mint, dp(14), mint)
        }
        brandRow.addView(brandMark, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brandCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandCopy.addView(label("AUDIO DOCTOR STUDIO", 17f, ink, true))
        brandCopy.addView(label("OFFLINE AUDIO INTELLIGENCE", 10f, mint, true))
        brandRow.addView(brandCopy, LinearLayout.LayoutParams(0, -2, 1f))
        pageColumn.addView(brandRow)
        addSpace(pageColumn, 20)

        pageColumn.addView(label("YOUR SOUND.", 12f, mint, true))
        pageColumn.addView(label("Under the microscope.", 30f, ink, true).apply {
            letterSpacing = -0.035f
        })
        pageColumn.addView(label(
            "Select a song to inspect its waveform, frequency balance, loudness, clipping indicators and stereo compatibility.",
            13f, muted, false
        ))
        addSpace(pageColumn, 16)

        val privacyCard = panel()
        privacyCard.addView(label("●  PRIVATE BY DESIGN", 11f, mint, true))
        addSpace(privacyCard, 5)
        privacyCard.addView(label(
            "Your song is decoded on this phone. Audio Doctor Studio has no internet permission and does not modify the selected file.",
            12f, muted, false
        ))
        pageColumn.addView(privacyCard, fullWidth())
        addSpace(pageColumn, 12)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chooseButton = button("＋  SELECT SONG", true).apply {
            setOnClickListener { pickAudio() }
        }
        exportButton = button("EXPORT JSON", false).apply {
            isEnabled = false
            setOnClickListener { exportReport() }
        }
        actionRow.addView(chooseButton, LinearLayout.LayoutParams(0, dp(48), 1.25f).apply {
            marginEnd = dp(8)
        })
        actionRow.addView(exportButton, LinearLayout.LayoutParams(0, dp(48), 0.9f))
        pageColumn.addView(actionRow, fullWidth())
        addSpace(pageColumn, 12)

        selectedFile = label("No song selected", 13f, ink, true)
        statusText = label("READY · WAITING FOR AUDIO", 11f, muted, true)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(border)
            visibility = View.GONE
        }
        progressText = label("", 10f, muted, false)
        val statePanel = panel()
        statePanel.addView(selectedFile)
        addSpace(statePanel, 6)
        statePanel.addView(statusText)
        addSpace(statePanel, 7)
        statePanel.addView(progressBar, fullWidth(dp(5)))
        statePanel.addView(progressText)
        pageColumn.addView(statePanel, fullWidth())
        addSpace(pageColumn, 21)

        addSection(pageColumn, "01 / SIGNAL VIEW", "Waveform")
        waveformGraph = AudioGraphView(this).apply { mode = AudioGraphView.Mode.WAVEFORM }
        pageColumn.addView(graphPanel("AMPLITUDE OVER TIME", waveformGraph), fullWidth())
        addSpace(pageColumn, 12)

        addSection(pageColumn, "02 / FREQUENCY", "Spectrum")
        spectrumGraph = AudioGraphView(this).apply { mode = AudioGraphView.Mode.SPECTRUM }
        pageColumn.addView(graphPanel("AVERAGED FFT · RELATIVE MAGNITUDE", spectrumGraph), fullWidth())
        addSpace(pageColumn, 21)

        addSection(pageColumn, "03 / AUDIO QUALITY", "Diagnostic measurements")
        metricsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageColumn.addView(metricsContainer, fullWidth())
        addSpace(pageColumn, 12)

        addSection(pageColumn, "04 / FINDINGS", "What should you look at?")
        val findingsPanel = panel()
        findingsText = label("Choose a song to generate measured findings.", 12f, muted, false)
        findingsPanel.addView(findingsText)
        pageColumn.addView(findingsPanel, fullWidth())
        addSpace(pageColumn, 12)

        addSection(pageColumn, "05 / SOURCE FILE", "Metadata and technical profile")
        val metadataPanel = panel()
        sourceDetailsText = label("No file loaded.", 12f, muted, false)
        metadataPanel.addView(sourceDetailsText)
        pageColumn.addView(metadataPanel, fullWidth())
        addSpace(pageColumn, 18)
        pageColumn.addView(label(
            "ANALYSIS ONLY  •  ORIGINAL FILE PRESERVED  •  ESTIMATES ARE LABELED",
            9f, muted, true
        ))
    }

    private fun pickAudio() {
        if (analysing) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_AUDIO)
        } catch (error: Exception) {
            Toast.makeText(this, "No compatible file picker is available.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform document picker to retain compatibility without extra libraries.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_PICK_AUDIO -> {
                val uri = data.data ?: return
                currentUri = uri
                currentReport = null
                exportButton.isEnabled = false
                selectedFile.text = getDisplayName(uri)
                startAnalysis(uri)
            }
            REQUEST_EXPORT_REPORT -> {
                val uri = data.data ?: return
                val report = currentReport ?: return
                try {
                    contentResolver.openOutputStream(uri, "w")?.use {
                        it.write(report.toJson().toByteArray(Charsets.UTF_8))
                        it.flush()
                    } ?: throw IllegalStateException("The report destination could not be opened.")
                    Toast.makeText(this, "Analysis report saved.", Toast.LENGTH_LONG).show()
                    statusText.text = "REPORT EXPORTED"
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not save report: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startAnalysis(uri: Uri) {
        analysing = true
        chooseButton.isEnabled = false
        exportButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "Preparing Android audio decoder…"
        statusText.text = "ANALYZING LOCALLY"
        waveformGraph.clear()
        spectrumGraph.clear()
        executor.submit {
            try {
                val report = AudioAnalyzer.analyze(this, uri) { value ->
                    handler.post {
                        progressBar.progress = value
                        progressText.text = "Decoding and measuring… " + value + "%"
                    }
                }
                handler.post {
                    currentReport = report
                    renderReport(report)
                    analysing = false
                    chooseButton.isEnabled = true
                    exportButton.isEnabled = true
                    progressBar.visibility = View.GONE
                    progressText.text = "Analysis complete."
                    statusText.text = "ANALYSIS COMPLETE · ORIGINAL FILE UNCHANGED"
                }
            } catch (error: Throwable) {
                handler.post {
                    analysing = false
                    chooseButton.isEnabled = true
                    exportButton.isEnabled = currentReport != null
                    progressBar.visibility = View.GONE
                    progressText.text = ""
                    statusText.text = "ANALYSIS COULD NOT COMPLETE"
                    selectedFile.text = getDisplayName(uri)
                    Toast.makeText(
                        this,
                        "Could not analyze this file: " + (error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderReport(report: AudioReport) {
        selectedFile.text = report.fileName
        waveformGraph.setWaveform(report.waveformMinimum, report.waveformMaximum)
        spectrumGraph.setSpectrum(report.spectrumDb, report.sampleRateHz)
        metricsContainer.removeAllViews()
        val items = listOf(
            Metric("Sample peak", formatDb(report.samplePeakDbfs), "dBFS · decoded samples"),
            Metric("True peak estimate", formatDb(report.truePeakEstimateDbtp), "dBTP · 4× linear interpolation"),
            Metric("Integrated loudness", report.integratedLufsEstimate?.let { formatDb(it) } ?: "N/A", "LUFS estimate · K-weighted"),
            Metric("RMS level", formatDb(report.rmsLevelDbfs), "dBFS · average level"),
            Metric("Crest factor", formatDb(report.crestFactorDb) + " dB", "Peak minus RMS"),
            Metric("Near-full-scale samples", report.clippedSampleCount.toString(), "Count of samples at or above 0.999"),
            Metric("Flat-top indicators", report.hardClipPlateauPairs.toString(), "Similar adjacent near-rail samples"),
            Metric("DC offset · L / R", formatSmall(report.dcOffsetLeft) + " / " + formatSmall(report.dcOffsetRight), "Mean sample value per channel"),
            Metric("Leading silence", formatSeconds(report.leadingSilenceSeconds), "Threshold-based estimate"),
            Metric("Trailing silence", formatSeconds(report.trailingSilenceSeconds), "Threshold-based estimate"),
            Metric("Level spread", report.shortTermLevelSpreadDb?.let { formatDb(it) + " dB" } ?: "N/A", "10th–95th percentile RMS windows"),
            Metric("Stereo correlation", report.stereoCorrelation?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "Mono", "−1 opposite · +1 similar"),
            Metric("Mono sum change", report.monoSumLevelChangeDb?.let { formatDb(it) + " dB" } ?: "N/A", "Relative to average channel RMS"),
            Metric("Tempo estimate", report.tempoBpmEstimate?.let { String.format(java.util.Locale.US, "%.1f BPM", it) } ?: "N/A", "Beat-envelope heuristic"),
            Metric("Musical key estimate", report.musicalKeyEstimate ?: "N/A", "Confidence: " + (report.keyConfidence ?: "low")),
            Metric("High-frequency cliff", report.estimatedSpectralCutoffHz?.let { it.toString() + " Hz" } ?: "Not detected", "Heuristic only; not proof of bitrate"),
        )
        for (pair in items.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(metricCard(pair[0]), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) })
            if (pair.size > 1) {
                row.addView(metricCard(pair[1]), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
            } else {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
            }
            metricsContainer.addView(row, fullWidth())
            addSpace(metricsContainer, 10)
        }

        val findings = ArrayList<Pair<String, Int>>()
        if (report.samplePeakDbfs >= -0.1) findings.add("Hot sample peaks: near the 0 dBFS limit. Check true-peak headroom." to amber)
        else findings.add("Sample peaks remain below digital full scale in this decode." to mint)
        if (report.clippedSampleCount > 0L) findings.add("Potential clipping evidence: " + report.clippedSampleCount + " samples are near full scale; this alone does not prove clipping." to amber)
        else findings.add("No near-full-scale sample events were detected." to mint)
        if (maxOf(abs(report.dcOffsetLeft), abs(report.dcOffsetRight)) > 0.001) findings.add("Measurable DC offset: inspect before applying a high-pass filter." to amber)
        else findings.add("No large mean DC offset was detected." to mint)
        if (report.leadingSilenceSeconds > 0.7) findings.add("Long lead-in silence: " + formatSeconds(report.leadingSilenceSeconds) + ". It may be intentional." to amber)
        if (report.trailingSilenceSeconds > 0.7) findings.add("Long tail silence: " + formatSeconds(report.trailingSilenceSeconds) + ". It may be intentional." to amber)
        if (report.stereoCorrelation != null && report.stereoCorrelation < -0.1) findings.add("Possible mono phase cancellation: correlation is negative; audition a mono fold-down." to red)
        if (report.estimatedSpectralCutoffHz != null) findings.add("Possible high-frequency cutoff near " + report.estimatedSpectralCutoffHz + " Hz. This does not establish source bitrate." to amber)
        if (report.titleTag == null || report.artistTag == null) findings.add("Some song tags were unavailable; title/artist may be guessed from the filename." to muted)
        findingsText.text = findings.joinToString("\n\n") { "• " + it.first }
        sourceDetailsText.text = buildString {
            appendLine("File: " + report.fileName)
            appendLine("Container codec: " + report.codec)
            appendLine("Duration: " + formatSeconds(report.durationSeconds))
            appendLine("Sample rate: " + report.sampleRateHz + " Hz")
            appendLine("Source channels: " + report.sourceChannels)
            appendLine("Bitrate: " + (report.bitRateBps?.let { (it / 1000).toString() + " kb/s" } ?: "unavailable"))
            appendLine("File size: " + if (report.fileSizeBytes >= 0L) formatBytes(report.fileSizeBytes) else "unavailable")
            appendLine("Title: " + (report.titleTag ?: "not found"))
            appendLine("Artist: " + (report.artistTag ?: "not found"))
            appendLine("Album: " + (report.albumTag ?: "not found"))
            appendLine("Embedded cover: " + if (report.coverArtPresent) "detected" else "not detected")
            if (report.decodeWarnings.isNotEmpty()) {
                appendLine()
                appendLine("Measurement caveats:")
                report.decodeWarnings.forEach { appendLine("• " + it) }
            }
        }.trim()
    }

    private data class Metric(val title: String, val value: String, val note: String)

    private fun metricCard(metric: Metric): View {
        val box = panel()
        box.addView(label(metric.title.uppercase(), 9f, muted, true))
        addSpace(box, 5)
        box.addView(label(metric.value, 20f, mint, true))
        addSpace(box, 3)
        box.addView(label(metric.note, 10f, muted, false))
        return box
    }

    private fun graphPanel(title: String, graph: AudioGraphView): View {
        val box = panel()
        box.addView(label(title, 10f, muted, true))
        addSpace(box, 8)
        box.addView(graph, fullWidth(dp(210)))
        return box
    }

    private fun addSection(parent: LinearLayout, kicker: String, title: String) {
        parent.addView(label(kicker, 10f, mint, true))
        addSpace(parent, 3)
        parent.addView(label(title, 22f, ink, true))
        addSpace(parent, 10)
    }

    private fun showEmptyState() {
        waveformGraph.clear()
        spectrumGraph.clear()
        metricsContainer.removeAllViews()
        val panel = panel()
        panel.addView(label("No measurements yet", 14f, ink, true))
        addSpace(panel, 5)
        panel.addView(label(
            "Select a track to calculate measurements and draw the two graphs. BPM, key, loudness and true-peak values will be labeled as estimates.",
            12f, muted, false
        ))
        metricsContainer.addView(panel, fullWidth())
        findingsText.text = "Audio Doctor will show measured flags here after decoding your song."
        sourceDetailsText.text = "No file loaded."
    }

    private fun exportReport() {
        if (currentReport == null || analysing) return
        val suggestedName = currentReport!!.fileName.substringBeforeLast('.', currentReport!!.fileName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_") + ".audio-doctor-report.json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_REPORT)
        } catch (_: Exception) {
            Toast.makeText(this, "This device has no compatible document saver.", Toast.LENGTH_LONG).show()
        }
    }

    private fun getDisplayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index) ?: "Selected audio"
                    }
                }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = true
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(cardColor, dp(15), border)
    }

    private fun button(title: String, primary: Boolean): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.rgb(4, 23, 16) else ink)
        background = rounded(if (primary) mint else cardColor, dp(12), if (primary) mint else border)
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun fullWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun addSpace(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun formatDb(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    private fun formatSmall(value: Double): String = String.format(java.util.Locale.US, "%.5f", value)
    private fun formatSeconds(value: Double): String = String.format(java.util.Locale.US, "%.2f s", value)
    private fun formatBytes(value: Long): String {
        if (value < 0) return "unavailable"
        if (value < 1024) return value.toString() + " B"
        if (value < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", value / 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
