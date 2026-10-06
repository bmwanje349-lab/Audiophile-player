package com.example.musicplayer

import android.content.Context
import com.example.peq.FilterType
import com.example.peq.PeqBand
import com.example.peq.PeqEngine
import com.example.peq.WidenerEngine
import org.json.JSONArray
import org.json.JSONObject

class EqSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsp_settings", Context.MODE_PRIVATE)

    fun loadGraphicGains(): FloatArray {
        val raw = prefs.getString(KEY_GRAPHIC, null) ?: return FloatArray(10)
        val values = raw.split(',').mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
        return FloatArray(10) { values.getOrNull(it) ?: 0f }
    }

    fun loadGraphicEnabled(): Boolean = prefs.getBoolean(KEY_GRAPHIC_ENABLED, false)

    fun saveGraphic(gains: FloatArray, enabled: Boolean) {
        prefs.edit()
            .putString(KEY_GRAPHIC, gains.joinToString(","))
            .putBoolean(KEY_GRAPHIC_ENABLED, enabled)
            .apply()
    }

    fun savePeq(peq: PeqEngine) {
        val array = JSONArray()
        peq.bands.forEach { band ->
            array.put(JSONObject().apply {
                put("type", band.type.id)
                put("freq", band.freq)
                put("gain", band.gainDb)
                put("q", band.q)
                put("enabled", band.enabled)
            })
        }
        prefs.edit()
            .putString(KEY_PEQ_BANDS, array.toString())
            .putBoolean(KEY_PEQ_ENABLED, peq.peqEnabled)
            .putBoolean(KEY_PEQ_LIMITER, peq.limiterEnabled)
            .putFloat(KEY_PEQ_CEILING, peq.limiterCeilingDb)
            .putFloat(KEY_PEQ_SAFETY, peq.limiterSafetyDb)
            .putBoolean(KEY_PEQ_AUTO, peq.autoGainEnabled)
            .putFloat(KEY_PEQ_AUTO_AMOUNT, peq.autoGainAmount)
            .putBoolean(KEY_PEQ_LINEAR, peq.linearPhaseEnabled)
            .putInt(KEY_PEQ_TAPS, peq.linearPhaseTaps)
            .apply()
    }

    fun applyPeq(peq: PeqEngine) {
        peq.peqEnabled = prefs.getBoolean(KEY_PEQ_ENABLED, true)
        peq.limiterEnabled = prefs.getBoolean(KEY_PEQ_LIMITER, true)
        peq.limiterCeilingDb = prefs.getFloat(KEY_PEQ_CEILING, -1f)
        peq.limiterSafetyDb = prefs.getFloat(KEY_PEQ_SAFETY, 0.10f)
        peq.autoGainEnabled = prefs.getBoolean(KEY_PEQ_AUTO, false)
        peq.autoGainAmount = prefs.getFloat(KEY_PEQ_AUTO_AMOUNT, 1f)
        peq.linearPhaseTaps = prefs.getInt(KEY_PEQ_TAPS, 513)
        peq.linearPhaseEnabled = prefs.getBoolean(KEY_PEQ_LINEAR, false)

        val raw = prefs.getString(KEY_PEQ_BANDS, null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until minOf(array.length(), peq.numBands)) {
            val item = array.optJSONObject(i) ?: continue
            val type = FilterType.entries.firstOrNull { it.id == item.optInt("type", 0) }
                ?: FilterType.PEAKING
            val band = PeqBand(
                type = type,
                freq = item.optDouble("freq", 1000.0).toFloat(),
                gainDb = item.optDouble("gain", 0.0).toFloat(),
                q = item.optDouble("q", 1.0).toFloat(),
                enabled = item.optBoolean("enabled", true),
            )
            peq.setBand(i, band)
        }
    }

    fun saveWidener(w: WidenerEngine) {
        prefs.edit()
            .putBoolean(KEY_W_ENABLED, w.enabled)
            .putFloat(KEY_W_WIDTH, w.width)
            .putFloat(KEY_W_LOW, w.lowCrossoverHz)
            .putFloat(KEY_W_HIGH, w.highCrossoverHz)
            .putFloat(KEY_W_BASS, w.bassMonoFrequencyHz)
            .putFloat(KEY_W_HAAS_DELAY, w.haasDelayMs)
            .putFloat(KEY_W_HAAS_MIX, w.haasMix)
            .putFloat(KEY_W_DRY_WET, w.dryWet)
            .putFloat(KEY_W_OUTPUT_GAIN, w.outputGainDb)
            .putFloat(KEY_W_CEILING, w.outputCeilingDb)
            .putFloat(KEY_W_SAFETY, w.limiterSafetyMarginDb)
            .putBoolean(KEY_W_AUTO, w.autoLevel)
            .apply()
    }

    fun applyWidener(w: WidenerEngine) {
        w.enabled = prefs.getBoolean(KEY_W_ENABLED, true)
        w.width = prefs.getFloat(KEY_W_WIDTH, 1f)
        w.lowCrossoverHz = prefs.getFloat(KEY_W_LOW, 180f)
        w.highCrossoverHz = prefs.getFloat(KEY_W_HIGH, 3200f)
        w.bassMonoFrequencyHz = prefs.getFloat(KEY_W_BASS, 80f)
        w.haasDelayMs = prefs.getFloat(KEY_W_HAAS_DELAY, 0f)
        w.haasMix = prefs.getFloat(KEY_W_HAAS_MIX, 0f)
        w.dryWet = prefs.getFloat(KEY_W_DRY_WET, 1f)
        w.outputGainDb = prefs.getFloat(KEY_W_OUTPUT_GAIN, 0f)
        w.outputCeilingDb = prefs.getFloat(KEY_W_CEILING, -1f)
        w.limiterSafetyMarginDb = prefs.getFloat(KEY_W_SAFETY, 0.25f)
        w.autoLevel = prefs.getBoolean(KEY_W_AUTO, true)
    }

    fun applyTo(peq: PeqEngine, widener: WidenerEngine) {
        applyPeq(peq)

        // Graphic EQ state is applied in one native batch.
        val gains = loadGraphicGains()
        com.example.peq.GraphicalEqController(peq).apply {
            setAll(gains)
            enabled = loadGraphicEnabled()
        }

        applyWidener(widener)
    }

    companion object {
        private const val KEY_GRAPHIC = "graphic_gains"
        private const val KEY_GRAPHIC_ENABLED = "graphic_enabled"
        private const val KEY_PEQ_BANDS = "peq_bands"
        private const val KEY_PEQ_ENABLED = "peq_enabled"
        private const val KEY_PEQ_LIMITER = "peq_limiter"
        private const val KEY_PEQ_CEILING = "peq_ceiling"
        private const val KEY_PEQ_SAFETY = "peq_safety"
        private const val KEY_PEQ_AUTO = "peq_auto"
        private const val KEY_PEQ_AUTO_AMOUNT = "peq_auto_amount"
        private const val KEY_PEQ_LINEAR = "peq_linear"
        private const val KEY_PEQ_TAPS = "peq_taps"

        private const val KEY_W_ENABLED = "w_enabled"
        private const val KEY_W_WIDTH = "w_width"
        private const val KEY_W_LOW = "w_low"
        private const val KEY_W_HIGH = "w_high"
        private const val KEY_W_BASS = "w_bass"
        private const val KEY_W_HAAS_DELAY = "w_haas_delay"
        private const val KEY_W_HAAS_MIX = "w_haas_mix"
        private const val KEY_W_DRY_WET = "w_dry_wet"
        private const val KEY_W_OUTPUT_GAIN = "w_output_gain"
        private const val KEY_W_CEILING = "w_ceiling"
        private const val KEY_W_SAFETY = "w_safety"
        private const val KEY_W_AUTO = "w_auto"
    }
}
