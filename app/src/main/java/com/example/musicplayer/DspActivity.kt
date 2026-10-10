package com.example.musicplayer

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.example.audio.ImmersionSettings
import com.example.audio.LoudnessSettings
import com.example.peq.FilterType
import com.example.peq.GraphicalEqController
import com.example.peq.GraphicalEqView
import com.example.peq.PeqEngine
import com.example.peq.PeqPresets
import com.example.peq.WidenerEngine
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

class DspActivity : AppCompatActivity() {

    private var section: String = "hub"
    private val peq: PeqEngine? get() = DspRuntime.peqEngine
    private val widener: WidenerEngine? get() = DspRuntime.widenerEngine
    private var geqController: GraphicalEqController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(EXTRA_SECTION) ?: "hub"
        title = when (section) {
            "graphic" -> "Graphic Equalizer"
            "parametric" -> "Parametric Equalizer"
            "stereo" -> "Stereo Widener"
            else -> "Sound"
        }
        setContentView(wrapScreen(when (section) {
            "graphic" -> buildGraphic()
            "parametric" -> buildParametric()
            "stereo" -> buildStereo()
            else -> buildHub()
        }))
    }

    override fun onPause() {
        super.onPause()
        saveDsp()
    }

    private fun wrapScreen(body: View): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = this@DspActivity.title
            setTitleTextColor(TEXT)
            setBackgroundColor(BG)
            navigationIcon = getDrawable(R.drawable.ic_arrow_back_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildHub(): View {
        val scroll = NestedScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "Shape your sound"
            textSize = 29f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Each processor has its own screen. Settings control the app; Sound controls the music."
            textSize = 14f
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(5), 0, dp(20))
        })

        root.addView(launchCard("Graphic Equalizer", "10 bands • fast, familiar tone shaping", "graphic"))
        root.addView(launchCard("Parametric Equalizer", "16 bands • frequency, gain, Q and filter type", "parametric"))
        root.addView(launchCard("Stereo Widener", "Professional spatial processing with protection", "stereo"))

        root.addView(sectionLabel("Engine overview"))
        root.addView(infoCard("Signal chain", "Graphic EQ → Parametric EQ → program-dependent curve makeup → v10 Stereo Widener → final output path"))
        root.addView(infoCard("Design principle", "The interface exposes musical controls. Filter design, smoothing, true-peak detection and stereo-safety processing stay inside the engine."))

        scroll.addView(root)
        return scroll
    }

    private fun launchCard(title: String, detail: String, type: String): View {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@DspActivity, DspActivity::class.java).putExtra(EXTRA_SECTION, type))
            }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(16))
        }
        val badge = TextView(this).apply {
            text = when (type) { "graphic" -> "GE"; "parametric" -> "PEQ"; else -> "ST" }
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            background = roundedDrawable(SURFACE_2, 16)
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(58), dp(58)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        col.addView(TextView(this).apply { text = title; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT) })
        col.addView(TextView(this).apply { text = detail; textSize = 13f; setTextColor(TEXT_SECONDARY); setPadding(0, dp(4), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 30f; setTextColor(TEXT_SECONDARY) })
        card.addView(row)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }
    }

    private fun buildGraphic(): View {
        val engine = peq ?: return unavailable()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(26))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(SwitchMaterial(this).apply {
            text = "Enable Graphic EQ"
            isChecked = engine.graphicEqEnabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> engine.graphicEqEnabled = on; saveDsp() }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        top.addView(MaterialButton(this).apply {
            text = "Presets"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { showGraphicPresets() }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(top)

        val controller = GraphicalEqController(engine, EqSettingsStore(this).loadGraphicGains())
        geqController = controller
        controller.enabled = engine.graphicEqEnabled
        val graph = GraphicalEqView(this).bind(controller)
        graph.setOnGainChangedListener { _, _ -> saveDsp() }
        root.addView(graph, LinearLayout.LayoutParams(-1, dp(330)))

        root.addView(infoCard("10-band Graphic EQ", "31 Hz • 62 Hz • 125 Hz • 250 Hz • 500 Hz • 1 kHz • 2 kHz • 4 kHz • 8 kHz • 16 kHz\nFixed Q 1.40 • ±12 dB • independent from the Parametric EQ"))
        root.addView(MaterialButton(this).apply {
            text = "Reset to Flat"
            setOnClickListener { controller.reset(); graph.bind(controller); engine.graphicEqEnabled = false; saveDsp() }
        }, LinearLayout.LayoutParams(-1, dp(50)))

        return NestedScrollView(this).apply { addView(root) }
    }

    private fun showGraphicPresets() {
        val names = GraphicalEqController.PRESETS.keys.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Graphic EQ preset")
            .setItems(names) { _, which ->
                geqController?.applyPreset(names[which])
                saveDsp()
            }
            .show()
    }

    private fun buildParametric(): View {
        val engine = peq ?: return unavailable()
        val scroll = NestedScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(26))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(SwitchMaterial(this).apply {
            text = "Enable Parametric EQ"
            isChecked = engine.peqEnabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> engine.peqEnabled = on; saveDsp() }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        top.addView(MaterialButton(this).apply {
            text = "Preset"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { showPeqPresets() }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(top)

        val processing = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val pcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)) }
        pcol.addView(SwitchMaterial(this).apply {
            text = "Automatic curve makeup"
            isChecked = engine.autoGainEnabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> engine.autoGainEnabled = on; saveDsp() }
        })
        pcol.addView(SwitchMaterial(this).apply {
            text = "Linear-phase FIR"
            isChecked = engine.linearPhaseEnabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> engine.linearPhaseEnabled = on; saveDsp() }
        })
        pcol.addView(TextView(this).apply {
            text = "FIR taps: ${engine.linearPhaseTaps} • Minimum-phase is the default."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            setPadding(dp(4), dp(2), dp(4), 0)
        })
        processing.addView(pcol)
        root.addView(processing, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        root.addView(sectionLabel("Bands"))
        engine.bands.forEachIndexed { index, band ->
            root.addView(buildBandCard(engine, index, band))
        }
        scroll.addView(root)
        return scroll
    }

    private fun buildBandCard(engine: PeqEngine, index: Int, initial: com.example.peq.PeqBand): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(10), dp(13), dp(11)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "Band ${index + 1}"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val bandSw = SwitchMaterial(this).apply {
            isChecked = initial.enabled
            setOnCheckedChangeListener { _, on -> engine.setBand(index, engine.bands[index].copy(enabled = on)); saveDsp() }
        }
        head.addView(bandSw)
        col.addView(head)

        val typeSpinner = Spinner(this)
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FilterType.entries.map { it.label })
        typeSpinner.setSelection(FilterType.entries.indexOfFirst { it == initial.type }.coerceAtLeast(0))
        typeSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0) engine.setBand(index, engine.bands[index].copy(type = FilterType.entries[position], enabled = bandSw.isChecked))
                saveDsp()
            }
        })
        col.addView(labeled("Filter type", typeSpinner))

        val freq = editValue("${initial.freq}", "Frequency (Hz)")
        val gain = editValue("${initial.gainDb}", "Gain (dB)")
        val q = editValue("${initial.q}", "Q")
        listOf(freq, gain, q).forEach { field ->
            field.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commitBand(engine, index, bandSw, freq, gain, q, typeSpinner)
            }
        }
        val edits = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        edits.addView(freq, LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(4) })
        edits.addView(gain, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        edits.addView(q, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(4) })
        col.addView(edits)
        card.addView(col)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }

    private fun commitBand(engine: PeqEngine, index: Int, sw: SwitchMaterial, f: EditText, g: EditText, q: EditText, spinner: Spinner) {
        val current = engine.bands[index]
        engine.setBand(index, current.copy(
            type = FilterType.entries[spinner.selectedItemPosition],
            freq = f.text.toString().toFloatOrNull() ?: current.freq,
            gainDb = g.text.toString().toFloatOrNull() ?: current.gainDb,
            q = q.text.toString().toFloatOrNull() ?: current.q,
            enabled = sw.isChecked,
        ))
        saveDsp()
    }

    private fun showPeqPresets() {
        val names = PeqPresets.all.keys.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Parametric EQ preset")
            .setItems(names) { _, which -> peq?.applyPreset(names[which]); recreate() }
            .show()
    }

    private fun buildStereo(): View {
        val engine = widener ?: return unavailable()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(28))
        }

        root.addView(SwitchMaterial(this).apply {
            text = "Enable Stereo Widener"
            isChecked = engine.enabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> engine.enabled = on; saveDsp() }
        }, LinearLayout.LayoutParams(-1, dp(54)))

        root.addView(sectionLabel("Immersion (psychoacoustic)"))
        root.addView(SwitchMaterial(this).apply {
            text = "Enable Immersion"
            isChecked = ImmersionSettings.enabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on ->
                ImmersionSettings.enabled = on
                ImmersionSettings.save(this@DspActivity)
            }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(bigSliderCard("Space (natural width)", 0f, 100f, ImmersionSettings.space * 100f, "${format(ImmersionSettings.space * 100f)}%") {
            ImmersionSettings.space = it / 100f
            ImmersionSettings.save(this)
        })
        root.addView(bigSliderCard("Ambience (depth)", 0f, 100f, ImmersionSettings.ambience * 100f, "${format(ImmersionSettings.ambience * 100f)}%") {
            ImmersionSettings.ambience = it / 100f
            ImmersionSettings.save(this)
        })
        root.addView(bigSliderCard("Air (sparkle)", 0f, 100f, ImmersionSettings.air * 100f, "${format(ImmersionSettings.air * 100f)}%") {
            ImmersionSettings.air = it / 100f
            ImmersionSettings.save(this)
        })
        root.addView(bigSliderCard("Bass depth", 0f, 100f, ImmersionSettings.bass * 100f, "${format(ImmersionSettings.bass * 100f)}%") {
            ImmersionSettings.bass = it / 100f
            ImmersionSettings.save(this)
        })
        root.addView(infoCard("Immersion", "Adds restrained high-frequency width, a short damped room cue, harmonic air, and bass harmonics. Keep changes modest and compare enabled/disabled at matched loudness."))

        root.addView(sectionLabel("Loudness / Power"))
        root.addView(SwitchMaterial(this).apply {
            text = "Loudness stage (preamp + limiter)"
            isChecked = LoudnessSettings.enabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> LoudnessSettings.enabled = on; LoudnessSettings.save(this@DspActivity) }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(bigSliderCard("Preamp", -12f, 12f, LoudnessSettings.preampDb, "${format(LoudnessSettings.preampDb)} dB") { LoudnessSettings.preampDb = it; LoudnessSettings.save(this) })
        root.addView(SwitchMaterial(this).apply {
            text = "Smart loudness (lift quiet songs)"
            isChecked = LoudnessSettings.smartEnabled
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> LoudnessSettings.smartEnabled = on; LoudnessSettings.save(this@DspActivity) }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(featureSlider("Target loudness", -20f, -8f, LoudnessSettings.targetLufs, "${format(LoudnessSettings.targetLufs)} LUFS") { LoudnessSettings.targetLufs = it; LoudnessSettings.save(this) })
        root.addView(featureSlider("Max boost", 0f, 15f, LoudnessSettings.maxBoostDb, "${format(LoudnessSettings.maxBoostDb)} dB") { LoudnessSettings.maxBoostDb = it; LoudnessSettings.save(this) })
        root.addView(featureSlider("Final ceiling", -6f, 0f, LoudnessSettings.ceilingDb, "${format(LoudnessSettings.ceilingDb)} dB") { LoudnessSettings.ceilingDb = it; LoudnessSettings.save(this) })
        root.addView(infoCard("How it works", "Songs are converted to 32-bit float, optionally lifted toward the target loudness, then passed through a look-ahead limiter that guarantees the ceiling is never exceeded - so you get a louder sound without clipping."))

        root.addView(sectionLabel("Core controls"))
        root.addView(bigSliderCard("Width", 0f, 250f, engine.width * 100f, "${format(engine.width * 100f)}%") { engine.width = it / 100f; saveDsp() })
        root.addView(bigSliderCard("Effect mix", 0f, 100f, engine.dryWet * 100f, "${format(engine.dryWet * 100f)}%") { engine.dryWet = it / 100f; saveDsp() })

        root.addView(sectionLabel("Bass protection"))
        root.addView(bigSliderCard("Bass mono point", 20f, 250f, engine.bassMonoFrequencyHz, "${format(engine.bassMonoFrequencyHz)} Hz") { engine.bassMonoFrequencyHz = it; saveDsp() })
        root.addView(infoCard("Why it is here", "Low frequencies are kept more compatible with stereo playback as width increases. The detailed M/S, crossover and correlation-safety processing remains inside v10."))

        root.addView(sectionLabel("Advanced"))
        root.addView(featureSlider("Low crossover", 40f, 400f, engine.lowCrossoverHz, "${format(engine.lowCrossoverHz)} Hz") { engine.lowCrossoverHz = it; saveDsp() })
        root.addView(featureSlider("High crossover", 1000f, 10000f, engine.highCrossoverHz, "${format(engine.highCrossoverHz)} Hz") { engine.highCrossoverHz = it; saveDsp() })
        root.addView(featureSlider("Haas delay", 0f, 20f, engine.haasDelayMs, "${format(engine.haasDelayMs)} ms") { engine.haasDelayMs = it; saveDsp() })
        root.addView(featureSlider("Haas mix", 0f, 100f, engine.haasMix * 100f, "${format(engine.haasMix * 100f)}%") { engine.haasMix = it / 100f; saveDsp() })
        root.addView(infoCard("Haas spatial cue", "Adds a short, high-passed delayed feed from centered audio into the stereo side field. Start around 8–12 ms and 25–50% mix for a restrained effect; low bass and the original center signal are preserved."))
        root.addView(featureSlider("Output gain", -12f, 6f, engine.outputGainDb, "${format(engine.outputGainDb)} dB") { engine.outputGainDb = it; saveDsp() })
        root.addView(featureSlider("Limiter ceiling", -12f, 0f, engine.outputCeilingDb, "${format(engine.outputCeilingDb)} dB") { engine.outputCeilingDb = it; saveDsp() })
        root.addView(SwitchMaterial(this).apply {
            text = "Automatic level matching"
            isChecked = engine.autoLevel
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, on -> engine.autoLevel = on; saveDsp() }
        }, LinearLayout.LayoutParams(-1, dp(54)))

        root.addView(infoCard("Latency", "Current v10 latency: ${engine.latencyFrames} frames at the active sample rate once the processor is prepared."))

        return NestedScrollView(this).apply { addView(root) }
    }

    private fun bigSliderCard(label: String, min: Float, max: Float, value: Float, valueText: String, onChange: (Float) -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(TEXT); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = TextView(this).apply { text = valueText; textSize = 14f; setTextColor(ACCENT); setTypeface(null, Typeface.BOLD) }
        row.addView(valueView)
        col.addView(row)
        val seek = SeekBar(this).apply {
            this.max = 1000
            progress = ((value - min) / (max - min) * 1000f).roundToInt().coerceIn(0, 1000)
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * (p / 1000f)
                    valueView.text = format(v) + when {
                        label == "Width" -> "%"
                        label.contains("mix", true) -> "%"
                        label.contains("delay", true) -> " ms"
                        label.contains("gain", true) || label.contains("ceiling", true) -> " dB"
                        else -> " Hz"
                    }
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        col.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        card.addView(col)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }

    private fun featureSlider(label: String, min: Float, max: Float, value: Float, valueText: String, onChange: (Float) -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(9)) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = TextView(this).apply { text = valueText; textSize = 13f; setTextColor(TEXT_SECONDARY) }
        row.addView(valueView)
        col.addView(row)
        val seek = SeekBar(this).apply {
            this.max = 1000
            progress = ((value - min) / (max - min) * 1000f).roundToInt().coerceIn(0, 1000)
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * (p / 1000f)
                    valueView.text = sliderValueText(label, v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        col.addView(seek, LinearLayout.LayoutParams(-1, dp(44)))
        card.addView(col)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }


    private fun sliderValueText(label: String, value: Float): String = when {
        label.equals("Haas delay", ignoreCase = true) -> "${format(value)} ms"
        label.contains("mix", ignoreCase = true) || label.equals("Width", ignoreCase = true) -> "${format(value)}%"
        label.contains("gain", ignoreCase = true) || label.contains("ceiling", ignoreCase = true) -> "${format(value)} dB"
        else -> "${format(value)} Hz"
    }

    private fun labeled(label: String, view: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(MUTED); setPadding(dp(3), dp(4), dp(3), 0) })
        col.addView(view, LinearLayout.LayoutParams(-1, dp(50)))
        return col
    }

    private fun editValue(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 13f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun infoCard(title: String, body: String): View {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(SURFACE)
            strokeWidth = 1
            strokeColor = BORDER
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        col.addView(TextView(this).apply { text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT) })
        col.addView(TextView(this).apply { text = body; textSize = 13f; setTextColor(TEXT_SECONDARY); setPadding(0, dp(5), 0, 0) })
        card.addView(col)
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }
    }

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(MUTED)
        setPadding(dp(2), dp(10), dp(2), dp(8))
    }

    private fun roundedDrawable(color: Int, radiusDp: Int): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun unavailable(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(TextView(this@DspActivity).apply {
            text = "Audio engine is starting…\nReturn to the player and open Sound again."
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(TEXT)
        })
    }

    private fun saveDsp() {
        val p = peq ?: return
        val w = widener ?: return
        val store = EqSettingsStore(this)
        val gains = geqController?.let { FloatArray(10) { i -> it.getGain(i) } } ?: store.loadGraphicGains()
        store.saveGraphic(gains, p.graphicEqEnabled)
        store.savePeq(p)
        store.saveWidener(w)
    }

    private fun format(v: Float): String = when {
        kotlin.math.abs(v) >= 100f -> "${v.roundToInt()}"
        kotlin.math.abs(v) >= 10f -> String.format("%.1f", v)
        else -> String.format("%.2f", v)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SECTION = "section"
        private const val BG = 0xFF0B0D10.toInt()
        private const val SURFACE = 0xFF14171C.toInt()
        private const val SURFACE_2 = 0xFF1B1F25.toInt()
        private const val TEXT = 0xFFF5F7FA.toInt()
        private const val TEXT_SECONDARY = 0xFFA7AFBA.toInt()
        private const val MUTED = 0xFF737C88.toInt()
        private const val BORDER = 0xFF272C33.toInt()
        private const val ACCENT = 0xFF54B8FF.toInt()
    }
}
