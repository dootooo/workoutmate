package com.example.workoutmate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight landing ripple overlay. Add a ripple at the detected foot
 * position; it expands and fades without allocating a view per event.
 */
class FootRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Ripple(
        val startedAt: Long,
        val x: Float,
        val y: Float
    )

    private val ripples = mutableListOf<Ripple>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun showAt(x: Float, y: Float) {
        ripples.add(Ripple(SystemClock.uptimeMillis(), x, y))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ripples.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val iterator = ripples.iterator()
        while (iterator.hasNext()) {
            val ripple = iterator.next()
            val progress = (now - ripple.startedAt) / DURATION_MS.toFloat()
            if (progress >= 1f) {
                iterator.remove()
                continue
            }
            val eased = 1f - (1f - progress) * (1f - progress)
            val radius = dp(8f) + dp(64f) * eased
            val alpha = ((1f - progress) * 220).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, 212, 255, 77)
            paint.strokeWidth = dp(2.4f)
            canvas.drawCircle(ripple.x, ripple.y, radius, paint)
            paint.color = Color.argb((alpha * 0.45f).toInt(), 0, 229, 255)
            paint.strokeWidth = dp(1.4f)
            canvas.drawCircle(ripple.x, ripple.y, radius * 0.62f, paint)
        }
        if (ripples.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val DURATION_MS = 720L
    }
}
