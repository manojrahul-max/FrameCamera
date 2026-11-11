package app.frame.camera.studio

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The one drawing routine for collages. The template miniatures, the editor and the exported
 * picture all call [draw], so what is shown is exactly what is saved.
 */
object CollageRenderer {
    enum class Mode { THUMB, EDIT, EXPORT }

    private var paperTile: Bitmap? = null

    fun innerRect(col: Collage, w: Int, h: Int): RectF {
        val o = col.outer * w
        return RectF(o, o, w - o, h - o)
    }

    private fun mapPts(p: FloatArray, r: RectF): FloatArray {
        val o = FloatArray(p.size)
        var i = 0
        while (i < p.size) { o[i] = r.left + p[i] * r.width(); o[i + 1] = r.top + p[i + 1] * r.height(); i += 2 }
        return o
    }

    fun roundedPoly(p: FloatArray, radius: Float): Path {
        val n = p.size / 2
        val path = Path()
        if (radius <= 0.5f) {
            path.moveTo(p[0], p[1]); for (i in 1 until n) path.lineTo(p[i * 2], p[i * 2 + 1]); path.close(); return path
        }
        fun len(ax: Float, ay: Float, bx: Float, by: Float) = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
        for (i in 0 until n) {
            val pv = (i + n - 1) % n; val nx = (i + 1) % n
            val x = p[i * 2]; val y = p[i * 2 + 1]
            val l1 = len(x, y, p[pv * 2], p[pv * 2 + 1]); val l2 = len(x, y, p[nx * 2], p[nx * 2 + 1])
            val r = minOf(radius, l1 / 2f, l2 / 2f)
            val ax = x + (p[pv * 2] - x) / l1 * r; val ay = y + (p[pv * 2 + 1] - y) / l1 * r
            val bx = x + (p[nx * 2] - x) / l2 * r; val by = y + (p[nx * 2 + 1] - y) / l2 * r
            if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
            path.quadTo(x, y, bx, by)
        }
        path.close()
        return path
    }

    fun heartPath(l: Float, t: Float, r: Float, b: Float): Path {
        val w = r - l; val h = b - t; val cx = l + w / 2
        return Path().apply {
            moveTo(cx, b)
            cubicTo(l - w * 0.1f, t + h * 0.62f, l + w * 0.02f, t, cx, t + h * 0.30f)
            cubicTo(l + w * 0.98f, t, l + w * 1.1f, t + h * 0.62f, cx, b)
            close()
        }
    }

    /** Pixel path of every frame (null when the gap has eaten the frame). */
    fun framePaths(col: Collage, w: Int, h: Int): List<Path?> {
        val r = innerRect(col, w, h)
        val d = col.gap * w / 2f
        val rad = col.radius * w
        return col.frames.map { f ->
            if (f.shape == SHAPE_POLY) {
                val px = mapPts(f.pts, r)
                val ins = if (d > 0.01f) Geo.inset(px, d) else px
                if (ins == null) null else roundedPoly(ins, rad)
            } else {
                val b = Geo.bbox(mapPts(f.pts, r))
                val l = b[0] + d; val t = b[1] + d; val rr = b[2] - d; val bb = b[3] - d
                if (rr - l < 2f || bb - t < 2f) null
                else if (f.shape == SHAPE_ELLIPSE) Path().apply { addOval(RectF(l, t, rr, bb), Path.Direction.CW) }
                else heartPath(l, t, rr, bb)
            }
        }
    }

    private fun colorFilter(f: CFrame): ColorMatrixColorFilter? {
        if (f.bright == 0f && f.contrast == 1f && f.sat == 1f && f.warm == 0f && f.filter == 0) return null
        val m = ColorMatrix()
        m.setSaturation(if (f.filter != 0) 0f else f.sat)
        if (f.filter == 2) m.postConcat(ColorMatrix(floatArrayOf(
            1.07f, 0f, 0f, 0f, 14f,
            0f, 0.86f, 0f, 0f, 4f,
            0f, 0f, 0.62f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f)))
        val c = f.contrast
        val t = (-0.5f * c + 0.5f + f.bright * 0.5f) * 255f
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        if (f.warm != 0f) m.postConcat(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, f.warm * 22f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, -f.warm * 22f, 0f, 0f, 0f, 1f, 0f)))
        return ColorMatrixColorFilter(m)
    }

    private fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun drawBackground(c: Canvas, col: Collage, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (col.gradient) {
            val a = Math.toRadians(col.bgAngle.toDouble())
            val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
            val cx = w / 2f; val cy = h / 2f; val ext = (Math.abs(dx) * w + Math.abs(dy) * h) / 2f
            p.shader = LinearGradient(cx - dx * ext, cy - dy * ext, cx + dx * ext, cy + dy * ext, col.bg1, col.bg2, Shader.TileMode.CLAMP)
        } else p.color = col.bg1
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        if (col.texture == TEX_NONE) return
        val dark = lum(if (col.gradient) col.bg1 else col.bg1) > 0.5
        val ink = if (dark) Color.argb(26, 0, 0, 0) else Color.argb(30, 255, 255, 255)
        val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val step = w / 18f
        when (col.texture) {
            TEX_PAPER -> {
                val tile = paperTile ?: Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
                    var s = 12345
                    for (y in 0 until 96) for (x in 0 until 96) {
                        s = (s * 1103515245 + 12345) and 0x7fffffff
                        val v = (s shr 8) % 100
                        b.setPixel(x, y, if (v < 50) Color.argb(14 + v / 5, 0, 0, 0) else Color.argb(12 + (v - 50) / 6, 255, 255, 255))
                    }
                    paperTile = b
                }
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
            }
            TEX_DOTS -> { var y = step / 2; while (y < h) { var x = step / 2; while (x < w) { c.drawCircle(x, y, step * 0.09f, q); x += step }; y += step } }
            TEX_GRID -> { q.strokeWidth = Math.max(1f, w / 600f); var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), q); x += step }
                var y = 0f; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, q); y += step } }
            TEX_LINES -> { q.strokeWidth = Math.max(1f, w / 500f); var y = step; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, q); y += step } }
        }
    }

    private fun textPaint(d: Deco, w: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = d.color
        textSize = d.size * w
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(if (d.serif) Typeface.SERIF else Typeface.SANS_SERIF, if (d.bold) Typeface.BOLD else Typeface.NORMAL)
        if (d.size * w > 40f) letterSpacing = 0.04f
    }

    /** Unrotated bounds of a decoration around (x, y) in pixels. */
    fun decoBounds(d: Deco, w: Int, h: Int): RectF {
        val cx = d.x * w; val cy = d.y * h
        return when (d.kind) {
            D_TEXT -> {
                val p = textPaint(d, w)
                val tw = Math.max(p.measureText(d.text.ifEmpty { " " }), d.size * w * 0.6f); val th = d.size * w * 1.2f
                RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
            }
            D_STICKER -> {
                val s = d.w * w
                if (d.sticker == ST_TAPE) RectF(cx - s / 2, cy - s * 0.16f, cx + s / 2, cy + s * 0.16f) else RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            }
            else -> RectF(cx - d.w * w / 2, cy - d.h * h / 2, cx + d.w * w / 2, cy + d.h * h / 2)
        }
    }

    fun hitDeco(d: Deco, w: Int, h: Int, px: Float, py: Float): Boolean {
        val r = decoBounds(d, w, h)
        val cx = d.x * w; val cy = d.y * h
        val a = Math.toRadians(-d.rot.toDouble())
        val dx = px - cx; val dy = py - cy
        val rx = cx + (dx * Math.cos(a) - dy * Math.sin(a)).toFloat()
        val ry = cy + (dx * Math.sin(a) + dy * Math.cos(a)).toFloat()
        val pad = w * 0.015f
        return rx >= r.left - pad && rx <= r.right + pad && ry >= r.top - pad && ry <= r.bottom + pad
    }

    private fun drawDeco(c: Canvas, d: Deco, w: Int, h: Int) {
        val cx = d.x * w; val cy = d.y * h
        c.save()
        c.rotate(d.rot, cx, cy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = d.color }
        if (d.shadow) p.setShadowLayer(w * 0.012f, 0f, w * 0.005f, Color.argb(90, 0, 0, 0))
        val b = decoBounds(d, w, h)
        when (d.kind) {
            D_RECT -> { val r = d.round * Math.min(b.width(), b.height()); c.drawRoundRect(b, r, r, p) }
            D_ELLIPSE -> c.drawOval(b, p)
            D_TEXT -> {
                val tp = textPaint(d, w)
                if (d.shadow) tp.setShadowLayer(w * 0.008f, 0f, w * 0.003f, Color.argb(110, 0, 0, 0))
                val fm = tp.fontMetrics
                c.drawText(d.text, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
            }
            D_STICKER -> {
                val s = d.w * w; val r = s / 2f
                when (d.sticker) {
                    ST_TAPE -> c.drawRect(b, p)
                    ST_DOT -> c.drawCircle(cx, cy, r * 0.55f, p)
                    ST_STAR -> c.drawPath(starPath(cx, cy, r, r * 0.45f, 5), p)
                    ST_SPARKLE -> c.drawPath(starPath(cx, cy, r, r * 0.22f, 4), p)
                    ST_HEART -> c.drawPath(heartPath(cx - r, cy - r * 0.9f, cx + r, cy + r * 0.9f), p)
                    ST_FLAG -> c.drawPath(Path().apply { moveTo(cx, cy - r); lineTo(cx + r * 0.8f, cy + r * 0.7f); lineTo(cx - r * 0.8f, cy + r * 0.7f); close() }, p)
                    ST_FLOWER -> { for (k in 0 until 5) { val a = Math.toRadians(k * 72.0 - 90.0)
                        c.drawCircle(cx + (Math.cos(a) * r * 0.5).toFloat(), cy + (Math.sin(a) * r * 0.5).toFloat(), r * 0.38f, p) }
                        c.drawCircle(cx, cy, r * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE066.toInt() }) }
                    ST_SMILE -> {
                        c.drawCircle(cx, cy, r, p)
                        val k = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2118.toInt(); style = Paint.Style.STROKE; strokeWidth = r * 0.12f; strokeCap = Paint.Cap.ROUND }
                        c.drawCircle(cx - r * 0.33f, cy - r * 0.2f, r * 0.06f, k.apply { style = Paint.Style.FILL })
                        c.drawCircle(cx + r * 0.33f, cy - r * 0.2f, r * 0.06f, k)
                        k.style = Paint.Style.STROKE
                        c.drawArc(RectF(cx - r * 0.5f, cy - r * 0.35f, cx + r * 0.5f, cy + r * 0.55f), 20f, 140f, false, k)
                    }
                }
            }
        }
        c.restore()
    }

    private fun starPath(cx: Float, cy: Float, ro: Float, ri: Float, n: Int): Path {
        val p = Path()
        for (i in 0 until n * 2) {
            val a = Math.PI * i / n - Math.PI / 2
            val r = if (i % 2 == 0) ro else ri
            val x = cx + (Math.cos(a) * r).toFloat(); val y = cy + (Math.sin(a) * r).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close(); return p
    }

    /**
     * Draws the whole collage. [hole] is a frame index that is cleared to transparent so a live
     * camera view behind the canvas shows through it. [selected] gets an outline in editors.
     */
    fun draw(c: Canvas, col: Collage, w: Int, h: Int, mode: Mode, selected: Int = -1, hole: Int = -1, selDeco: Int = -1) {
        val layer = if (hole >= 0) c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null) else -1
        drawBackground(c, col, w, h)
        for (d in col.decos) if (d.below) drawDeco(c, d, w, h)
        val paths = framePaths(col, w, h)
        for ((i, f) in col.frames.withIndex()) {
            val path = paths[i] ?: continue
            if (col.shadow) {
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; setShadowLayer(w * 0.02f, 0f, w * 0.008f, Color.argb(110, 0, 0, 0)) }
                c.drawPath(path, sp)
            }
            val img = f.image as? Bitmap
            if (img != null && !img.isRecycled) {
                c.save()
                c.clipPath(path)
                val pp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                pp.colorFilter = colorFilter(f)
                c.drawBitmap(img, null, RectF(f.imgL * w, f.imgT * h, f.imgR * w, f.imgB * h), pp)
                c.restore()
            } else if (mode != Mode.EXPORT || i != hole) {
                val fp = Paint(Paint.ANTI_ALIAS_FLAG)
                if (mode == Mode.THUMB) {
                    val b = Geo.bbox(f.pts)
                    fp.shader = LinearGradient(b[0] * w, b[1] * h, b[2] * w, b[3] * h, 0xFF8C93B8.toInt(), 0xFFB9A4A0.toInt(), Shader.TileMode.CLAMP)
                } else fp.color = Color.argb(if (lum(col.bg1) > 0.5) 40 else 36, if (lum(col.bg1) > 0.5) 0 else 255, if (lum(col.bg1) > 0.5) 0 else 255, if (lum(col.bg1) > 0.5) 0 else 255)
                c.drawPath(path, fp)
            }
            if (col.borderW > 0f) {
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = col.borderColor; strokeWidth = col.borderW * w; strokeJoin = Paint.Join.ROUND }
                c.save(); c.clipPath(path); c.drawPath(path, bp.apply { strokeWidth = col.borderW * w * 2f }); c.restore()
            }
        }
        if (hole >= 0) {
            val hp = paths.getOrNull(hole)
            if (hp != null) c.drawPath(hp, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }
        for (d in col.decos) if (!d.below) drawDeco(c, d, w, h)
        if (layer >= 0) c.restoreToCount(layer)
        if (mode == Mode.EDIT) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFEDA94C.toInt(); strokeWidth = Math.max(3f, w / 220f) }
            paths.getOrNull(selected)?.let { c.drawPath(it, sp) }
            if (selDeco in col.decos.indices) {
                val d = col.decos[selDeco]; val b = decoBounds(d, w, h)
                c.save(); c.rotate(d.rot, d.x * w, d.y * h)
                sp.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
                c.drawRect(b.left - 8, b.top - 8, b.right + 8, b.bottom + 8, sp); c.restore()
            }
        }
    }

    fun render(col: Collage, w: Int, mode: Mode): Bitmap {
        val h = Math.round(w / col.aspect)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), col, w, h, mode)
        return b
    }
}
