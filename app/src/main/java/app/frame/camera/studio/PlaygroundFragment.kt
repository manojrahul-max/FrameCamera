package app.frame.camera.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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
 * The Live Effect Playground. A live camera view in the centre, layers with their own adjustments and
 * regions below it. Everything shown is the real GPU result of the camera pipeline, and a capture
 * runs the same shader.
 */
class PlaygroundFragment : Fragment(), MaskOverlay.Host {

    companion object {
        const val ARG_ID = "effect_id"
        const val ARG_PRESET = "preset_id"
        fun newInstance(effectId: String?, presetId: String? = null) = PlaygroundFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, effectId); putString(ARG_PRESET, presetId) }
        }

        private val LIBRARY: List<Pair<String, Map<String, Float>>> = listOf(
            "Black and white" to mapOf("mono" to 1f),
            "Sepia" to mapOf("sepia" to 1f),
            "Vintage" to mapOf("vintage" to 0.8f, "grain" to 0.3f, "vignette" to 0.3f),
            "Cinematic" to mapOf("cinematic" to 0.8f, "contrast" to 1.1f),
            "Warm" to mapOf("temperature" to 0.5f, "tint" to 0.05f),
            "Cool" to mapOf("temperature" to -0.5f),
            "Vivid" to mapOf("saturation" to 0.5f, "contrast" to 1.15f),
            "Fade" to mapOf("contrast" to 0.85f, "brightness" to 0.08f, "saturation" to -0.25f),
            "Soft blur" to mapOf("blur" to 0.5f),
            "Dream glow" to mapOf("glow" to 0.7f, "blur" to 0.15f),
            "Sharpen" to mapOf("sharpness" to 1f),
            "Vignette" to mapOf("vignette" to 0.7f),
            "Film grain" to mapOf("grain" to 0.6f),
            "RGB split" to mapOf("rgbSplit" to 0.7f),
            "Mirror left" to mapOf("mirror" to 1f),
            "Mirror right" to mapOf("mirror" to 2f),
            "Neon pink" to mapOf("gainR" to 1.5f, "gainG" to 0.6f, "gainB" to 1.3f, "saturation" to 0.4f),
            "Neon cyan" to mapOf("gainR" to 0.6f, "gainG" to 1.3f, "gainB" to 1.5f, "saturation" to 0.4f)
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var effect: CustomEffect
    private var start: CustomEffect? = null
    private var sel = 0
    private val undo = ArrayList<CustomEffect>()
    private val redo = ArrayList<CustomEffect>()
    private val brush = BrushSettings()
    private var showMask = true
    private var camera: StudioCamera? = null
    private lateinit var stageBox: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var overlay: MaskOverlay
    private lateinit var panel: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var nameView: TextView
    private var undoChip: TextView? = null
    private var redoChip: TextView? = null
    private var tab = 0
    private var aspect = 0.75f
    private var pending = false
    private var comparing = false
    private var busy = false
    private val tabNames = listOf("Layers", "Adjust", "Colour", "Region", "Library")
    private var group = "Light"

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else say("Camera permission is off, so the live view cannot run")
    }

    // ================================================================== lifecycle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        FxHub.acquire(ctx)
        CustomSchema.load(ctx)
        val id = arguments?.getString(ARG_ID)
        val loaded = if (id != null) CreationStore.loadEffect(ctx, id) else null
        val pid = arguments?.getString(ARG_PRESET)
        effect = loaded ?: (CustomEffect.presets(ctx).firstOrNull { it.id == "preset_$pid" } ?: CustomEffect.presets(ctx).first()).copy()
        if (loaded == null) { effect.id = java.util.UUID.randomUUID().toString() }
        start = effect.copy()

        val root = Ui.col(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_ground)) }
        val bar = Ui.row(ctx)
        bar.addView(Ui.chip(ctx, "Studio") { studio()?.home() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 8) })
        nameView = Ui.label(ctx, effect.name, 14f, false).apply { maxLines = 1; setOnClickListener { rename() } }
        bar.addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
        undoChip = Ui.chip(ctx, "Undo") { undoOp() }.also { it.alpha = 0.4f }
        redoChip = Ui.chip(ctx, "Redo") { redoOp() }.also { it.alpha = 0.4f }
        bar.addView(undoChip, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 6) })
        bar.addView(redoChip, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 6) })
        bar.addView(Ui.chip(ctx, "Reset") { resetDialog() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 6) })
        bar.addView(Ui.chip(ctx, "Save", true) { saveDialog() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)))
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 52)).also { it.marginStart = Ui.dp(ctx, 12); it.marginEnd = Ui.dp(ctx, 12) })

        stage = FrameLayout(ctx)
        stageBox = FrameLayout(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_well)) }
        preview = PreviewView(ctx)
        overlay = MaskOverlay(ctx).also { it.host = this }
        stageBox.addView(preview, FrameLayout.LayoutParams(-1, -1))
        stageBox.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        stage.addView(stageBox, FrameLayout.LayoutParams(10, 10, Gravity.CENTER))

        // controls floating over the live view
        val compare = Ui.chip(ctx, "Hold: original")
        compare.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { comparing = true; applyNow(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; applyNow(); true }
                else -> true
            }
        }
        stage.addView(compare, FrameLayout.LayoutParams(-2, Ui.dp(ctx, 36), Gravity.BOTTOM or Gravity.START).also { it.leftMargin = Ui.dp(ctx, 12); it.bottomMargin = Ui.dp(ctx, 14) })
        val shutter = View(ctx).apply { setBackgroundResource(R.drawable.shutter_ring); contentDescription = "Take a picture"; setOnClickListener { capture() } }
        stage.addView(shutter, FrameLayout.LayoutParams(Ui.dp(ctx, 68), Ui.dp(ctx, 68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = Ui.dp(ctx, 8) })
        val side = Ui.col(ctx)
        side.addView(Ui.chip(ctx, "Flip") { camera?.flip() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 36)).also { it.bottomMargin = Ui.dp(ctx, 6) })
        side.addView(Ui.chip(ctx, "Shape") { cycleShape() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 36)))
        stage.addView(side, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).also { it.rightMargin = Ui.dp(ctx, 12); it.bottomMargin = Ui.dp(ctx, 14) })
        status = TextView(ctx).apply {
            setTextColor(Ui.color(ctx, R.color.cam_ink)); textSize = 12f
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
            setBackgroundResource(R.drawable.glass_cam_pill); visibility = View.GONE
        }
        stage.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = Ui.dp(ctx, 8) })
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) fitStage() }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        val chips = ArrayList<View>()
        tabNames.forEachIndexed { i, n -> chips.add(Ui.chip(ctx, n, i == tab) { selectTab(i) }) }
        val ts = Ui.scrollRow(ctx, chips)
        root.addView(ts, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.marginStart = Ui.dp(ctx, 12); it.topMargin = Ui.dp(ctx, 8) })
        tabsRow = ts.getChildAt(0) as LinearLayout
        panel = FrameLayout(ctx)
        root.addView(panel, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 196)))
        selectTab(0)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hasCam()) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroyView() {
        camera?.stop(); camera = null
        FxHub.release()
        super.onDestroyView()
    }

    private fun studio(): StudioFragment? = parentFragment as? StudioFragment
    private fun hasCam() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun say(t: String) {
        if (view == null) return
        status.text = t; status.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(status)
        main.postAtTime({ status.visibility = View.GONE }, status, android.os.SystemClock.uptimeMillis() + 3200)
    }

    private fun fitStage() {
        val aw = stage.width; val ah = stage.height
        if (aw <= 0 || ah <= 0) return
        var w = aw; var h = Math.round(w / aspect)
        if (h > ah) { h = ah; w = Math.round(h * aspect) }
        val lp = stageBox.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; stageBox.layoutParams = lp }
    }

    private fun cycleShape() {
        aspect = when { aspect == 0.75f -> 0.5625f; aspect == 0.5625f -> 1f; else -> 0.75f }
        fitStage(); camera?.setAspect(aspect); applyNow()
        say(when (aspect) { 0.75f -> "Frame 3:4"; 0.5625f -> "Frame 9:16"; else -> "Frame 1:1" })
    }

    private fun startCamera() {
        if (view == null) return
        val cam = StudioCamera(requireContext().applicationContext, viewLifecycleOwner, preview)
        cam.onMessage = { m -> say(m) }
        cam.onReady = { applyNow() }
        camera = cam
        cam.start(aspect)
        cam.setCustom(effect)
    }

    // ================================================================== live apply

    private fun applyNow() {
        pending = false
        camera?.setCustom(effect, comparing)
        overlay.invalidate()
    }

    private fun schedule() {
        if (pending) return
        pending = true
        main.postDelayed({ if (view != null) applyNow() }, 24)
    }

    private fun capture() {
        if (busy) return
        val cam = camera ?: run { say("The camera is not running"); return }
        val ctx = requireContext().applicationContext
        busy = true
        overlay.visibility = View.INVISIBLE
        cam.capture { bmp, err ->
            busy = false
            if (view == null) { bmp?.recycle(); return@capture }
            overlay.visibility = if (tab == 3) View.VISIBLE else View.INVISIBLE
            if (bmp == null) { say(err ?: "Capture failed"); return@capture }
            io.execute {
                val ok = Ui.saveJpeg(ctx, bmp, "Frame_fx")
                bmp.recycle()
                main.post { say(if (ok) "Saved to Pictures/Frame" else "The picture could not be saved") }
            }
        }
    }

    // ================================================================== undo

    private fun snap() = effect.copy()

    override fun begin() { undo.add(snap()); if (undo.size > 60) undo.removeAt(0); redo.clear(); refreshUndo() }

    private fun undoOp() { if (undo.isEmpty()) return; redo.add(snap()); effect = undo.removeAt(undo.size - 1); sel = sel.coerceIn(0, effect.layers.size - 1); effect.touchStrokes(); afterStructure() }
    private fun redoOp() { if (redo.isEmpty()) return; undo.add(snap()); effect = redo.removeAt(redo.size - 1); sel = sel.coerceIn(0, effect.layers.size - 1); effect.touchStrokes(); afterStructure() }

    private fun afterStructure() { refreshUndo(); nameView.text = effect.name; selectTab(tab); applyNow() }

    private fun refreshUndo() { undoChip?.alpha = if (undo.isNotEmpty()) 1f else 0.4f; redoChip?.alpha = if (redo.isNotEmpty()) 1f else 0.4f }

    // MaskOverlay.Host
    override fun layer(): CLayer? = effect.layers.getOrNull(sel)
    override fun brush(): BrushSettings = brush
    override fun showMask(): Boolean = showMask
    override fun changed(strokes: Boolean) { if (strokes) effect.touchStrokes(); schedule() }

    // ================================================================== tabs

    private fun selectTab(i: Int) {
        tab = i
        for (j in 0 until tabsRow.childCount) Ui.setChipOn(tabsRow.getChildAt(j) as TextView, j == i)
        overlay.visibility = if (i == 3) View.VISIBLE else View.INVISIBLE
        panel.removeAllViews()
        panel.addView(when (i) { 0 -> layersPanel(); 1 -> adjustPanel(); 2 -> colourPanel(); 3 -> regionPanel(); else -> libraryPanel() })
        overlay.invalidate()
    }

    private fun pad(l: LinearLayout) = l.setPadding(Ui.dp(requireContext(), 16), Ui.dp(requireContext(), 8), Ui.dp(requireContext(), 16), Ui.dp(requireContext(), 12))

    private fun LinearLayout.addRow(v: View, top: Int = 6) {
        addView(v, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(v.context, top) })
    }

    private fun fld(k: String) = CustomSchema.field(k)

    private fun slider(col: LinearLayout, key: String, fmt: ((Float) -> String)? = null) {
        val ctx = requireContext(); val l = layer() ?: return; val f = fld(key)
        val h = Ui.slider(ctx, f.label, f.min, f.max, l.get(f), fmt ?: { String.format("%.2f", it) }, { begin() }) { v -> l.set(f, v); changed(false) }
        col.addRow(h.view)
    }

    // ------------------------------------------------------------------ layers

    private fun layersPanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx); val col = Ui.col(ctx); pad(col)
        col.addRow(Ui.slider(ctx, "Overall intensity", 0f, 1f, effect.intensity, { String.format("%.0f%%", it * 100) }, { begin() }) { v -> effect.intensity = v; changed(false) }.view, 0)
        for ((i, layer) in effect.layers.withIndex()) {
            val r = Ui.row(ctx)
            r.addView(Ui.chip(ctx, if (layer.enabled) "On" else "Off", layer.enabled) { begin(); layer.enabled = !layer.enabled; changed(false); selectTab(0) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 6) })
            val name = Ui.chip(ctx, (i + 1).toString() + "  " + layer.name + regionName(layer), i == sel) { sel = i; selectTab(0); overlay.invalidate() }
            name.setOnLongClickListener { Ui.input(ctx, "Layer name", layer.name) { n -> begin(); layer.name = n.ifEmpty { layer.name }; selectTab(0) }; true }
            r.addView(name, LinearLayout.LayoutParams(0, Ui.dp(ctx, 34), 1f).also { it.marginEnd = Ui.dp(ctx, 6) })
            r.addView(Ui.chip(ctx, "Up") { move(i, -1) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 4) })
            r.addView(Ui.chip(ctx, "Down") { move(i, 1) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 4) })
            r.addView(Ui.chip(ctx, "Copy") { copyLayer(i) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 4) })
            r.addView(Ui.chip(ctx, "Delete") { deleteLayer(i) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)))
            col.addRow(r, 8)
        }
        col.addRow(Ui.scrollRow(ctx, listOf(Ui.chip(ctx, "Add layer") { addLayer(CLayer("Layer ${effect.layers.size + 1}")) }), 34), 8)
        col.addRow(Ui.label(ctx, "Layers apply from the top. Each has its own adjustments and region. Long press a name to rename it. Up to four layers run live.", 11f), 6)
        sv.addView(col); return sv
    }

    private fun regionName(l: CLayer): String {
        val names = fld("maskType").choices ?: return ""
        return if (l.maskType == 0) "" else "  (" + names.getOrElse(l.maskType) { "" } + ")"
    }

    private fun addLayer(l: CLayer) {
        if (effect.layers.size >= CustomSchema.MAX_LAYERS) { say("Four layers is the maximum. Delete one first."); return }
        begin(); effect.layers.add(l); sel = effect.layers.size - 1; effect.touchStrokes(); changed(false); selectTab(tab)
    }

    private fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= effect.layers.size) return
        begin(); val x = effect.layers.removeAt(i); effect.layers.add(j, x); sel = j; effect.touchStrokes(); changed(false); selectTab(0)
    }

    private fun copyLayer(i: Int) {
        if (effect.layers.size >= CustomSchema.MAX_LAYERS) { say("Four layers is the maximum. Delete one first."); return }
        begin(); effect.layers.add(i + 1, effect.layers[i].copy().also { it.name = it.name + " copy" }); sel = i + 1; effect.touchStrokes(); changed(false); selectTab(0)
    }

    private fun deleteLayer(i: Int) {
        if (effect.layers.size <= 1) { say("An effect needs at least one layer"); return }
        begin(); effect.layers.removeAt(i); sel = sel.coerceIn(0, effect.layers.size - 1); effect.touchStrokes(); changed(false); selectTab(0)
    }

    // ------------------------------------------------------------------ adjust and colour

    private fun layerHeader(col: LinearLayout) {
        col.addRow(Ui.label(requireContext(), "Editing layer ${sel + 1}: " + (layer()?.name ?: ""), 11f), 0)
    }

    private fun adjustPanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx); val col = Ui.col(ctx); pad(col)
        layerHeader(col)
        val groups = listOf("Light", "Detail", "Look")
        val chips = groups.map { Ui.chip(ctx, it, it == group) }
        chips.forEachIndexed { i, c -> c.setOnClickListener { group = groups[i]; selectTab(1) } }
        col.addRow(Ui.scrollRow(ctx, chips, 32), 6)
        for (f in CustomSchema.adj.filter { it.group == group }) {
            if (f.key == "mirror") { mirrorRow(col); continue }
            val fmt: ((Float) -> String)? = if (f.key == "exposure") ({ v -> String.format("%+.1f", v) }) else null
            slider(col, f.key, fmt)
        }
        slider(col, "opacity", { String.format("%.0f%%", it * 100) })
        sv.addView(col); return sv
    }

    private fun mirrorRow(col: LinearLayout) {
        val ctx = requireContext(); val l = layer() ?: return; val f = fld("mirror")
        val cur = Math.round(l.get(f))
        val names = listOf("Off", "Left to right", "Right to left")
        val chips = names.mapIndexed { i, n -> Ui.chip(ctx, n, i == cur) }
        chips.forEachIndexed { i, c -> c.setOnClickListener { begin(); l.set(f, i.toFloat()); chips.forEachIndexed { j, x -> Ui.setChipOn(x, j == i) }; changed(false) } }
        col.addRow(Ui.label(ctx, "Mirror", 12f), 6)
        col.addRow(Ui.scrollRow(ctx, chips, 32), 2)
    }

    private fun colourPanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx); val col = Ui.col(ctx); pad(col)
        layerHeader(col)
        for (f in CustomSchema.adj.filter { it.group == "Color" }) slider(col, f.key, if (f.key == "hue") ({ v -> String.format("%.0f deg", v * 360f) }) else null)
        col.addRow(Ui.scrollRow(ctx, listOf(Ui.chip(ctx, "Reset colour") {
            val l = layer() ?: return@chip; begin()
            for (f in CustomSchema.adj.filter { it.group == "Color" }) l.set(f, f.def)
            changed(false); selectTab(2)
        }), 32), 8)
        sv.addView(col); return sv
    }

    // ------------------------------------------------------------------ region

    private fun regionPanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx); val col = Ui.col(ctx); pad(col)
        val l = layer() ?: return sv
        layerHeader(col)
        val tf = fld("maskType"); val names = tf.choices ?: emptyList()
        val chips = names.mapIndexed { i, n -> Ui.chip(ctx, n, i == l.maskType) }
        chips.forEachIndexed { i, c -> c.setOnClickListener {
            begin(); l.set(tf, i.toFloat())
            if (i == 2 || i == 3) { if (l.get(fld("radius")) < 0.08f) l.set(fld("radius"), 0.3f) }
            changed(false); selectTab(3)
        } }
        col.addRow(Ui.scrollRow(ctx, chips, 34), 4)
        when (l.maskType) {
            0 -> col.addRow(Ui.label(ctx, "The layer applies to the whole picture. Pick a region type to limit it.", 12f), 8)
            1 -> {
                col.addRow(Ui.label(ctx, "Drag on the picture to slide the line. Twist two fingers to turn it.", 12f), 6)
                slider(col, "maskAngle", { v -> String.format("%.0f deg", Math.toDegrees(v.toDouble())) })
                slider(col, "maskOffset"); slider(col, "maskCurve"); slider(col, "maskFeather")
            }
            2, 3 -> {
                col.addRow(Ui.label(ctx, "Drag to move the region. Pinch to resize it.", 12f), 6)
                slider(col, "cx"); slider(col, "cy"); slider(col, "radius"); slider(col, "height"); slider(col, "maskFeather")
            }
            4 -> brushControls(col, l)
            5 -> {
                col.addRow(Ui.label(ctx, "Only these colours get the layer. Everything else stays as it is.", 12f), 6)
                val cols = intArrayOf(0xFFE5484D.toInt(), 0xFFF2A33A.toInt(), 0xFFF0D84A.toInt(), 0xFF4CB86A.toInt(), 0xFF3CC4C4.toInt(), 0xFF3C7BE0.toInt(), 0xFF8A5CE0.toInt(), 0xFFE05CB0.toInt())
                val hues = floatArrayOf(0f, 0.08f, 0.16f, 0.33f, 0.5f, 0.62f, 0.76f, 0.9f)
                col.addRow(Ui.swatches(ctx, cols, cols[hues.indexOfFirst { Math.abs(it - l.get(fld("hueCenter"))) < 0.03f }.let { if (it < 0) 0 else it }]) { c ->
                    begin(); l.set(fld("hueCenter"), hues[cols.indexOf(c)]); changed(false); selectTab(3) }, 6)
                slider(col, "hueCenter"); slider(col, "hueWidth"); slider(col, "maskFeather")
            }
        }
        if (l.maskType != 0) {
            val inv = Ui.chip(ctx, "Invert region", l.get(fld("maskInvert")) > 0.5f)
            inv.setOnClickListener { begin(); l.set(fld("maskInvert"), if (l.get(fld("maskInvert")) > 0.5f) 0f else 1f); Ui.setChipOn(inv, l.get(fld("maskInvert")) > 0.5f); changed(false) }
            col.addRow(Ui.scrollRow(ctx, listOf(inv), 34), 8)
        }
        sv.addView(col); return sv
    }

    private fun brushControls(col: LinearLayout, l: CLayer) {
        val ctx = requireContext()
        col.addRow(Ui.label(ctx, "Paint on the picture where the layer should apply. The painted area is the processing region.", 12f), 6)
        col.addRow(Ui.slider(ctx, "Brush size", 0.02f, 0.3f, brush.size, { String.format("%.0f", it * 100) }) { v -> brush.size = v }.view)
        col.addRow(Ui.slider(ctx, "Softness", 0f, 1f, brush.softness, { String.format("%.0f%%", it * 100) }) { v -> brush.softness = v }.view)
        col.addRow(Ui.slider(ctx, "Brush opacity", 0.1f, 1f, brush.strength, { String.format("%.0f%%", it * 100) }) { v -> brush.strength = v }.view)
        val erase = Ui.chip(ctx, "Eraser", brush.erase)
        erase.setOnClickListener { brush.erase = !brush.erase; Ui.setChipOn(erase, brush.erase) }
        val show = Ui.chip(ctx, "Show mask", showMask)
        show.setOnClickListener { showMask = !showMask; Ui.setChipOn(show, showMask); overlay.invalidate() }
        val inv = Ui.chip(ctx, "Invert region", l.get(fld("maskInvert")) > 0.5f)
        inv.setOnClickListener { begin(); l.set(fld("maskInvert"), if (l.get(fld("maskInvert")) > 0.5f) 0f else 1f); Ui.setChipOn(inv, l.get(fld("maskInvert")) > 0.5f); changed(false) }
        val clear = Ui.chip(ctx, "Clear strokes") { if (l.strokes.isNotEmpty()) { begin(); l.strokes.clear(); changed(true) } }
        col.addRow(Ui.scrollRow(ctx, listOf(erase, show, inv, clear), 34), 8)
        col.addRow(Ui.label(ctx, "Use Undo and Redo at the top to step through strokes.", 11f), 6)
    }

    // ------------------------------------------------------------------ library

    private fun libraryPanel(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx); val col = Ui.col(ctx); pad(col)
        col.addRow(Ui.label(ctx, "Presets. Tapping one replaces the current effect (Undo brings it back).", 12f), 0)
        val presets = CustomEffect.presets(ctx)
        val saved = CreationStore.list(ctx, CreationStore.EFFECT)
        val pc = ArrayList<View>()
        presets.forEach { p -> pc.add(Ui.chip(ctx, p.name) { loadEffectCopy(p) }) }
        saved.forEach { e -> pc.add(Ui.chip(ctx, "Mine: " + e.name) { CreationStore.loadEffect(ctx, e.id)?.let { loadEffectCopy(it, keepId = true) } }) }
        col.addRow(Ui.scrollRow(ctx, pc, 34), 6)
        col.addRow(Ui.label(ctx, "Add as a new layer", 12f), 12)
        val lc = LIBRARY.map { (n, vals) -> Ui.chip(ctx, n) {
            val l = CLayer(n); for ((k, v) in vals) l.values[k] = v
            addLayer(l)
        } }
        col.addRow(Ui.scrollRow(ctx, lc.take(9), 34), 6)
        col.addRow(Ui.scrollRow(ctx, lc.drop(9), 34), 6)
        sv.addView(col); return sv
    }

    private fun loadEffectCopy(src: CustomEffect, keepId: Boolean = false) {
        begin()
        val c = src.copy()
        c.id = if (keepId) src.id else java.util.UUID.randomUUID().toString()
        effect = c; sel = 0; effect.touchStrokes()
        afterStructure()
        say("Loaded ${c.name}")
    }

    // ================================================================== save, reset

    private fun rename() = Ui.input(requireContext(), "Effect name", effect.name) { n -> if (n.isNotEmpty()) { effect.name = n; nameView.text = n } }

    private fun saveDialog() {
        val ctx = requireContext()
        Ui.input(ctx, "Name this effect", if (effect.name.startsWith("Layer")) "My effect" else effect.name) { n ->
            effect.name = n.ifEmpty { "My effect" }
            nameView.text = effect.name
            if (effect.id.startsWith("preset_")) effect.id = java.util.UUID.randomUUID().toString()
            CreationStore.saveEffect(ctx, effect.copy())
            android.app.AlertDialog.Builder(ctx).setTitle("Saved").setMessage("Find it in My Creations. Use it on the main camera now?")
                .setPositiveButton("Use on camera") { _, _ -> CreationStore.setCameraEffect(ctx, effect.id); say("The Camera tab will use ${effect.name}") }
                .setNegativeButton("Not now", null).show()
        }
    }

    private fun resetDialog() {
        android.app.AlertDialog.Builder(requireContext()).setTitle("Reset")
            .setItems(arrayOf("Reset this layer's adjustments", "Back to how this session started", "Start with an empty effect")) { _, w ->
                begin()
                when (w) {
                    0 -> layer()?.let { l -> l.values.clear(); l.strokes.clear() }
                    1 -> { effect = (start ?: effect).copy() }
                    else -> { effect = CustomEffect(effect.id, "My effect", arrayListOf(CLayer("Layer 1"))) }
                }
                sel = 0; effect.touchStrokes(); afterStructure()
            }.show()
    }
}
