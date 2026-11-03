package app.frame.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Composition guide drawn over the viewfinder. 0 = off, 1 = thirds, 2 = center frame. */
class GridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 232, 226, 210)
        strokeWidth = density
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (mode) {
            1 -> for (i in 1..2) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
            }
            2 -> {
                val cx = w / 2f
                val cy = h / 2f
                val arm = 14f * density
                canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
                val half = minOf(w, h) * 0.3f
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
            }
        }
    }
}
