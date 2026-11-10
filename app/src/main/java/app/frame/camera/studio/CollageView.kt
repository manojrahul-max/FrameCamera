package app.frame.camera.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * The collage canvas. In capture mode the selected frame is a transparent hole over the live camera.
 * In layout mode frames can be moved, resized (neighbouring edges follow), split, merged and reshaped.
 * In adjust mode the photo inside a frame can be panned and zoomed. In decorate mode text and stickers
 * can be moved, scaled and rotated.
 */
class CollageView(ctx: Context) : View(ctx) {

    companion object {
        const val CAPTURE = 0
        const val LAYOUT = 1
        const val ADJUST = 2
        const val DECO = 3
        const val STYLE = 4
        private const val NONE = 0
        private const val MOVE = 1
        private const val RESIZE = 2
        private const val PAN = 3
        private const val DMOVE = 4
    }

    interface Listener {
        fun onSelection()
        fun onChanged()
        fun onMessage(text: String)
        fun onDecoTap(index: Int)
    }

    var collage: Collage = Collage("blank", "Blank", 0.8f)
        set(v) { field = v; selected = -1; selDeco = -1; undo.clear(); redo.clear(); grave.clear(); armSplit = -1; armMerge = false; invalidate() }
    var mode = CAPTURE
        set(v) { field = v; armSplit = -1; armMerge = false; invalidate() }
    var selected = -1
    var selDeco = -1
    var armSplit = -1
    var armMerge = false
    var liveHole = false
    var listener: Listener? = null

    private val undo = ArrayList<Collage>()
    private val redo = ArrayList<Collage>()
    private val grave = HashMap<Int, CFrame>()
    private val dp = resources.displayMetrics.density
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drag = NONE
    private var lastX = 0f
    private var lastY = 0f
    private var hx = 0
    private var hy = 0
    private var rotating = false
    private var lastAngle = 0f
    private var moved = false
    private var downIndex = -1
    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { if (mode == ADJUST || mode == DECO) { checkpoint(); return true }; return false }
        override fun onScale(d: ScaleGestureDetector): Boolean { pinch(d.scaleFactor, d.focusX, d.focusY); return true }
    })

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val hole = if (liveHole && mode == CAPTURE && selected in collage.frames.indices) selected else -1
        CollageRenderer.draw(c, collage, w, h, CollageRenderer.Mode.EDIT, if (mode == DECO) -1 else selected, hole, if (mode == DECO) selDeco else -1)
        if (mode == LAYOUT && selected in collage.frames.indices) drawHandles(c)
        if (mode == CAPTURE) drawIndexes(c)
    }

    private fun drawIndexes(c: Canvas) {
        val paths = CollageRenderer.framePaths(collage, width, height)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 14 * dp; textAlign = Paint.Align.CENTER; setShadowLayer(3f, 0f, 1f, 0xAA000000.toInt()) }
        for ((i, f) in collage.frames.withIndex()) {
            if (f.image != null || i == selected) continue
            val path = paths[i] ?: continue
            val r = RectF(); path.computeBounds(r, true)
            c.drawText("+", r.centerX(), r.centerY() + 5 * dp, p.apply { textSize = 22 * dp })
        }
    }

    private fun handlesFor(b: RectF): List<Triple<Float, Float, IntArray>> {
        val xs = floatArrayOf(b.left, b.centerX(), b.right); val ys = floatArrayOf(b.top, b.centerY(), b.bottom)
        val out = ArrayList<Triple<Float, Float, IntArray>>()
        for (j in 0..2) for (i in 0..2) if (!(i == 1 && j == 1)) out.add(Triple(xs[i], ys[j], intArrayOf(i - 1, j - 1)))
        return out
    }

    private fun selBox(): RectF? {
        val f = collage.frames.getOrNull(selected) ?: return null
        val r = CollageRenderer.innerRect(collage, width, height)
        val b = f.bbox()
        return RectF(r.left + b[0] * r.width(), r.top + b[1] * r.height(), r.left + b[2] * r.width(), r.top + b[3] * r.height())
    }

    private fun drawHandles(c: Canvas) {
        val b = selBox() ?: return
        handlePaint.style = Paint.Style.STROKE; handlePaint.color = 0x99EDA94C.toInt(); handlePaint.strokeWidth = 1.5f * dp
        c.drawRect(b, handlePaint)
        for ((x, y, _) in handlesFor(b)) {
            handlePaint.style = Paint.Style.FILL; handlePaint.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, 7 * dp, handlePaint)
            handlePaint.style = Paint.Style.STROKE; handlePaint.color = 0xFFEDA94C.toInt(); handlePaint.strokeWidth = 2f * dp; c.drawCircle(x, y, 7 * dp, handlePaint)
        }
        if (armSplit >= 0) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 12 * dp; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt()) }
            c.drawText("Tap where to cut", b.centerX(), b.centerY(), p)
        }
        if (armMerge) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 12 * dp; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt()) }
            c.drawText("Tap the frame to merge with", b.centerX(), b.centerY(), p)
        }
    }

    // ------------------------------------------------------------------ hit testing

    private fun hitFrame(x: Float, y: Float): Int {
        val paths = CollageRenderer.framePaths(collage, width, height)
        val clip = Region(0, 0, width, height)
        for (i in collage.frames.indices.reversed()) {
            val p = paths[i] ?: continue
            val reg = Region(); reg.setPath(p, clip)
            if (reg.contains(x.toInt(), y.toInt())) return i
        }
        val r = CollageRenderer.innerRect(collage, width, height)
        val nx = (x - r.left) / r.width(); val ny = (y - r.top) / r.height()
        for (i in collage.frames.indices.reversed()) if (Geo.contains(collage.frames[i].pts, nx, ny)) return i
        return -1
    }

    private fun hitDecoAt(x: Float, y: Float): Int {
        for (i in collage.decos.indices.reversed()) if (CollageRenderer.hitDeco(collage.decos[i], width, height, x, y)) return i
        return -1
    }

    // ------------------------------------------------------------------ undo

    fun checkpoint() {
        undo.add(collage.layoutCopy().also { src -> collage.frames.forEachIndexed { i, f -> src.frames[i].id = f.id } })
        if (undo.size > 40) undo.removeAt(0)
        redo.clear()
    }

    private fun restore(src: Collage) {
        val keep = HashMap<Int, CFrame>(grave)
        collage.frames.forEach { keep[it.id] = it }
        collage.frames.clear()
        for (f in src.frames) {
            val nf = CFrame(f.id, f.pts.copyOf(), f.shape)
            keep[f.id]?.let { o ->
                nf.image = o.image; nf.imgL = o.imgL; nf.imgT = o.imgT; nf.imgR = o.imgR; nf.imgB = o.imgB
                nf.bright = o.bright; nf.contrast = o.contrast; nf.sat = o.sat; nf.warm = o.warm; nf.filter = o.filter
            }
            collage.frames.add(nf)
        }
        collage.decos.clear(); src.decos.forEach { collage.decos.add(it.copy()) }
        collage.gap = src.gap; collage.outer = src.outer; collage.radius = src.radius; collage.borderW = src.borderW
        collage.borderColor = src.borderColor; collage.bg1 = src.bg1; collage.bg2 = src.bg2; collage.bgAngle = src.bgAngle
        collage.gradient = src.gradient; collage.texture = src.texture; collage.shadow = src.shadow; collage.aspect = src.aspect
        if (selected >= collage.frames.size) selected = -1
        selDeco = -1
    }

    private fun snapshotNow(): Collage = collage.layoutCopy().also { s -> collage.frames.forEachIndexed { i, f -> s.frames[i].id = f.id } }

    fun undo() {
        if (undo.isEmpty()) return
        redo.add(snapshotNow()); restore(undo.removeAt(undo.size - 1)); changed()
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.add(snapshotNow()); restore(redo.removeAt(redo.size - 1)); changed()
    }

    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()

    private fun changed() { invalidate(); listener?.onChanged() }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == DECO && e.pointerCount == 2) { rotating = true; lastAngle = angle(e); if (selDeco >= 0) checkpoint() }
                drag = NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) { if (rotating && mode == DECO) rotateDeco(e) }
                else if (!scale.isInProgress && !rotating) move(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_UP -> { if (e.pointerCount <= 2) rotating = false; drag = NONE }
            MotionEvent.ACTION_UP -> { up(e.x, e.y); rotating = false }
            MotionEvent.ACTION_CANCEL -> { drag = NONE; rotating = false }
        }
        return true
    }

    private fun angle(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private fun rotateDeco(e: MotionEvent) {
        val d = collage.decos.getOrNull(selDeco) ?: return
        val a = angle(e)
        d.rot += a - lastAngle; lastAngle = a
        invalidate()
    }

    private fun down(x: Float, y: Float) {
        lastX = x; lastY = y; moved = false; drag = NONE
        downIndex = -1
        when (mode) {
            CAPTURE -> {
                val i = hitFrame(x, y)
                if (i >= 0 && i != selected) { selected = i; invalidate(); listener?.onSelection() }
            }
            LAYOUT -> {
                if (armSplit >= 0 && selected >= 0) { if (hitFrame(x, y) == selected) doSplit(x, y); return }
                if (armMerge && selected >= 0) { val i = hitFrame(x, y); if (i >= 0 && i != selected) doMerge(i); return }
                val b = selBox()
                if (b != null) {
                    for ((px, py, id) in handlesFor(b)) {
                        if (Math.hypot((x - px).toDouble(), (y - py).toDouble()) < 22 * dp) {
                            hx = id[0]; hy = id[1]; drag = RESIZE; checkpoint(); return
                        }
                    }
                }
                val i = hitFrame(x, y)
                if (i >= 0) {
                    if (i != selected) { selected = i; listener?.onSelection() }
                    drag = MOVE; checkpoint(); invalidate()
                } else if (selected >= 0) { selected = -1; listener?.onSelection(); invalidate() }
            }
            ADJUST -> {
                val i = hitFrame(x, y)
                if (i >= 0 && i != selected) { selected = i; invalidate(); listener?.onSelection() }
                if (i >= 0 && collage.frames[i].image != null) { drag = PAN; checkpoint() }
            }
            DECO -> {
                val di = hitDecoAt(x, y)
                if (di >= 0) {
                    selDeco = di; drag = DMOVE; downIndex = di; checkpoint(); invalidate(); listener?.onSelection()
                } else {
                    selDeco = -1
                    val i = hitFrame(x, y)
                    if (i >= 0 && i != selected) selected = i
                    invalidate(); listener?.onSelection()
                }
            }
        }
    }

    private fun move(x: Float, y: Float) {
        val dx = x - lastX; val dy = y - lastY
        if (!moved && Math.hypot(dx.toDouble(), dy.toDouble()) < 3 * dp) return
        moved = true
        val r = CollageRenderer.innerRect(collage, width, height)
        when (drag) {
            MOVE -> { moveFrame(dx / r.width(), dy / r.height()); }
            RESIZE -> resize(x, y, r)
            PAN -> panImage(dx / width, dy / height)
            DMOVE -> {
                val d = collage.decos.getOrNull(selDeco) ?: return
                d.x = (d.x + dx / width).coerceIn(0f, 1f); d.y = (d.y + dy / height).coerceIn(0f, 1f)
            }
            else -> return
        }
        lastX = x; lastY = y
        invalidate()
    }

    private fun up(x: Float, y: Float) {
        if (drag == DMOVE && !moved && downIndex >= 0) {
            // a tap on a decoration (no drag): editing text is offered
            if (undo.isNotEmpty()) undo.removeAt(undo.size - 1)
            listener?.onDecoTap(downIndex)
        } else if (drag != NONE && !moved && undo.isNotEmpty()) {
            undo.removeAt(undo.size - 1)
        }
        if (moved && drag != NONE) listener?.onChanged()
        drag = NONE
    }

    private fun moveFrame(ndx: Float, ndy: Float) {
        val f = collage.frames.getOrNull(selected) ?: return
        val b = f.bbox()
        var dx = ndx; var dy = ndy
        if (b[0] + dx < -0.05f) dx = -0.05f - b[0]
        if (b[2] + dx > 1.05f) dx = 1.05f - b[2]
        if (b[1] + dy < -0.05f) dy = -0.05f - b[1]
        if (b[3] + dy > 1.05f) dy = 1.05f - b[3]
        var i = 0
        while (i < f.pts.size) { f.pts[i] += dx; f.pts[i + 1] += dy; i += 2 }
        // the photo moves with its frame so the picture stays where it was framed
        f.imgL += dx * (1f - 2 * collage.outer); f.imgR += dx * (1f - 2 * collage.outer)
        f.imgT += dy * (1f - 2 * collage.outer * width / Math.max(1, height)); f.imgB += dy * (1f - 2 * collage.outer * width / Math.max(1, height))
    }

    private fun resize(x: Float, y: Float, r: RectF) {
        val f = collage.frames.getOrNull(selected) ?: return
        val b = f.bbox()
        val nx = ((x - r.left) / r.width()).coerceIn(-0.02f, 1.02f)
        val ny = ((y - r.top) / r.height()).coerceIn(-0.02f, 1.02f)
        var nl = b[0]; var nr = b[2]; var nt = b[1]; var nb = b[3]
        val minS = 0.06f
        if (hx < 0) nl = Math.min(nx, nr - minS) else if (hx > 0) nr = Math.max(nx, nl + minS)
        if (hy < 0) nt = Math.min(ny, nb - minS) else if (hy > 0) nb = Math.max(ny, nt + minS)
        val ow = b[2] - b[0]; val oh = b[3] - b[1]
        // neighbours that share the moving edge follow it
        val eps = 0.006f
        for (o in collage.frames) {
            if (o === f) continue
            var i = 0
            while (i < o.pts.size) {
                val px = o.pts[i]; val py = o.pts[i + 1]
                if (hx < 0 && Math.abs(px - b[0]) < eps && py >= b[1] - eps && py <= b[3] + eps) o.pts[i] = nl
                if (hx > 0 && Math.abs(px - b[2]) < eps && py >= b[1] - eps && py <= b[3] + eps) o.pts[i] = nr
                if (hy < 0 && Math.abs(py - b[1]) < eps && px >= b[0] - eps && px <= b[2] + eps) o.pts[i + 1] = nt
                if (hy > 0 && Math.abs(py - b[3]) < eps && px >= b[0] - eps && px <= b[2] + eps) o.pts[i + 1] = nb
                i += 2
            }
        }
        var i = 0
        while (i < f.pts.size) {
            if (ow > 1e-4f) f.pts[i] = nl + (f.pts[i] - b[0]) / ow * (nr - nl)
            if (oh > 1e-4f) f.pts[i + 1] = nt + (f.pts[i + 1] - b[1]) / oh * (nb - nt)
            i += 2
        }
    }

    private fun panImage(dx: Float, dy: Float) {
        val f = collage.frames.getOrNull(selected) ?: return
        f.imgL += dx; f.imgR += dx; f.imgT += dy; f.imgB += dy
    }

    private fun pinch(s: Float, fx: Float, fy: Float) {
        if (mode == ADJUST) {
            val f = collage.frames.getOrNull(selected) ?: return
            if (f.image == null) return
            val px = fx / width; val py = fy / height
            val w0 = f.imgR - f.imgL
            if (w0 * s < 0.1f || w0 * s > 12f) return
            f.imgL = px + (f.imgL - px) * s; f.imgR = px + (f.imgR - px) * s
            f.imgT = py + (f.imgT - py) * s; f.imgB = py + (f.imgB - py) * s
            invalidate()
        } else if (mode == DECO) {
            val d = collage.decos.getOrNull(selDeco) ?: return
            when (d.kind) {
                D_TEXT -> d.size = (d.size * s).coerceIn(0.015f, 0.4f)
                D_STICKER -> d.w = (d.w * s).coerceIn(0.03f, 0.9f)
                else -> { d.w = (d.w * s).coerceIn(0.01f, 1.5f); d.h = (d.h * s).coerceIn(0.002f, 1.5f) }
            }
            invalidate()
        }
    }

    // ------------------------------------------------------------------ layout operations

    private fun doSplit(x: Float, y: Float) {
        val f = collage.frames.getOrNull(selected) ?: return
        val r = CollageRenderer.innerRect(collage, width, height)
        val nx = (x - r.left) / r.width(); val ny = (y - r.top) / r.height()
        val src = if (f.shape == SHAPE_POLY) f.pts else Geo.rect(f.bbox()[0], f.bbox()[1], f.bbox()[2], f.bbox()[3])
        val res = Geo.splitAt(src, nx, ny, armSplit, collage.aspect * r.width() / r.height())
        if (res == null) { listener?.onMessage("The cut has to pass through the frame"); return }
        checkpoint()
        f.pts = res.first; f.shape = SHAPE_POLY
        val nf = CFrame(collage.newId(), res.second)
        collage.frames.add(selected + 1, nf)
        armSplit = -1
        changed(); listener?.onSelection()
    }

    private fun doMerge(i: Int) {
        val a = collage.frames[selected]; val b = collage.frames[i]
        val pa = if (a.shape == SHAPE_POLY) a.pts else Geo.rect(a.bbox()[0], a.bbox()[1], a.bbox()[2], a.bbox()[3])
        val pb = if (b.shape == SHAPE_POLY) b.pts else Geo.rect(b.bbox()[0], b.bbox()[1], b.bbox()[2], b.bbox()[3])
        val hull = Geo.hull(pa + pb)
        val sum = Geo.area(pa) + Geo.area(pb)
        if (hull.size < 6 || Geo.area(hull) > sum * 1.03f) {
            listener?.onMessage("Those frames do not join into one clean shape. Pick two that sit side by side.")
            armMerge = false; invalidate(); return
        }
        checkpoint()
        a.pts = hull; a.shape = SHAPE_POLY
        grave[b.id] = b
        collage.frames.removeAt(i)
        selected = collage.frames.indexOf(a)
        armMerge = false
        changed(); listener?.onSelection()
    }

    fun splitMode(kind: Int) { armMerge = false; armSplit = if (armSplit == kind) -1 else kind; if (selected < 0) { armSplit = -1; listener?.onMessage("Select a frame first") }; invalidate() }

    fun mergeMode() { armSplit = -1; armMerge = !armMerge; if (selected < 0) { armMerge = false; listener?.onMessage("Select a frame first") }; invalidate() }

    fun addFrame() {
        checkpoint()
        val h = 0.3f; val w = 0.3f / collage.aspect * 0.8f + 0.1f
        val f = collage.add(Geo.rect(0.5f - w / 2, 0.5f - h / 2, 0.5f + w / 2, 0.5f + h / 2))
        selected = collage.frames.indexOf(f); changed(); listener?.onSelection()
    }

    fun deleteSelected() {
        val f = collage.frames.getOrNull(selected) ?: return
        checkpoint()
        grave[f.id] = f
        collage.frames.removeAt(selected)
        selected = -1; changed(); listener?.onSelection()
    }

    fun bringToFront() {
        val f = collage.frames.getOrNull(selected) ?: return
        checkpoint(); collage.frames.removeAt(selected); collage.frames.add(f); selected = collage.frames.size - 1; changed()
    }

    fun sendToBack() {
        val f = collage.frames.getOrNull(selected) ?: return
        checkpoint(); collage.frames.removeAt(selected); collage.frames.add(0, f); selected = 0; changed()
    }

    /** kind: SHAPE_POLY (rectangle), SHAPE_ELLIPSE, SHAPE_HEART, or 3 for a diamond. */
    fun setShape(kind: Int) {
        val f = collage.frames.getOrNull(selected) ?: return
        checkpoint()
        val b = f.bbox()
        when (kind) {
            3 -> { val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2; f.pts = floatArrayOf(cx, b[1], b[2], cy, cx, b[3], b[0], cy); f.shape = SHAPE_POLY }
            SHAPE_POLY -> { f.pts = Geo.rect(b[0], b[1], b[2], b[3]); f.shape = SHAPE_POLY }
            else -> { f.pts = Geo.rect(b[0], b[1], b[2], b[3]); f.shape = kind }
        }
        changed()
    }

    // ------------------------------------------------------------------ images

    /** Puts a captured picture into frame [idx]. The picture is the full canvas, so it lines up with the live hole. */
    fun placeCapture(idx: Int, full: Bitmap) {
        val f = collage.frames.getOrNull(idx) ?: run { full.recycle(); return }
        val b = f.bbox()
        val r = CollageRenderer.innerRect(collage, width, height)
        val l = ((r.left + b[0] * r.width()) / width - 0.15f).coerceIn(0f, 1f)
        val t = ((r.top + b[1] * r.height()) / height - 0.15f).coerceIn(0f, 1f)
        val rr = ((r.left + b[2] * r.width()) / width + 0.15f).coerceIn(0f, 1f)
        val bb = ((r.top + b[3] * r.height()) / height + 0.15f).coerceIn(0f, 1f)
        val px = Rect(Math.round(l * full.width), Math.round(t * full.height), Math.round(rr * full.width), Math.round(bb * full.height))
        if (px.width() < 8 || px.height() < 8) { full.recycle(); return }
        checkpoint()
        var crop = Bitmap.createBitmap(full, px.left, px.top, px.width(), px.height())
        if (crop !== full) full.recycle()
        val maxSide = 1800
        if (Math.max(crop.width, crop.height) > maxSide) {
            val s = maxSide.toFloat() / Math.max(crop.width, crop.height)
            val sc = Bitmap.createScaledBitmap(crop, Math.max(1, (crop.width * s).toInt()), Math.max(1, (crop.height * s).toInt()), true)
            if (sc !== crop) crop.recycle()
            crop = sc
        }
        (f.image as? Bitmap)?.recycle()
        f.image = crop
        f.imgL = l; f.imgT = t; f.imgR = rr; f.imgB = bb
        changed()
    }

    /** Puts a picture from the gallery into frame [idx], scaled to fill it. */
    fun placePicture(idx: Int, bmp: Bitmap) {
        val f = collage.frames.getOrNull(idx) ?: return
        checkpoint()
        (f.image as? Bitmap)?.recycle()
        f.image = bmp
        fillFrame(idx)
        changed()
    }

    fun fillFrame(idx: Int = selected) {
        val f = collage.frames.getOrNull(idx) ?: return
        val img = f.image as? Bitmap ?: return
        val r = CollageRenderer.innerRect(collage, width, height)
        val b = f.bbox()
        val fl = r.left + b[0] * r.width(); val ft = r.top + b[1] * r.height()
        val fw = (b[2] - b[0]) * r.width(); val fh = (b[3] - b[1]) * r.height()
        val s = Math.max(fw / img.width, fh / img.height)
        val iw = img.width * s; val ih = img.height * s
        val cx = fl + fw / 2; val cy = ft + fh / 2
        f.imgL = (cx - iw / 2) / width; f.imgR = (cx + iw / 2) / width
        f.imgT = (cy - ih / 2) / height; f.imgB = (cy + ih / 2) / height
        invalidate()
    }

    fun clearImage() {
        val f = collage.frames.getOrNull(selected) ?: return
        checkpoint(); (f.image as? Bitmap)?.recycle(); f.image = null; f.resetAdjust(); changed()
    }

    fun filled(): Int = collage.frames.count { it.image != null }

    fun nextEmpty(): Int = collage.frames.indexOfFirst { it.image == null }

    fun releaseImages() {
        collage.frames.forEach { (it.image as? Bitmap)?.recycle(); it.image = null }
        grave.values.forEach { (it.image as? Bitmap)?.recycle() }
        grave.clear()
    }

    // ------------------------------------------------------------------ decorations

    fun addDeco(d: Deco) { checkpoint(); collage.decos.add(d); selDeco = collage.decos.size - 1; mode = DECO; changed(); listener?.onSelection() }

    fun deleteDeco() {
        if (selDeco !in collage.decos.indices) return
        checkpoint(); collage.decos.removeAt(selDeco); selDeco = -1; changed(); listener?.onSelection()
    }

    fun notifyChanged() = changed()
}
