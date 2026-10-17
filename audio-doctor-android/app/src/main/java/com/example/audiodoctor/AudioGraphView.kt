package com.example.audiodoctor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class AudioGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Mode { WAVEFORM, SPECTRUM }

    var mode: Mode = Mode.WAVEFORM
        set(value) { field = value; invalidate() }

    private var minimum = emptyList<Float>()
    private var maximum = emptyList<Float>()
    private var spectrum = emptyList<Float>()
    private var sampleRate = 44100
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 48, 62)
        strokeWidth = dp(1).toFloat()
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(103, 242, 194)
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 187, 155)
        alpha = 65
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 132, 151)
        textSize = dp(10).toFloat()
    }
    private val path = Path()

    fun setWaveform(mins: List<Float>, maxs: List<Float>) {
        minimum = mins
        maximum = maxs
        mode = Mode.WAVEFORM
        invalidate()
    }

    fun setSpectrum(values: List<Float>, rate: Int) {
        spectrum = values
        sampleRate = rate
        mode = Mode.SPECTRUM
        invalidate()
    }

    fun clear() {
        minimum = emptyList()
        maximum = emptyList()
        spectrum = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(10).toFloat()
        val right = width - dp(10).toFloat()
        val top = dp(12).toFloat()
        val bottom = height - dp(24).toFloat()
        canvas.drawColor(Color.rgb(10, 16, 24))
        if (right <= left || bottom <= top) return
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        if (mode == Mode.WAVEFORM) drawWaveform(canvas, left, right, top, bottom)
        else drawSpectrum(canvas, left, right, top, bottom)
    }

    private fun drawWaveform(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (minimum.isEmpty() || maximum.isEmpty()) {
            drawEmpty(canvas, "Choose a song to draw its waveform")
            return
        }
        val center = (top + bottom) / 2f
        canvas.drawLine(left, center, right, center, gridPaint)
        val count = min(minimum.size, maximum.size)
        val binWidth = (right - left) / max(1, count)
        path.reset()
        for (i in 0 until count) {
            val x = left + (i + 0.5f) * binWidth
            val high = (center - maximum[i].coerceIn(-1f, 1f) * (bottom - top) * 0.46f)
            val low = (center - minimum[i].coerceIn(-1f, 1f) * (bottom - top) * 0.46f)
            canvas.drawLine(x, high, x, low, linePaint)
        }
        canvas.drawText("−1.0", left, top - dp(1), textPaint)
        canvas.drawText("+1.0", left, bottom + dp(16), textPaint)
        canvas.drawText("TIME →", right - dp(44), bottom + dp(16), textPaint)
    }

    private fun drawSpectrum(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (spectrum.isEmpty()) {
            drawEmpty(canvas, "Frequency spectrum appears after analysis")
            return
        }
        val nyquist = max(100.0, sampleRate / 2.0)
        val minimumFrequency = 25.0
        val maximumFrequency = min(20000.0, nyquist)
        val maxDb = spectrum.maxOrNull()?.toDouble() ?: 0.0
        fun xForFrequency(frequency: Double): Float {
            val ratio = ln((frequency / minimumFrequency).coerceAtLeast(1.0)) /
                ln((maximumFrequency / minimumFrequency).coerceAtLeast(1.001))
            return left + ((right - left) * ratio).toFloat()
        }
        path.reset()
        var started = false
        for (bin in spectrum.indices) {
            val frequency = bin.toDouble() * sampleRate / 8192.0
            if (frequency < minimumFrequency || frequency > maximumFrequency) continue
            val relativeDb = (spectrum[bin] - maxDb).toDouble().coerceIn(-75.0, 0.0)
            val y = bottom - ((relativeDb + 75.0) / 75.0 * (bottom - top)).toFloat()
            val x = xForFrequency(frequency)
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        if (!started) {
            drawEmpty(canvas, "No usable spectrum bins")
            return
        }
        val areaPath = Path(path)
        areaPath.lineTo(right, bottom)
        areaPath.lineTo(left, bottom)
        areaPath.close()
        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(path, linePaint)
        val marks = listOf(40.0, 100.0, 250.0, 1000.0, 4000.0, 10000.0, 16000.0)
        for (frequency in marks) {
            if (frequency > maximumFrequency) continue
            val x = xForFrequency(frequency)
            val label = if (frequency >= 1000.0) (frequency / 1000.0).toInt().toString() + "k" else frequency.toInt().toString()
            canvas.drawText(label, (x - dp(4)).coerceIn(left, right - dp(22)), bottom + dp(16), textPaint)
        }
        canvas.drawText("RELATIVE MAGNITUDE", left, top - dp(1), textPaint)
    }

    private fun drawEmpty(canvas: Canvas, message: String) {
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(message, width / 2f, height / 2f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val targetHeight = (210 * resources.displayMetrics.density).toInt()
        val h = resolveSize(targetHeight, heightMeasureSpec)
        val w = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, h)
    }
}
