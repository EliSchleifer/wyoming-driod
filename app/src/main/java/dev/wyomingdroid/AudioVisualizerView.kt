package dev.wyomingdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/** Live microphone level meter and waveform for the on-device mic preview. */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bounds = RectF()
    private val clipPath = Path()
    private val waveFillPath = Path()
    private val waveStrokePath = Path()
    private val idlePath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        color = 0x18FFFFFF
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val overlayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
    }

    private val waveform = FloatArray(MicLevelMonitor.WAVEFORM_SIZE)
    private val smoothed = FloatArray(MicLevelMonitor.WAVEFORM_SIZE)

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

    /** When set, shown instead of level/peak (errors, disconnect messages). */
    var statusMessage: String? = null
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

        val inset = dp(2f)
        bounds.set(inset, inset, w - inset, h - inset)
        val radius = dp(12f)
        clipPath.reset()
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        drawBackground(canvas, w, h)
        drawGrid(canvas, w, h)

        val waveTop = bounds.top + dp(10f)
        val waveBottom = bounds.bottom - dp(28f)
        if (active) {
            drawLiveWaveform(canvas, bounds.left + dp(8f), bounds.right - dp(8f), waveTop, waveBottom)
        } else {
            drawIdleWaveform(canvas, bounds.left + dp(8f), bounds.right - dp(8f), waveTop, waveBottom)
        }
        drawOverlay(canvas)

        canvas.restore()
    }

    private fun drawOverlay(canvas: Canvas) {
        val baseline = bounds.bottom - dp(10f)

        statusMessage?.let { msg ->
            overlayPaint.textSize = sp(12f)
            overlayPaint.color = 0xFFFFCC80.toInt()
            canvas.drawText(msg, bounds.centerX(), baseline, overlayPaint)
            return
        }

        val text = if (active) {
            context.getString(
                R.string.live_view_level,
                (level * 100).toInt(),
                (peak * 100).toInt(),
            )
        } else {
            context.getString(R.string.live_view_idle)
        }

        overlayPaint.textSize = sp(11f)
        overlayPaint.color = if (active) 0xCCFFFFFF.toInt() else 0x88FFFFFF.toInt()
        val textWidth = overlayPaint.measureText(text)
        val bgPadH = dp(8f)
        val bgPadV = dp(4f)
        val bgLeft = bounds.centerX() - textWidth / 2f - bgPadH
        val bgRight = bounds.centerX() + textWidth / 2f + bgPadH
        val bgTop = baseline - overlayPaint.textSize - bgPadV
        val bgBottom = baseline + bgPadV
        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, dp(8f), dp(8f), overlayBgPaint)
        canvas.drawText(text, bounds.centerX(), baseline, overlayPaint)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0B1220.toInt(), 0xFF141E2E.toInt(), 0xFF0D1524.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null

        if (processing) {
            val t = SystemClock.uptimeMillis() / 1000f
            val colors = IntArray(6) { i ->
                Color.HSVToColor(
                    140,
                    floatArrayOf((t * 90f + i * 60f) % 360f, 0.55f, 0.95f),
                )
            }
            bgPaint.shader = LinearGradient(0f, 0f, w, h, colors, null, Shader.TileMode.MIRROR)
            bgPaint.alpha = 48
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            bgPaint.alpha = 255
            bgPaint.shader = null
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val midY = h / 2f
        canvas.drawLine(bounds.left + dp(8f), midY, bounds.right - dp(8f), midY, gridPaint)
        val step = h / 4f
        for (i in 1..3) {
            val y = step * i
            gridPaint.alpha = if (i == 2) 36 else 18
            canvas.drawLine(bounds.left + dp(8f), y, bounds.right - dp(8f), y, gridPaint)
        }
        gridPaint.alpha = 255
    }

    private fun drawLiveWaveform(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        MicLevelMonitor.snapshotWaveform(waveform)
        smoothWaveform(waveform, smoothed)

        val midY = (top + bottom) / 2f
        val maxAmp = (bottom - top) * 0.44f
        val phase = SystemClock.uptimeMillis() / 1000f

        buildSymmetricWavePaths(smoothed, left, right, midY, maxAmp)

        val waveColors = if (processing) {
            IntArray(4) { i ->
                Color.HSVToColor(
                    200,
                    floatArrayOf((phase * 120f + i * 90f) % 360f, 0.7f, 1f),
                )
            }
        } else {
            intArrayOf(0x00000000, 0x6600E5FF, 0xAA1A73E8.toInt(), 0x557C4DFF)
        }

        fillPaint.shader = LinearGradient(0f, top, 0f, bottom, waveColors, null, Shader.TileMode.CLAMP)
        canvas.drawPath(waveFillPath, fillPaint)
        fillPaint.shader = null

        val strokeColors = if (processing) {
            IntArray(3) { i ->
                Color.HSVToColor(floatArrayOf((phase * 120f + i * 120f) % 360f, 0.75f, 1f))
            }
        } else {
            intArrayOf(0xFF00E5FF.toInt(), 0xFF64B5F6.toInt(), 0xFFB388FF.toInt())
        }

        glowPaint.strokeWidth = dp(6f)
        glowPaint.shader = LinearGradient(left, midY, right, midY, strokeColors, null, Shader.TileMode.CLAMP)
        glowPaint.alpha = 70
        canvas.drawPath(waveStrokePath, glowPaint)
        glowPaint.alpha = 255

        strokePaint.strokeWidth = dp(2.5f)
        strokePaint.shader = LinearGradient(left, midY, right, midY, strokeColors, null, Shader.TileMode.CLAMP)
        canvas.drawPath(waveStrokePath, strokePaint)
        strokePaint.shader = null
        glowPaint.shader = null
    }

    private fun drawIdleWaveform(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val midY = (top + bottom) / 2f
        val amp = (bottom - top) * 0.08f
        val t = SystemClock.uptimeMillis() / 900f
        val points = 64
        val step = (right - left) / (points - 1)

        idlePath.reset()
        for (i in 0 until points) {
            val x = left + step * i
            val norm = i.toFloat() / (points - 1)
            val y = midY + sin((norm * 4f * Math.PI + t).toFloat()) * amp * (0.35f + 0.65f * sin(t * 0.7f + norm * 3f))
            if (i == 0) idlePath.moveTo(x, y) else idlePath.lineTo(x, y)
        }

        strokePaint.strokeWidth = dp(2f)
        strokePaint.shader = LinearGradient(
            left, midY, right, midY,
            intArrayOf(0x00000000, 0x553C4F6B, 0x553C4F6B, 0x00000000),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(idlePath, strokePaint)
        strokePaint.shader = null
    }

    private fun buildSymmetricWavePaths(
        samples: FloatArray,
        left: Float,
        right: Float,
        midY: Float,
        maxAmp: Float,
    ) {
        val n = samples.size
        if (n < 2) return

        val step = (right - left) / (n - 1)
        val xs = FloatArray(n) { i -> left + step * i }
        val amps = FloatArray(n) { i ->
            val boosted = samples[i].coerceIn(0f, 1f)
            (boosted * boosted * 0.65f + boosted * 0.35f) * maxAmp
        }

        waveFillPath.reset()
        waveStrokePath.reset()

        waveFillPath.moveTo(xs[0], midY)
        waveStrokePath.moveTo(xs[0], midY - amps[0])
        for (i in 0 until n - 1) {
            val cx = (xs[i] + xs[i + 1]) / 2f
            val topY = midY - (amps[i] + amps[i + 1]) / 2f
            waveFillPath.quadTo(xs[i], midY - amps[i], cx, topY)
            waveStrokePath.quadTo(xs[i], midY - amps[i], cx, topY)
        }
        waveFillPath.lineTo(xs[n - 1], midY - amps[n - 1])
        waveStrokePath.lineTo(xs[n - 1], midY - amps[n - 1])

        waveFillPath.lineTo(xs[n - 1], midY)
        for (i in n - 1 downTo 1) {
            val cx = (xs[i] + xs[i - 1]) / 2f
            val botY = midY + (amps[i] + amps[i - 1]) / 2f
            waveFillPath.quadTo(xs[i], midY + amps[i], cx, botY)
        }
        waveFillPath.lineTo(xs[0], midY + amps[0])
        waveFillPath.close()
    }

    private fun smoothWaveform(input: FloatArray, output: FloatArray) {
        val n = minOf(input.size, output.size)
        if (n == 0) return
        for (i in 0 until n) {
            val prev = input[(i - 1 + n) % n]
            val cur = input[i]
            val next = input[(i + 1) % n]
            output[i] = prev * 0.2f + cur * 0.6f + next * 0.2f
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
