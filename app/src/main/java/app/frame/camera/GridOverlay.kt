package app.frame.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Composition guides drawn over the viewfinder.
 * 0 off, 1 thirds, 2 square cells, 3 golden ratio, 4 center frame.
 */
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
        color = Color.argb(150, 241, 238, 231)
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
                val cell = w / 4f
                var x = cell
                while (x < w - 1f) {
                    canvas.drawLine(x, 0f, x, h, paint)
                    x += cell
                }
                var y = (h % cell) / 2f + cell
                while (y < h - 1f) {
                    canvas.drawLine(0f, y, w, y, paint)
                    y += cell
                }
            }
            3 -> {
                val a = 0.382f
                val b = 0.618f
                canvas.drawLine(w * a, 0f, w * a, h, paint)
                canvas.drawLine(w * b, 0f, w * b, h, paint)
                canvas.drawLine(0f, h * a, w, h * a, paint)
                canvas.drawLine(0f, h * b, w, h * b, paint)
            }
            4 -> {
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
