package dev.wyomingdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Live microphone level meter and waveform for the on-device Live view. */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.colorPrimary)
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.colorPrimaryDark)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.colorPrimary)
    }
    private val bgPaint = Paint().apply { color = 0xFFF0F4F8.toInt() }
    private val wavePath = Path()

    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var peak = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    var processing = false
        set(value) {
            field = value
            invalidate()
        }

    fun updateFromMonitor() {
        level = MicLevelMonitor.level
        peak = MicLevelMonitor.peak
        active = level > 0.001f || peak > 0.001f || processing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (processing) {
            val colors = intArrayOf(
                0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
                0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
            )
            bgPaint.shader = LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.MIRROR)
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            bgPaint.shader = null
        }

        val barH = h * 0.18f
        drawLevelMeter(canvas, w, barH)
        drawWaveform(canvas, w, barH + dp(12f), h - barH - dp(20f))
    }

    private fun drawLevelMeter(canvas: Canvas, width: Float, height: Float) {
        val inset = dp(16f)
        val barLeft = inset
        val barRight = width - inset
        val barTop = dp(8f)
        val barBottom = height
        canvas.drawRect(barLeft, barTop, barRight, barBottom, barPaint.apply { alpha = 40 })
        if (!active) return
        canvas.drawRect(barLeft, barTop, barLeft + (barRight - barLeft) * level, barBottom, barPaint.apply { alpha = 255 })
        val peakX = barLeft + (barRight - barLeft) * peak
        canvas.drawLine(peakX, barTop, peakX, barBottom, peakPaint)
    }

    private fun drawWaveform(canvas: Canvas, width: Float, top: Float, height: Float) {
        val inset = dp(16f)
        val left = inset
        val right = width - inset
        val midY = top + height / 2f
        canvas.drawLine(left, midY, right, midY, peakPaint.apply { alpha = 60 })
        if (!active) return

        val waveform = FloatArray(MicLevelMonitor.WAVEFORM_SIZE)
        MicLevelMonitor.snapshotWaveform(waveform)
        wavePath.reset()
        val step = (right - left) / (waveform.size - 1).coerceAtLeast(1)
        waveform.forEachIndexed { index, value ->
            val x = left + step * index
            val y = midY - value * height * 0.45f
            if (index == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
