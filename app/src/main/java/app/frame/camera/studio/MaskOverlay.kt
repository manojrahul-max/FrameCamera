package app.frame.camera.studio

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.BlurMaskFilter
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class BrushSettings(var size: Float = 0.09f, var softness: Float = 0.5f, var strength: Float = 1f, var erase: Boolean = false)

/**
 * Editing aid drawn over the live camera view. It only shows where the region is and takes the touches
 * that move or paint it. The effect itself is rendered by the GPU in the camera pipeline, and
 * this overlay is hidden when a picture is taken.
 */
class MaskOverlay(ctx: Context) : View(ctx) {

    interface Host {
        fun layer(): CLayer?
        fun brush(): BrushSettings
        fun begin()
        fun changed(strokes: Boolean)
        fun showMask(): Boolean
    }

    var host: Host? = null
    private val dp = resources.displayMetrics.density
    private val amber = 0xFFEDA94C.toInt()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = amber; strokeWidth = 2f * dp; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = amber; strokeWidth = 2.5f * dp }
    private val F = CustomSchema
    private var drawing = false
    private var points = ArrayList<Float>()
    private var twist = false
    private var lastAngle = 0f
    private var began = false

    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val l = host?.layer() ?: return false
            if (l.maskType != 2 && l.maskType != 3) return false
            if (!began) { host?.begin(); began = true }
            for (k in listOf("radius", "height")) { val f = F.field(k); l.set(f, l.get(f) * d.scaleFactor) }
            host?.changed(false); invalidate(); return true
        }
    })

    private fun asp() = width.toFloat() / Math.max(1, height)
    private fun sx(px: Float) = (px / asp() + 0.5f) * width
    private fun sy(py: Float) = (0.5f - py) * height

    override fun onDraw(c: Canvas) {
        val l = host?.layer() ?: return
        val w = width.toFloat(); val h = height.toFloat()
        when (l.maskType) {
            1 -> {
                val a = l.get(F.field("maskAngle")); val off = l.get(F.field("maskOffset")); val cur = l.get(F.field("maskCurve"))
                val ca = Math.cos(a.toDouble()).toFloat(); val sa = Math.sin(a.toDouble()).toFloat()
                val path = Path(); var first = true
                var mx = 0f; var my = 0f
                var u = -1.4f
                while (u <= 1.4f) {
                    val rx = off * asp() * 0.5f + cur * u * u * 2f
                    val px = ca * rx - sa * u; val py = sa * rx + ca * u
                    val x = sx(px); val y = sy(py)
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    if (Math.abs(u) < 0.04f) { mx = x; my = y }
                    u += 0.05f
                }
                c.drawPath(path, line)
                c.drawCircle(mx, my, 11 * dp, fill); c.drawCircle(mx, my, 11 * dp, ring)
            }
            2 -> {
                val cx = (l.get(F.field("cx")) + 0.5f) * w; val cy = (0.5f - l.get(F.field("cy"))) * h
                val rx = l.get(F.field("radius")) * h; val ry = l.get(F.field("height")) * h
                c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), line)
                c.drawCircle(cx, cy, 8 * dp, fill); c.drawCircle(cx, cy, 8 * dp, ring)
            }
            3 -> {
                val cx = (l.get(F.field("cx")) + 0.5f) * w; val cy = (0.5f - l.get(F.field("cy"))) * h
                val hw = l.get(F.field("radius")) * w * 0.5f; val hh = l.get(F.field("height")) * h * 0.5f
                c.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, line)
                c.drawCircle(cx, cy, 8 * dp, fill); c.drawCircle(cx, cy, 8 * dp, ring)
            }
            4 -> if (host?.showMask() == true) drawBrushHint(c, l)
        }
    }

    private fun strokePaint(s: Stroke, erase: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = Math.max(1f, s.size * width)
        if (erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        else color = (0x66 shl 24) or (amber and 0xFFFFFF)
        if (s.softness > 0.03f && !erase) maskFilter = BlurMaskFilter(Math.max(1f, s.softness * s.size * width * 0.5f), BlurMaskFilter.Blur.NORMAL)
    }

    private fun drawBrushHint(c: Canvas, l: CLayer) {
        val layer = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        for (s in l.strokes) {
            val p = strokePaint(s, s.erase)
            if (s.pts.size < 4) c.drawPoint(s.pts[0] * width, s.pts[1] * height, p)
            else {
                val path = Path(); path.moveTo(s.pts[0] * width, s.pts[1] * height)
                var i = 2; while (i + 1 < s.pts.size) { path.lineTo(s.pts[i] * width, s.pts[i + 1] * height); i += 2 }
                c.drawPath(path, p)
            }
        }
        c.restoreToCount(layer)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = host?.layer() ?: return false
        scale.onTouchEvent(e)
        val type = l.maskType
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                began = false; twist = false
                if (type == 4) beginStroke(e.x, e.y) else if (type == 1 || type == 2 || type == 3) dragTo(l, e.x, e.y, true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (type == 1 && e.pointerCount == 2) { twist = true; lastAngle = ang(e) }
                if (type == 4 && drawing) endStroke(l)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    if (type == 1 && twist) {
                        val a = ang(e)
                        if (!began) { host?.begin(); began = true }
                        val f = F.field("maskAngle")
                        l.set(f, wrap(l.get(f) - Math.toRadians((a - lastAngle).toDouble()).toFloat()))
                        lastAngle = a
                        host?.changed(false); invalidate()
                    }
                } else if (!scale.isInProgress && !twist) {
                    if (type == 4) extendStroke(l, e.x, e.y) else if (type == 1 || type == 2 || type == 3) dragTo(l, e.x, e.y, false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (type == 4 && drawing) endStroke(l); twist = false }
        }
        return true
    }

    private fun wrap(a: Float): Float { var x = a; val p = Math.PI.toFloat(); while (x > p) x -= 2 * p; while (x < -p) x += 2 * p; return x }
    private fun ang(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun dragTo(l: CLayer, x: Float, y: Float, start: Boolean) {
        if (start && !began) { host?.begin(); began = true }
        when (l.maskType) {
            1 -> {
                val a = l.get(F.field("maskAngle"))
                val px = (x / width - 0.5f) * asp(); val py = 0.5f - y / height
                val d = Math.cos(a.toDouble()).toFloat() * px + Math.sin(a.toDouble()).toFloat() * py
                l.set(F.field("maskOffset"), d / (asp() * 0.5f))
            }
            else -> {
                l.set(F.field("cx"), x / width - 0.5f); l.set(F.field("cy"), 0.5f - y / height)
            }
        }
        host?.changed(false); invalidate()
    }

    // ------------------------------------------------------------------ brush

    private fun beginStroke(x: Float, y: Float) {
        host?.begin()
        drawing = true
        points = arrayListOf((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
        pushStroke(host?.layer() ?: return, true)
    }

    private fun extendStroke(l: CLayer, x: Float, y: Float) {
        if (!drawing) return
        val nx = (x / width).coerceIn(0f, 1f); val ny = (y / height).coerceIn(0f, 1f)
        val n = points.size
        if (Math.hypot(((nx - points[n - 2]) * width).toDouble(), ((ny - points[n - 1]) * height).toDouble()) < 3 * dp) return
        points.add(nx); points.add(ny)
        pushStroke(l, false)
    }

    private fun pushStroke(l: CLayer, add: Boolean) {
        val b = host?.brush() ?: return
        val s = Stroke(points.toFloatArray(), b.size, b.softness, b.strength, b.erase)
        if (add || l.strokes.isEmpty()) l.strokes.add(s) else l.strokes[l.strokes.size - 1] = s
        host?.changed(true); invalidate()
    }

    private fun endStroke(l: CLayer) { drawing = false; host?.changed(true); invalidate() }
}
