package dev.wyomingdroid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

/**
 * Status banner container that animates a rainbow background while Assist is
 * actively listening / processing a voice command.
 */
class RainbowBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hueOffset = 0f
    private var animator: ValueAnimator? = null

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startAnimation() else stopAnimation()
            invalidate()
        }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                hueOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (active) {
            val w = width.toFloat()
            if (w > 0f) {
                val colors = IntArray(RAINBOW_STOPS) { i ->
                    Color.HSVToColor(
                        floatArrayOf((hueOffset + i * 360f / RAINBOW_STOPS) % 360f, 0.85f, 0.95f),
                    )
                }
                paint.shader = LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.MIRROR)
                canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
            }
        } else {
            canvas.drawColor(0xFFF5F5F5.toInt())
        }
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val RAINBOW_STOPS = 7
    }
}
