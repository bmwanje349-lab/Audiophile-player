package com.example.peq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.round

/**
 * Simple 10-band graphic equalizer with its own native filter bank.
 * It is independent from the advanced PEQ stage: changing or bypassing one
 * does not rewrite the other stage's bands or filter states.
 *
 * Center frequencies:
 * 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
 *
 * Each graphic band is implemented as a fixed-frequency peaking biquad with
 * a fixed Q, while the user only controls the gain. The native engine smooths
 * the coefficients independently from the advanced PEQ bank.
 */
class GraphicalEqController(
    private val engine: PeqEngine,
    initialGainsDb: FloatArray = FloatArray(FREQUENCIES_HZ.size),
    private val bandQ: Float = 1.40f,
    private val minGainDb: Float = -12f,
    private val maxGainDb: Float = 12f,
) {
    private val gainsDb = initialGainsDb.copyOf()

    companion object {
        val FREQUENCIES_HZ = floatArrayOf(
            31f, 62f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f
        )

        val PRESETS: Map<String, FloatArray> = linkedMapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble Boost" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 3f, 5f, 5f),
            "Vocal" to floatArrayOf(-2f, -1f, -1f, 0f, 1f, 3f, 3f, 2f, 0f, -1f),
            "Rock" to floatArrayOf(4f, 3f, 1f, -1f, -2f, -1f, 2f, 3f, 4f, 4f),
        )
    }

    /** Independent Graphic EQ switch. PEQ settings and states are untouched. */
    var enabled: Boolean
        get() = engine.graphicEqEnabled
        set(value) { engine.graphicEqEnabled = value }

    init {
        require(initialGainsDb.size == FREQUENCIES_HZ.size) {
            "Expected ${FREQUENCIES_HZ.size} initial gain values"
        }
        require(engine.numBands >= 1) { "PeqEngine must have at least one native PEQ band" }
        require(bandQ.isFinite() && bandQ > 0f) { "bandQ must be finite and positive" }
        require(minGainDb.isFinite() && maxGainDb.isFinite() && minGainDb <= maxGainDb) {
            "Invalid gain range"
        }
        for (i in gainsDb.indices) {
            gainsDb[i] = (gainsDb[i].takeIf { it.isFinite() } ?: 0f)
                .coerceIn(minGainDb, maxGainDb)
        }
        engine.setGraphicBands(gainsDb, bandQ)
    }

    fun setGain(band: Int, gainDb: Float) {
        require(band in FREQUENCIES_HZ.indices) { "band out of range" }
        val finiteGain = gainDb.takeIf { it.isFinite() } ?: 0f
        val gain = finiteGain.coerceIn(minGainDb, maxGainDb)
        gainsDb[band] = gain
        engine.setGraphicBand(
            index = band,
            gainDb = gain,
            q = bandQ,
            enabled = abs(gain) > 0.0001f,
        )
    }

    fun getGain(band: Int): Float {
        require(band in FREQUENCIES_HZ.indices) { "band out of range" }
        return gainsDb[band]
    }

    fun setAll(gainsDb: FloatArray) {
        require(gainsDb.size == FREQUENCIES_HZ.size) {
            "Expected ${FREQUENCIES_HZ.size} gain values"
        }
        for (i in gainsDb.indices) {
            val value = gainsDb[i].takeIf { it.isFinite() } ?: 0f
            this.gainsDb[i] = value.coerceIn(minGainDb, maxGainDb)
        }
        engine.setGraphicBands(this.gainsDb, bandQ)
    }

    fun reset() {
        java.util.Arrays.fill(gainsDb, 0f)
        engine.setGraphicBands(gainsDb, bandQ)
    }

    fun applyPreset(name: String): Boolean {
        val preset = PRESETS[name] ?: return false
        setAll(preset)
        return true
    }
}

/**
 * Lightweight Android View for the graphic EQ.
 * No Compose or third-party UI dependency is required.
 */
class GraphicalEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private val FREQUENCIES_HZ = GraphicalEqController.FREQUENCIES_HZ
        private const val MIN_DB = -12f
        private const val MAX_DB = 12f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val gains = FloatArray(FREQUENCIES_HZ.size)
    private var activeBand = -1
    private var controller: GraphicalEqController? = null
    private var onGainChanged: ((band: Int, gainDb: Float) -> Unit)? = null

    private var lastWidth = 0f
    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f
    private var centerY = 0f

    init {
        isClickable = true
        isFocusable = true

        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = dp(2f)

        fillPaint.style = Paint.Style.FILL

        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = dp(1f)
        gridPaint.alpha = 80

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(10f)

        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.textSize = sp(11f)
        valuePaint.isFakeBoldText = true
    }

    fun bind(controller: GraphicalEqController): GraphicalEqView {
        this.controller = controller
        for (i in gains.indices) {
            gains[i] = controller.getGain(i)
        }
        invalidate()
        return this
    }

    fun setOnGainChangedListener(listener: ((band: Int, gainDb: Float) -> Unit)?) {
        onGainChanged = listener
    }

    fun setGain(band: Int, gainDb: Float, notify: Boolean = true) {
        if (band !in gains.indices) return
        val gain = snapGain(gainDb)
        gains[band] = gain
        if (notify) {
            controller?.setGain(band, gain)
            onGainChanged?.invoke(band, gain)
        }
        invalidate()
    }

    fun getGain(band: Int): Float = if (band in gains.indices) gains[band] else 0f

    fun reset() {
        gains.fill(0f)
        controller?.reset()
        invalidate()
    }

    fun applyPreset(name: String): Boolean {
        val preset = GraphicalEqController.PRESETS[name] ?: return false
        for (i in gains.indices) gains[i] = snapGain(preset[i])
        controller?.setAll(gains.copyOf())
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastWidth = w.toFloat()
        left = paddingLeft + dp(16f)
        right = w - paddingRight - dp(16f)
        top = paddingTop + dp(28f)
        bottom = h - paddingBottom - dp(28f)
        centerY = top + (bottom - top) * 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = right - left
        if (width <= 0f || bottom <= top) return

        drawGrid(canvas)

        for (i in gains.indices) {
            val x = xForBand(i)
            val y = yForGain(gains[i])
            val active = i == activeBand

            trackPaint.alpha = if (active) 255 else 190
            canvas.drawLine(x, top, x, bottom, trackPaint)

            fillPaint.alpha = 210
            val knobRadius = if (active) dp(8f) else dp(7f)
            canvas.drawCircle(x, y, knobRadius, fillPaint)

            textPaint.alpha = 220
            canvas.drawText(freqLabel(FREQUENCIES_HZ[i]), x, bottom + dp(18f), textPaint)

            valuePaint.alpha = 255
            canvas.drawText(formatGain(gains[i]), x, top - dp(8f), valuePaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val dbLines = floatArrayOf(-12f, -6f, 0f, 6f, 12f)
        for (db in dbLines) {
            val y = yForGain(db)
            gridPaint.alpha = if (db == 0f) 150 else 70
            canvas.drawLine(left, y, right, y, gridPaint)
            textPaint.alpha = 120
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                if (db > 0f) "+${db.toInt()}" else db.toInt().toString(),
                left,
                y - dp(4f),
                textPaint
            )
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val band = nearestBand(event.x)
                activeBand = band
                setGain(band, gainForY(event.y))
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val band = if (activeBand >= 0) activeBand else nearestBand(event.x)
                setGain(band, gainForY(event.y))
                activeBand = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestBand(x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in gains.indices) {
            val d = abs(x - xForBand(i))
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun xForBand(index: Int): Float {
        if (gains.size == 1) return (left + right) * 0.5f
        return left + (right - left) * index / (gains.size - 1).toFloat()
    }

    private fun yForGain(gainDb: Float): Float {
        val t = ((gainDb.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB))
        return bottom - t * (bottom - top)
    }

    private fun gainForY(y: Float): Float {
        val clampedY = y.coerceIn(top, bottom)
        val t = 1f - (clampedY - top) / (bottom - top)
        return snapGain(MIN_DB + t * (MAX_DB - MIN_DB))
    }

    private fun snapGain(value: Float): Float {
        // 0.5 dB steps are simple, predictable and easy to control by touch.
        return (round(value * 2f) / 2f).coerceIn(MIN_DB, MAX_DB)
    }

    private fun freqLabel(freq: Float): String = when {
        freq >= 1000f -> {
            val k = freq / 1000f
            if (k >= 10f) "${k.toInt()}k" else "${k}k".removeSuffix(".0k")
        }
        else -> freq.toInt().toString()
    }

    private fun formatGain(db: Float): String {
        val snapped = (round((db.takeIf { it.isFinite() } ?: 0f) * 2f) / 2f)
        val scaled = round(kotlin.math.abs(snapped) * 2f).toInt()
        val magnitude = if (scaled % 2 == 0) {
            (scaled / 2).toString()
        } else {
            "${scaled / 2}.5"
        }
        return when {
            scaled == 0 -> "0 dB"
            snapped > 0f -> "+$magnitude dB"
            else -> "-$magnitude dB"
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
