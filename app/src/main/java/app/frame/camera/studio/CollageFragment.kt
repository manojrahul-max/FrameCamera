package app.frame.camera.studio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.frame.camera.R
import app.frame.camera.fx.FxHub
import java.util.concurrent.Executors

/**
 * Collage Studio: a template browser with miniature previews, and an editor where the layout sits over
 * the live camera. Tap a frame and capture straight into it.
 */
class CollageFragment : Fragment(), CollageView.Listener {

    companion object {
        fun newInstance(layoutId: String?) = CollageFragment().apply { arguments = Bundle().apply { putString("layout", layoutId) } }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var browser: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var canvasArea: FrameLayout
    private lateinit var box: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var cv: CollageView
    private lateinit var panel: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var status: TextView
    private lateinit var titleView: TextView
    private var undoChip: TextView? = null
    private var redoChip: TextView? = null
    private var camera: StudioCamera? = null
    private var tab = 0
    private var category = 0
    private var inEditor = false
    private var busy = false
    private var look: CustomEffect? = null
    private var pendingPickIndex = -1
    private var frameSliders: List<Ui.SliderHandle> = emptyList()

    private val tabNames = listOf("Capture", "Layout", "Style", "Frame", "Decorate")
    private val aspects = listOf("1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "2:3" to 0.6667f, "9:16" to 0.5625f, "3:2" to 1.5f, "16:9" to 1.7778f)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else say("Camera permission is off. You can still fill frames from your gallery.")
    }
    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val idx = pendingPickIndex
        if (uri == null || idx < 0) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        io.execute {
            val bmp = try {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val m = Math.max(info.size.width, info.size.height)
                    if (m > 2000) { val s = 2000f / m; d.setTargetSize(Math.max(1, (info.size.width * s).toInt()), Math.max(1, (info.size.height * s).toInt())) }
                }
            } catch (t: Throwable) { null }
            main.post { if (view != null) { if (bmp != null) cv.placePicture(idx, bmp) else say("That picture could not be opened") } }
        }
    }

    // ================================================================== lifecycle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        FxHub.acquire(ctx)
        CustomSchema.load(ctx)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_ground)) }
        browser = buildBrowser()
        editor = buildEditor()
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        editor.visibility = View.GONE
        status = TextView(ctx).apply {
            setTextColor(Ui.color(ctx, R.color.cam_ink)); textSize = 12f
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
            setBackgroundResource(R.drawable.glass_cam_pill); visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = Ui.dp(ctx, 70) })
        val layoutId = arguments?.getString("layout")
        if (layoutId != null) root.post {
            if (view != null) CreationStore.loadLayout(ctx, layoutId)?.let { openTemplate(it, false) }
        }
        return root
    }

    override fun onDestroyView() {
        camera?.stop(); camera = null
        if (::cv.isInitialized) cv.releaseImages()
        FxHub.release()
        super.onDestroyView()
    }

    private fun say(t: String) {
        status.text = t; status.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(status)
        main.postAtTime({ status.visibility = View.GONE }, status, android.os.SystemClock.uptimeMillis() + 3200)
    }

    /** Back handling for the host. Returns true when it consumed the press. */
    fun onBack(): Boolean {
        if (inEditor) { closeEditor(); return true }
        return false
    }

    private fun studio(): StudioFragment? = parentFragment as? StudioFragment

    // ================================================================== browser

    private fun buildBrowser(): LinearLayout {
        val ctx = requireContext()
        val root = Ui.col(ctx)
        val bar = Ui.row(ctx)
        bar.addView(Ui.chip(ctx, "Studio") { studio()?.home() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 12) })
        bar.addView(Ui.title(ctx, "Collage Studio"), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Ui.chip(ctx, "Blank canvas") { openTemplate(Templates.byId("c_blank")!!.build().also { it.name = "My layout"; it.id = java.util.UUID.randomUUID().toString() }, true) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)))
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 56)).also { it.marginStart = Ui.dp(ctx, 16); it.marginEnd = Ui.dp(ctx, 16) })

        val chips = ArrayList<TextView>()
        val names = Templates.categories + "My layouts"
        for ((i, n) in names.withIndex()) {
            val c = Ui.chip(ctx, n, i == category)
            c.setOnClickListener { category = i; chips.forEachIndexed { j, x -> Ui.setChipOn(x, j == i) }; fillGrid() }
            chips.add(c)
        }
        val sv = Ui.scrollRow(ctx, chips)
        root.addView(sv, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.marginStart = Ui.dp(ctx, 16); it.bottomMargin = Ui.dp(ctx, 8) })

        val scroll = ScrollView(ctx)
        grid = Ui.col(ctx)
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fillGrid()
        return root
    }

    private fun fillGrid() {
        val ctx = requireContext()
        grid.removeAllViews()
        val items: List<Pair<String, () -> Collage?>> =
            if (category < Templates.categories.size)
                Templates.inCategory(category).map { t -> Pair<String, () -> Collage?>(t.name, { t.build() }) }
            else CreationStore.list(ctx, CreationStore.LAYOUT).map { e -> Pair<String, () -> Collage?>(e.name, { CreationStore.loadLayout(ctx, e.id) }) }
        if (items.isEmpty()) {
            grid.addView(Ui.label(ctx, "No saved layouts yet. Build one in the editor and choose Save as template.", 13f).apply { setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 40), Ui.dp(ctx, 24), 0) })
            return
        }
        val perRow = 3
        var row: LinearLayout? = null
        for ((i, item) in items.withIndex()) {
            if (i % perRow == 0) { row = Ui.row(ctx).apply { gravity = Gravity.TOP }; grid.addView(row, LinearLayout.LayoutParams(-1, -2).also { lp -> lp.marginStart = Ui.dp(ctx, 10); lp.marginEnd = Ui.dp(ctx, 10) }) }
            val col = item.second() ?: continue
            val tile = TemplateTile(ctx, col, item.first)
            tile.setOnClickListener { _ ->
                val fresh = item.second() ?: return@setOnClickListener
                if (category >= Templates.categories.size) { /* saved layout: keep its own id so edits can overwrite */ } else fresh.id = java.util.UUID.randomUUID().toString()
                openTemplate(fresh, false)
            }
            if (category >= Templates.categories.size) tile.setOnLongClickListener { _ ->
                android.app.AlertDialog.Builder(ctx).setTitle(item.first).setItems(arrayOf("Delete")) { _, _ ->
                    CreationStore.list(ctx, CreationStore.LAYOUT).firstOrNull { e -> e.name == item.first }?.let { e -> CreationStore.delete(ctx, CreationStore.LAYOUT, e.id); fillGrid() }
                }.show(); true }
            row!!.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).also { lp -> lp.margins(Ui.dp(ctx, 6)) })
        }
        val extra = (perRow - items.size % perRow) % perRow
        for (k in 0 until extra) row?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).also { lp -> lp.margins(Ui.dp(ctx, 6)) })
        grid.addView(View(ctx), LinearLayout.LayoutParams(-1, Ui.dp(ctx, 24)))
    }

    private fun LinearLayout.LayoutParams.margins(m: Int) { setMargins(m, m, m, m) }

    /** One template with its miniature drawn by the same renderer that exports the collage. */
    private class TemplateTile(ctx: android.content.Context, val col: Collage, val name: String) : View(ctx) {
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(ctx, R.color.cam_ink); textSize = Ui.dp(ctx, 11).toFloat(); textAlign = Paint.Align.CENTER }
        private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(ctx, R.color.cam_dim); textSize = Ui.dp(ctx, 10).toFloat(); textAlign = Paint.Align.CENTER }
        private var thumb: Bitmap? = null
        private var tw = 0
        init { isClickable = true; isFocusable = true; contentDescription = name }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, width + Ui.dp(context, 30))
        }
        override fun onDraw(c: Canvas) {
            val w = width
            if (thumb == null || tw != w) {
                val box = w - Ui.dp(context, 8)
                val tH = if (col.aspect >= 1f) Math.round(box / col.aspect) else box
                val tW = if (col.aspect >= 1f) box else Math.round(box * col.aspect)
                val b = Bitmap.createBitmap(Math.max(tW, 8), Math.max(tH, 8), Bitmap.Config.ARGB_8888)
                CollageRenderer.draw(Canvas(b), col, b.width, b.height, CollageRenderer.Mode.THUMB)
                thumb?.recycle(); thumb = b; tw = w
            }
            val b = thumb!!
            val left = (w - b.width) / 2f; val top = (w - b.height) / 2f
            c.drawBitmap(b, left, top, null)
            c.drawText(name, w / 2f, w + Ui.dp(context, 14).toFloat(), tp)
            c.drawText("${col.frames.size} frame" + (if (col.frames.size == 1) "" else "s"), w / 2f, w + Ui.dp(context, 27).toFloat(), sub)
        }
        override fun onDetachedFromWindow() { thumb?.recycle(); thumb = null; super.onDetachedFromWindow() }
    }

    // ================================================================== editor

    private fun buildEditor(): LinearLayout {
        val ctx = requireContext()
        val root = Ui.col(ctx)
        val bar = Ui.row(ctx)
        bar.addView(Ui.chip(ctx, "Templates") { closeEditor() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 8) })
        titleView = Ui.label(ctx, "", 14f, false).apply { gravity = Gravity.CENTER_VERTICAL; maxLines = 1 }
        bar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        undoChip = Ui.chip(ctx, "Undo") { cv.undo() }.also { it.alpha = 0.4f }
        redoChip = Ui.chip(ctx, "Redo") { cv.redo() }.also { it.alpha = 0.4f }
        bar.addView(undoChip, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 6) })
        bar.addView(redoChip, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 6) })
        bar.addView(Ui.chip(ctx, "Save", true) { exportPicture() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)))
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 52)).also { it.marginStart = Ui.dp(ctx, 12); it.marginEnd = Ui.dp(ctx, 12) })

        canvasArea = FrameLayout(ctx)
        box = FrameLayout(ctx)
        preview = PreviewView(ctx)
        cv = CollageView(ctx).also { it.listener = this }
        box.addView(preview, FrameLayout.LayoutParams(-1, -1))
        box.addView(cv, FrameLayout.LayoutParams(-1, -1))
        canvasArea.addView(box, FrameLayout.LayoutParams(10, 10, Gravity.CENTER))
        canvasArea.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) fitBox() }
        root.addView(canvasArea, LinearLayout.LayoutParams(-1, 0, 1f))

        tabs = Ui.row(ctx)
        val chips = ArrayList<View>()
        tabNames.forEachIndexed { i, n -> chips.add(Ui.chip(ctx, n, i == tab) { selectTab(i) }) }
        val ts = Ui.scrollRow(ctx, chips)
        root.addView(ts, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.marginStart = Ui.dp(ctx, 12); it.topMargin = Ui.dp(ctx, 8) })
        tabs = (ts.getChildAt(0) as LinearLayout)

        panel = FrameLayout(ctx)
        root.addView(panel, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 190)))
        return root
    }

    private fun fitBox() {
        val aw = canvasArea.width; val ah = canvasArea.height
        if (aw <= 0 || ah <= 0) return
        val pad = Ui.dp(requireContext(), 10)
        val maxW = aw - pad * 2; val maxH = ah - pad
        val a = cv.collage.aspect
        var w = maxW; var h = Math.round(w / a)
        if (h > maxH) { h = maxH; w = Math.round(h * a) }
        val lp = box.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; box.layoutParams = lp }
    }

    private fun openTemplate(col: Collage, blank: Boolean) {
        cv.releaseImages()
        cv.collage = col
        inEditor = true
        browser.visibility = View.GONE; editor.visibility = View.VISIBLE
        titleView.text = col.name
        tab = 0
        fitBox()
        cv.selected = col.frames.indexOfFirst { true }
        selectTab(0)
        refreshUndo()
        if (blank) say("Use the Layout tab to split, add and reshape frames")
    }

    private fun closeEditor() {
        camera?.stop()
        cv.releaseImages()
        inEditor = false
        editor.visibility = View.GONE; browser.visibility = View.VISIBLE
        fillGrid()
    }

    private fun hasCam() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!inEditor || tab != 0 || view == null) return
        preview.visibility = View.VISIBLE
        val cam = camera ?: StudioCamera(requireContext().applicationContext, viewLifecycleOwner, preview).also {
            it.onMessage = { m -> say(m) }
            camera = it
        }
        cam.start(cv.collage.aspect)
        look?.let { cam.setCustom(it) }
        cv.liveHole = true
        cv.invalidate()
    }

    private fun selectTab(i: Int) {
        tab = i
        tabs.let { for (j in 0 until it.childCount) Ui.setChipOn(it.getChildAt(j) as TextView, j == i) }
        cv.armSplit = -1; cv.armMerge = false
        cv.mode = when (i) { 0 -> CollageView.CAPTURE; 1 -> CollageView.LAYOUT; 2 -> CollageView.STYLE; 3 -> CollageView.ADJUST; else -> CollageView.DECO }
        if (i == 0) {
            if (cv.selected < 0 || cv.selected >= cv.collage.frames.size) cv.selected = Math.max(0, cv.nextEmpty())
            if (hasCam()) startCamera() else permission.launch(Manifest.permission.CAMERA)
            cv.liveHole = true
        } else {
            camera?.stop()
            cv.liveHole = false
            preview.visibility = View.INVISIBLE
        }
        cv.invalidate()
        panel.removeAllViews()
        panel.addView(when (i) { 0 -> capturePanel(); 1 -> layoutPanel(); 2 -> stylePanel(); 3 -> framePanel(); else -> decoPanel() })
    }

    // ------------------------------------------------------------------ capture panel

    private fun capturePanel(): View {
        val ctx = requireContext()
        val col = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), 0) }
        val info = Ui.label(ctx, infoText(), 12f)
        info.tag = "info"
        col.addView(info)
        val r = Ui.row(ctx).apply { gravity = Gravity.CENTER }
        r.addView(Ui.chip(ctx, "Flip") { camera?.flip(); camera?.let { c -> look?.let { e -> c.setCustom(e) } } }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 38)).also { it.marginEnd = Ui.dp(ctx, 8) })
        val lookChip = Ui.chip(ctx, "Look: " + (look?.name ?: "none"))
        lookChip.setOnClickListener { pickLook(lookChip) }
        r.addView(lookChip, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 38)).also { it.marginEnd = Ui.dp(ctx, 8) })
        val shutter = View(ctx).apply {
            setBackgroundResource(R.drawable.shutter_ring)
            setOnClickListener { capture() }
            contentDescription = "Capture into the selected frame"
        }
        r.addView(shutter, LinearLayout.LayoutParams(Ui.dp(ctx, 72), Ui.dp(ctx, 72)).also { it.marginStart = Ui.dp(ctx, 8); it.marginEnd = Ui.dp(ctx, 8) })
        r.addView(Ui.chip(ctx, "Gallery") { fromGallery() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 38)).also { it.marginEnd = Ui.dp(ctx, 8) })
        r.addView(Ui.chip(ctx, "Clear") { cv.clearImage() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 38)))
        col.addView(r, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun infoText(): String {
        val total = cv.collage.frames.size; val n = cv.filled()
        val sel = cv.selected
        return if (sel in 0 until total) "Frame ${sel + 1} of $total selected. $n of $total filled. Tap a frame, then capture."
        else "$n of $total filled. Tap a frame to select it."
    }

    private fun fromGallery() {
        if (cv.selected < 0) { say("Select a frame first"); return }
        pendingPickIndex = cv.selected
        pick.launch("image/*")
    }

    private fun capture() {
        if (busy) return
        val idx = cv.selected
        if (idx !in cv.collage.frames.indices) { say("Select a frame first"); return }
        val cam = camera ?: run { say("The camera is not running"); return }
        busy = true
        cam.capture { bmp, err ->
            busy = false
            if (view == null) { bmp?.recycle(); return@capture }
            if (bmp == null) { say(err ?: "Capture failed"); return@capture }
            cv.placeCapture(idx, bmp)
            val next = cv.collage.frames.indices.firstOrNull { k -> k != idx && cv.collage.frames[k].image == null }
            if (next != null) cv.selected = next
            cv.invalidate()
            (panel.findViewWithTag<TextView>("info"))?.text = infoText()
        }
    }

    private fun pickLook(chip: TextView) {
        val ctx = requireContext()
        val presets = CustomEffect.presets(ctx)
        val saved = CreationStore.list(ctx, CreationStore.EFFECT)
        val names = ArrayList<String>(); names.add("No effect")
        presets.forEach { names.add(it.name) }
        saved.forEach { names.add("Mine: " + it.name) }
        android.app.AlertDialog.Builder(ctx).setTitle("Live look for captures").setItems(names.toTypedArray()) { _, w ->
            val e: CustomEffect? = when {
                w == 0 -> null
                w <= presets.size -> presets[w - 1]
                else -> CreationStore.loadEffect(ctx, saved[w - 1 - presets.size].id)
            }
            look = e
            camera?.setCustom(e)
            chip.text = "Look: " + (e?.name ?: "none")
            say(if (e == null) "Look removed" else "Captures will carry ${e.name}")
        }.show()
    }

    // ------------------------------------------------------------------ layout panel

    private fun layoutPanel(): View {
        val ctx = requireContext()
        val col = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), 0) }
        col.addView(Ui.label(ctx, "Select a frame. Drag to move, drag the handles to resize (shared edges follow). Pinch is not used here.", 11f))
        fun c(t: String, f: () -> Unit) = Ui.chip(ctx, t) { f() }
        col.addView(Ui.scrollRow(ctx, listOf(
            c("Add frame") { cv.addFrame() }, c("Delete") { cv.deleteSelected() },
            c("Split across") { cv.splitMode(0) }, c("Split down") { cv.splitMode(1) },
            c("Split /") { cv.splitMode(2) }, c("Split \\") { cv.splitMode(3) }, c("Merge") { cv.mergeMode() }), 36),
            LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.topMargin = Ui.dp(ctx, 8) })
        col.addView(Ui.scrollRow(ctx, listOf(
            c("Rectangle") { cv.setShape(SHAPE_POLY) }, c("Oval") { cv.setShape(SHAPE_ELLIPSE) }, c("Heart") { cv.setShape(SHAPE_HEART) },
            c("Diamond") { cv.setShape(3) }, c("Bring front") { cv.bringFrameForward() }, c("Send back") { cv.sendFrameBack() }), 36),
            LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.topMargin = Ui.dp(ctx, 8) })
        col.addView(Ui.scrollRow(ctx, listOf(
            c("Aspect: " + aspectName()) { chooseAspect() }, c("Save as template") { saveTemplate() }), 36),
            LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.topMargin = Ui.dp(ctx, 8) })
        return col
    }

    private fun aspectName(): String = aspects.minByOrNull { Math.abs(it.second - cv.collage.aspect) }!!.let { if (Math.abs(it.second - cv.collage.aspect) < 0.02f) it.first else "custom" }

    private fun chooseAspect() {
        android.app.AlertDialog.Builder(requireContext()).setTitle("Canvas shape").setItems(aspects.map { it.first }.toTypedArray()) { _, i ->
            cv.checkpoint(); cv.collage.aspect = aspects[i].second; fitBox(); cv.invalidate(); selectTab(1)
        }.show()
    }

    private fun saveTemplate() {
        val ctx = requireContext()
        Ui.input(ctx, "Name this template", cv.collage.name) { n ->
            val copy = cv.collage.layoutCopy()
            copy.id = java.util.UUID.randomUUID().toString(); copy.name = n.ifEmpty { "My layout" }
            CreationStore.saveLayout(ctx, copy)
            titleView.text = copy.name
            say("Saved. Find it under My layouts")
        }
    }

    // ------------------------------------------------------------------ style panel

    private fun stylePanel(): View {
        val ctx = requireContext()
        val c = cv.collage
        val sv = ScrollView(ctx)
        val col = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 12)) }
        fun add(v: View, top: Int = 6) = col.addView(v, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, top) })
        fun s(name: String, min: Float, max: Float, v: Float, set: (Float) -> Unit) =
            add(Ui.slider(ctx, name, min, max, v, { String.format("%.0f%%", it * 100) }, { cv.checkpoint() }) { x -> set(x); cv.invalidate() }.view)
        s("Spacing", 0f, 0.06f, c.gap) { c.gap = it }
        s("Outer margin", 0f, 0.1f, c.outer) { c.outer = it }
        s("Corner radius", 0f, 0.12f, c.radius) { c.radius = it }
        s("Border", 0f, 0.03f, c.borderW) { c.borderW = it }
        add(Ui.label(ctx, "Background colour"), 10)
        add(Ui.swatches(ctx, Ui.PALETTE, c.bg1) { cv.checkpoint(); c.bg1 = it; if (!c.gradient) c.bg2 = it; cv.invalidate() })
        add(Ui.label(ctx, "Gradient to"), 10)
        add(Ui.swatches(ctx, Ui.PALETTE, c.bg2) { cv.checkpoint(); c.bg2 = it; c.gradient = it != c.bg1; cv.invalidate() })
        s("Gradient angle", 0f, 360f, c.bgAngle) { c.bgAngle = it }
        add(Ui.label(ctx, "Border colour"), 10)
        add(Ui.swatches(ctx, Ui.PALETTE, c.borderColor) { cv.checkpoint(); c.borderColor = it; cv.invalidate() })
        add(Ui.label(ctx, "Texture"), 10)
        val names = listOf("None", "Paper", "Dots", "Grid", "Lines")
        val chips = names.mapIndexed { i, n -> Ui.chip(ctx, n, c.texture == i) }
        chips.forEachIndexed { i, t -> t.setOnClickListener { cv.checkpoint(); c.texture = i; chips.forEachIndexed { j, x -> Ui.setChipOn(x, j == i) }; cv.invalidate() } }
        add(Ui.scrollRow(ctx, chips))
        val sh = Ui.chip(ctx, "Frame shadow", c.shadow)
        sh.setOnClickListener { cv.checkpoint(); c.shadow = !c.shadow; Ui.setChipOn(sh, c.shadow); cv.invalidate() }
        add(Ui.scrollRow(ctx, listOf(sh)), 10)
        sv.addView(col)
        return sv
    }

    // ------------------------------------------------------------------ frame panel

    private fun framePanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx)
        val col = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 12)) }
        val f = cv.collage.frames.getOrNull(cv.selected)
        if (f == null) {
            col.addView(Ui.label(ctx, "Tap a frame on the canvas to adjust it. Drag moves its photo, pinch zooms it.", 13f))
            sv.addView(col); return sv
        }
        col.addView(Ui.label(ctx, if (f.image == null) "This frame is empty. Capture or pick a photo first." else "Drag to move the photo. Pinch to zoom. Changes only affect this frame.", 12f))
        fun add(v: View, top: Int = 6) = col.addView(v, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, top) })
        fun s(n: String, min: Float, max: Float, v: Float, set: (Float) -> Unit) =
            add(Ui.slider(ctx, n, min, max, v, { String.format("%.2f", it) }, { cv.checkpoint() }) { x -> set(x); cv.invalidate() }.view)
        s("Brightness", -1f, 1f, f.bright) { f.bright = it }
        s("Contrast", 0.5f, 2f, f.contrast) { f.contrast = it }
        s("Saturation", 0f, 2f, f.sat) { f.sat = it }
        s("Warmth", -1f, 1f, f.warm) { f.warm = it }
        val fl = listOf("Colour", "Mono", "Sepia").mapIndexed { i, n -> Ui.chip(ctx, n, f.filter == i) }
        fl.forEachIndexed { i, t -> t.setOnClickListener { cv.checkpoint(); f.filter = i; fl.forEachIndexed { j, x -> Ui.setChipOn(x, j == i) }; cv.invalidate() } }
        add(Ui.scrollRow(ctx, fl), 10)
        add(Ui.scrollRow(ctx, listOf(
            Ui.chip(ctx, "Fill frame") { cv.checkpoint(); cv.fillFrame() },
            Ui.chip(ctx, "Replace from gallery") { fromGallery() },
            Ui.chip(ctx, "Retake") { selectTab(0) },
            Ui.chip(ctx, "Reset adjustments") { cv.checkpoint(); f.resetAdjust(); cv.invalidate(); selectTab(3) },
            Ui.chip(ctx, "Clear photo") { cv.clearImage() })), 10)
        sv.addView(col)
        return sv
    }

    // ------------------------------------------------------------------ decorate panel

    private fun decoPanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx)
        val col = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 12)) }
        fun add(v: View, top: Int = 6) = col.addView(v, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, top) })
        add(Ui.label(ctx, "Drag to move. Pinch to resize. Turn with two fingers. Tap text to edit it.", 12f))
        val stickers = listOf("Star" to ST_STAR, "Heart" to ST_HEART, "Sparkle" to ST_SPARKLE, "Smile" to ST_SMILE, "Tape" to ST_TAPE, "Flower" to ST_FLOWER, "Dot" to ST_DOT, "Flag" to ST_FLAG)
        val items = ArrayList<View>()
        items.add(Ui.chip(ctx, "Add text") { Ui.input(ctx, "Text", "Hello") { t -> if (t.isNotEmpty()) cv.addDeco(Deco(D_TEXT, 0.5f, 0.5f, 0f, 0f, 0f, 0xFFFFFFFF.toInt(), t, 0.08f, shadow = false)) } })
        items.add(Ui.chip(ctx, "Add bar") { cv.addDeco(Deco(D_RECT, 0.5f, 0.5f, 0.5f, 0.02f, 0f, 0xFFFFFFFF.toInt())) })
        for ((n, k) in stickers) items.add(Ui.chip(ctx, n) { cv.addDeco(Deco(D_STICKER, 0.5f, 0.5f, 0.16f, 0f, 0f, if (k == ST_TAPE) 0xCCF2E8A8.toInt() else 0xFFFFD34D.toInt(), sticker = k)) })
        add(Ui.scrollRow(ctx, items), 8)
        val d = cv.collage.decos.getOrNull(cv.selDeco)
        if (d != null) {
            add(Ui.label(ctx, "Selected"), 10)
            add(Ui.swatches(ctx, Ui.PALETTE + intArrayOf(0xFFFFD34D.toInt()), d.color) { cv.checkpoint(); d.color = it; cv.invalidate() })
            val tools = ArrayList<View>()
            if (d.kind == D_TEXT) {
                tools.add(Ui.chip(ctx, "Edit text") { Ui.input(ctx, "Text", d.text) { t -> cv.checkpoint(); d.text = t; cv.invalidate() } })
                tools.add(Ui.chip(ctx, if (d.serif) "Serif" else "Sans") { cv.checkpoint(); d.serif = !d.serif; cv.invalidate(); selectTab(4) })
                tools.add(Ui.chip(ctx, if (d.bold) "Bold" else "Regular") { cv.checkpoint(); d.bold = !d.bold; cv.invalidate(); selectTab(4) })
            }
            tools.add(Ui.chip(ctx, "Shadow", d.shadow) { cv.checkpoint(); d.shadow = !d.shadow; cv.invalidate(); selectTab(4) })
            tools.add(Ui.chip(ctx, if (d.below) "Behind photos" else "Above photos") { cv.checkpoint(); d.below = !d.below; cv.invalidate(); selectTab(4) })
            tools.add(Ui.chip(ctx, "Delete") { cv.deleteDeco() })
            add(Ui.scrollRow(ctx, tools), 8)
        }
        sv.addView(col)
        return sv
    }

    // ------------------------------------------------------------------ export

    private fun exportPicture() {
        if (busy) return
        val ctx = requireContext().applicationContext
        if (cv.filled() == 0) { say("Add at least one photo first"); return }
        busy = true
        say("Saving...")
        val col = cv.collage
        // Draw on the main thread's data, render off-thread from a snapshot of the references.
        io.execute {
            val long = 3000
            val w = if (col.aspect >= 1f) long else Math.round(long * col.aspect)
            val bmp = try { CollageRenderer.render(col, w, CollageRenderer.Mode.EXPORT) } catch (t: Throwable) { null }
            val ok = bmp != null && Ui.saveJpeg(ctx, bmp, "Frame_collage")
            bmp?.recycle()
            main.post { busy = false; if (view != null) say(if (ok) "Saved to Pictures/Frame" else "The collage could not be saved") }
        }
    }

    // ================================================================== CollageView.Listener

    override fun onSelection() {
        if (!isAdded || view == null) return
        (panel.findViewWithTag<TextView>("info"))?.text = infoText()
        if (tab == 3 || tab == 4) selectTab(tab)
        refreshUndo()
    }

    override fun onChanged() {
        if (!isAdded || view == null) return
        (panel.findViewWithTag<TextView>("info"))?.text = infoText()
        refreshUndo()
    }

    override fun onMessage(text: String) = say(text)

    override fun onDecoTap(index: Int) {
        val d = cv.collage.decos.getOrNull(index) ?: return
        if (d.kind == D_TEXT) Ui.input(requireContext(), "Text", d.text) { t -> cv.checkpoint(); d.text = t; cv.invalidate() }
    }

    private fun refreshUndo() {
        undoChip?.alpha = if (cv.canUndo()) 1f else 0.4f
        redoChip?.alpha = if (cv.canRedo()) 1f else 0.4f
    }
}
