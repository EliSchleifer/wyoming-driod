package dev.wyomingdroid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** Live indicator shown beside the app title. */
class StatusDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var blinkAlpha = 1f

    var live: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startBlink() else stopBlink()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = if (live) {
            Color.argb((255 * blinkAlpha).toInt(), 229, 57, 53)
        } else {
            Color.parseColor("#424242")
        }
        val radius = (minOf(width, height) / 2f) - paint.strokeWidth
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }

    private fun startBlink() {
        animator?.cancel()
        blinkAlpha = 1f
        animator = ValueAnimator.ofFloat(1f, 0.3f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                blinkAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopBlink() {
        animator?.cancel()
        animator = null
        blinkAlpha = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopBlink()
        super.onDetachedFromWindow()
    }
}
