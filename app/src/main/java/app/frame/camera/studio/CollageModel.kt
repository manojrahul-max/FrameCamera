package app.frame.camera.studio

// Pure Kotlin (no Android types) so the layout maths can be run and checked on a plain JVM.

const val SHAPE_POLY = 0
const val SHAPE_ELLIPSE = 1
const val SHAPE_HEART = 2

const val D_RECT = 0
const val D_TEXT = 1
const val D_STICKER = 2
const val D_ELLIPSE = 3

const val ST_STAR = 0
const val ST_HEART = 1
const val ST_SPARKLE = 2
const val ST_SMILE = 3
const val ST_TAPE = 4
const val ST_DOT = 5
const val ST_FLAG = 6
const val ST_FLOWER = 7

const val TEX_NONE = 0
const val TEX_PAPER = 1
const val TEX_DOTS = 2
const val TEX_GRID = 3
const val TEX_LINES = 4

class CFrame(var id: Int, var pts: FloatArray, var shape: Int = SHAPE_POLY) {
    /** The photo (an android.graphics.Bitmap), kept as Any so this file stays platform free. */
    var image: Any? = null
    /** Where the photo sits on the canvas, in canvas fractions (left, top, right, bottom). */
    var imgL = 0f; var imgT = 0f; var imgR = 1f; var imgB = 1f
    var bright = 0f      // -1..1
    var contrast = 1f    // 0.5..2
    var sat = 1f         // 0..2
    var warm = 0f        // -1..1
    var filter = 0       // 0 none, 1 mono, 2 sepia

    fun layoutCopy(): CFrame = CFrame(id, pts.copyOf(), shape)
    fun bbox(): FloatArray = Geo.bbox(pts)
    fun resetAdjust() { bright = 0f; contrast = 1f; sat = 1f; warm = 0f; filter = 0 }
}

class Deco(
    var kind: Int, var x: Float, var y: Float, var w: Float, var h: Float,
    var rot: Float = 0f, var color: Int = 0xFFFFFFFF.toInt(), var text: String = "",
    var size: Float = 0.05f, var below: Boolean = false, var shadow: Boolean = false,
    var sticker: Int = 0, var bold: Boolean = true, var serif: Boolean = false, var round: Float = 0f
) {
    fun copy() = Deco(kind, x, y, w, h, rot, color, text, size, below, shadow, sticker, bold, serif, round)
}

class Collage(
    var id: String, var name: String, var aspect: Float,
    val frames: MutableList<CFrame> = ArrayList(),
    val decos: MutableList<Deco> = ArrayList()
) {
    var gap = 0.012f          // space between frames, fraction of canvas width
    var outer = 0.0f          // outer margin, fraction of canvas width
    var radius = 0.0f         // corner radius, fraction of canvas width
    var borderW = 0.0f        // fraction of canvas width
    var borderColor = 0xFFFFFFFF.toInt()
    var bg1 = 0xFF121315.toInt()
    var bg2 = 0xFF121315.toInt()
    var bgAngle = 90f
    var gradient = false
    var texture = TEX_NONE
    var shadow = false
    private var next = 1

    fun newId(): Int { frames.forEach { if (it.id >= next) next = it.id + 1 }; return next++ }

    fun add(pts: FloatArray, shape: Int = SHAPE_POLY): CFrame {
        val f = CFrame(newId(), pts, shape); frames.add(f); return f
    }

    fun layoutCopy(): Collage {
        val c = Collage(id, name, aspect)
        frames.forEach { c.frames.add(it.layoutCopy()) }
        decos.forEach { c.decos.add(it.copy()) }
        c.gap = gap; c.outer = outer; c.radius = radius; c.borderW = borderW; c.borderColor = borderColor
        c.bg1 = bg1; c.bg2 = bg2; c.bgAngle = bgAngle; c.gradient = gradient; c.texture = texture; c.shadow = shadow
        return c
    }
}

object Geo {
    fun rect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    fun bbox(p: FloatArray): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < p.size) { l = minOf(l, p[i]); r = maxOf(r, p[i]); t = minOf(t, p[i + 1]); b = maxOf(b, p[i + 1]); i += 2 }
        return floatArrayOf(l, t, r, b)
    }

    fun area(p: FloatArray): Float {
        var s = 0f; val n = p.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; s += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1] }
        return Math.abs(s) / 2f
    }

    fun centroid(p: FloatArray): FloatArray {
        var x = 0f; var y = 0f; val n = p.size / 2
        for (i in 0 until n) { x += p[i * 2]; y += p[i * 2 + 1] }
        return floatArrayOf(x / n, y / n)
    }

    fun contains(p: FloatArray, x: Float, y: Float): Boolean {
        var inside = false; val n = p.size / 2; var j = n - 1
        for (i in 0 until n) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Keeps the part of [p] where a*x + b*y >= c (or <= c when [ge] is false). */
    fun clip(p: FloatArray, a: Float, b: Float, c: Float, ge: Boolean): FloatArray {
        val s = if (ge) 1f else -1f
        val out = ArrayList<Float>(); val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            val px = p[i * 2]; val py = p[i * 2 + 1]; val qx = p[j * 2]; val qy = p[j * 2 + 1]
            val dp = s * (a * px + b * py - c); val dq = s * (a * qx + b * qy - c)
            if (dp >= 0) out.add(px).also { out.add(py) }
            if ((dp >= 0) != (dq >= 0)) {
                val t = dp / (dp - dq)
                out.add(px + (qx - px) * t); out.add(py + (qy - py) * t)
            }
        }
        return out.toFloatArray()
    }

    fun clipRect(p: FloatArray): FloatArray {
        var r = clip(p, 1f, 0f, 0f, true)
        r = clip(r, 1f, 0f, 1f, false)
        r = clip(r, 0f, 1f, 0f, true)
        return clip(r, 0f, 1f, 1f, false)
    }

    /** Splits [p] by the line a*x+b*y=c. Returns null when the line misses the polygon. */
    fun split(p: FloatArray, a: Float, b: Float, c: Float): Pair<FloatArray, FloatArray>? {
        val u = clip(p, a, b, c, true); val v = clip(p, a, b, c, false)
        if (u.size < 6 || v.size < 6) return null
        if (area(u) < 1e-5f || area(v) < 1e-5f) return null
        return Pair(u, v)
    }

    /** kind: 0 horizontal cut, 1 vertical cut, 2 "/" diagonal, 3 "\" diagonal (45 degrees on screen). */
    fun splitAt(p: FloatArray, x: Float, y: Float, kind: Int, aspect: Float): Pair<FloatArray, FloatArray>? = when (kind) {
        0 -> split(p, 0f, 1f, y)
        1 -> split(p, 1f, 0f, x)
        2 -> split(p, aspect, 1f, aspect * x + y)
        else -> split(p, aspect, -1f, aspect * x - y)
    }

    /** Rotates by [deg] (clockwise on screen) about (cx, cy), correct for a non-square canvas. */
    fun rotate(p: FloatArray, deg: Float, cx: Float, cy: Float, aspect: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val cs = Math.cos(r).toFloat(); val sn = Math.sin(r).toFloat()
        val o = FloatArray(p.size)
        var i = 0
        while (i < p.size) {
            val dx = (p[i] - cx) * aspect; val dy = p[i + 1] - cy
            o[i] = cx + (dx * cs - dy * sn) / aspect
            o[i + 1] = cy + dx * sn + dy * cs
            i += 2
        }
        return o
    }

    fun hull(p: FloatArray): FloatArray {
        val pts = ArrayList<FloatArray>()
        var i = 0
        while (i < p.size) { pts.add(floatArrayOf(p[i], p[i + 1])); i += 2 }
        pts.sortWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<FloatArray>()
        for (q in pts) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1); lower.add(q) }
        val upper = ArrayList<FloatArray>()
        for (q in pts.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1); upper.add(q) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        val all = lower + upper
        val out = FloatArray(all.size * 2)
        all.forEachIndexed { k, q -> out[k * 2] = q[0]; out[k * 2 + 1] = q[1] }
        return out
    }

    /** Shrinks a convex polygon (pixel units) by [d] on every side. Returns null if it collapses. */
    fun inset(p: FloatArray, d: Float): FloatArray? {
        val n = p.size / 2
        if (n < 3) return null
        var sgn = 0f
        for (i in 0 until n) { val j = (i + 1) % n; sgn += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1] }
        val dir = if (sgn > 0) 1f else -1f
        val lines = Array(n) { FloatArray(3) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = p[j * 2] - p[i * 2]; val dy = p[j * 2 + 1] - p[i * 2 + 1]
            val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (len < 1e-6f) return null
            val nx = -dir * dy / len; val ny = dir * dx / len      // inward normal
            lines[i][0] = nx; lines[i][1] = ny
            lines[i][2] = nx * (p[i * 2] + nx * d) + ny * (p[i * 2 + 1] + ny * d)
        }
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = lines[(i + n - 1) % n]; val b = lines[i]
            val det = a[0] * b[1] - a[1] * b[0]
            if (Math.abs(det) < 1e-6f) { out[i * 2] = p[i * 2]; out[i * 2 + 1] = p[i * 2 + 1]; continue }
            out[i * 2] = (a[2] * b[1] - a[1] * b[2]) / det
            out[i * 2 + 1] = (a[0] * b[2] - a[2] * b[0]) / det
        }
        // A collapsed polygon has edges that run backwards compared with the original.
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ox = p[j * 2] - p[i * 2]; val oy = p[j * 2 + 1] - p[i * 2 + 1]
            val ex = out[j * 2] - out[i * 2]; val ey = out[j * 2 + 1] - out[i * 2 + 1]
            if (ox * ex + oy * ey <= 0f) return null
        }
        return out
    }
}
