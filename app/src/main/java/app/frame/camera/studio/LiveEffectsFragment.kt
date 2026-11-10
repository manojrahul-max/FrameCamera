package app.frame.camera.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.frame.camera.R
import app.frame.camera.fx.EffectCatalog
import app.frame.camera.fx.EffectDef
import app.frame.camera.fx.EffectPanel
import app.frame.camera.fx.EffectSettings
import app.frame.camera.fx.FxHub
import java.util.concurrent.Executors

/** Live Effects: the ready-made real-time effects and your own saved effects on a live camera view. */
class LiveEffectsFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var stage: FrameLayout
    private lateinit var box: FrameLayout
    private lateinit var status: TextView
    private lateinit var settings: EffectSettings
    private lateinit var fxList: List<EffectDef>
    private lateinit var panel: EffectPanel
    private var camera: StudioCamera? = null
    private var current: CustomEffect? = null
    private var busy = false
    private val aspect = 0.75f

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else say("Camera permission is off, so the live view cannot run")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        FxHub.acquire(ctx)
        CustomSchema.load(ctx)
        settings = EffectSettings(ctx)
        fxList = EffectCatalog.all(ctx)

        val root = Ui.col(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_ground)) }
        val bar = Ui.row(ctx)
        bar.addView(Ui.chip(ctx, "Studio") { (parentFragment as? StudioFragment)?.home() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 12) })
        bar.addView(Ui.title(ctx, "Live Effects"), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Ui.chip(ctx, "Photo lab") { (parentFragment as? StudioFragment)?.open(StudioFragment.PHOTO_LAB) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)))
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 52)).also { it.marginStart = Ui.dp(ctx, 12); it.marginEnd = Ui.dp(ctx, 12) })

        stage = FrameLayout(ctx)
        box = FrameLayout(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_well)) }
        preview = PreviewView(ctx)
        box.addView(preview, FrameLayout.LayoutParams(-1, -1))
        stage.addView(box, FrameLayout.LayoutParams(10, 10, Gravity.CENTER))
        val shutter = View(ctx).apply { setBackgroundResource(R.drawable.shutter_ring); contentDescription = "Take a picture"; setOnClickListener { capture() } }
        stage.addView(shutter, FrameLayout.LayoutParams(Ui.dp(ctx, 68), Ui.dp(ctx, 68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = Ui.dp(ctx, 8) })
        stage.addView(Ui.chip(ctx, "Flip") { camera?.flip() }, FrameLayout.LayoutParams(-2, Ui.dp(ctx, 36), Gravity.BOTTOM or Gravity.END).also { it.rightMargin = Ui.dp(ctx, 12); it.bottomMargin = Ui.dp(ctx, 22) })
        stage.addView(Ui.chip(ctx, "Customise") { customise() }, FrameLayout.LayoutParams(-2, Ui.dp(ctx, 36), Gravity.BOTTOM or Gravity.START).also { it.leftMargin = Ui.dp(ctx, 12); it.bottomMargin = Ui.dp(ctx, 22) })
        status = TextView(ctx).apply {
            setTextColor(Ui.color(ctx, R.color.cam_ink)); textSize = 12f
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
            setBackgroundResource(R.drawable.glass_cam_pill); visibility = View.GONE
        }
        stage.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = Ui.dp(ctx, 8) })
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) {
                val aw = stage.width; val ah = stage.height
                var w = aw; var h = Math.round(w / aspect)
                if (h > ah) { h = ah; w = Math.round(h * aspect) }
                val lp = box.layoutParams as FrameLayout.LayoutParams
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; box.layoutParams = lp }
            }
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // your effects, then the ready-made ones
        val mine = ArrayList<View>()
        mine.add(Ui.label(ctx, "Mine", 12f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, Ui.dp(ctx, 4), 0) })
        val presets = CustomEffect.presets(ctx)
        presets.forEach { p -> mine.add(Ui.chip(ctx, p.name) { chooseCustom(p) }) }
        CreationStore.list(ctx, CreationStore.EFFECT).forEach { e ->
            mine.add(Ui.chip(ctx, e.name) { CreationStore.loadEffect(ctx, e.id)?.let { chooseCustom(it) } })
        }
        root.addView(Ui.scrollRow(ctx, mine, 34), LinearLayout.LayoutParams(-1, Ui.dp(ctx, 34)).also { it.marginStart = Ui.dp(ctx, 12); it.topMargin = Ui.dp(ctx, 8) })

        panel = EffectPanel(ctx)
        panel.bind(fxList, settings, 150)
        panel.listener = object : EffectPanel.Listener {
            override fun onActiveChanged(def: EffectDef?) {
                current = null
                camera?.setLook(def, if (def != null) settings.snapshot(def) else FloatArray(8))
                settings.activeId = def?.id
            }
            override fun onParamsChanged(def: EffectDef) { camera?.setLook(def, settings.snapshot(def)) }
        }
        panel.setActive(null)
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, 6) })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hasCam()) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroyView() { camera?.stop(); camera = null; FxHub.release(); super.onDestroyView() }

    private fun hasCam() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun say(t: String) {
        if (view == null) return
        status.text = t; status.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(status)
        main.postAtTime({ status.visibility = View.GONE }, status, android.os.SystemClock.uptimeMillis() + 3200)
    }

    private fun startCamera() {
        if (view == null) return
        val cam = StudioCamera(requireContext().applicationContext, viewLifecycleOwner, preview)
        cam.onMessage = { m -> say(m) }
        camera = cam
        cam.start(aspect)
    }

    private fun chooseCustom(e: CustomEffect) {
        current = e
        panel.setActive(null)
        camera?.setCustom(e)
        say(e.name)
    }

    private fun customise() {
        val host = parentFragment as? StudioFragment ?: return
        val ctx = requireContext()
        val e = current
        if (e == null) { host.open(StudioFragment.PLAYGROUND); return }
        if (e.id.startsWith("preset_")) { host.open(StudioFragment.PLAYGROUND, null, e.id.removePrefix("preset_")) } else host.open(StudioFragment.PLAYGROUND, e.id)
    }

    private fun capture() {
        if (busy) return
        val cam = camera ?: run { say("The camera is not running"); return }
        val ctx = requireContext().applicationContext
        busy = true
        cam.capture { bmp, err ->
            busy = false
            if (view == null) { bmp?.recycle(); return@capture }
            if (bmp == null) { say(err ?: "Capture failed"); return@capture }
            io.execute {
                val ok = Ui.saveJpeg(ctx, bmp, "Frame_fx")
                bmp.recycle()
                main.post { say(if (ok) "Saved to Pictures/Frame" else "The picture could not be saved") }
            }
        }
    }
}
