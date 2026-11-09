package app.frame.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.frame.camera.fx.EffectCatalog
import app.frame.camera.fx.EffectDef
import app.frame.camera.fx.EffectPanel
import app.frame.camera.fx.EffectSettings
import app.frame.camera.fx.FxHub
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Effects tab: try the ten effects on a photo from the phone, adjust them and save the result.
 * The two motion effects (Blob Tracking, Datamosh) compare frames, so they are offered in the camera
 * and while recording, not on a single photo.
 */
class EffectsFragment : Fragment() {

    private lateinit var settings: EffectSettings
    private lateinit var fxList: List<EffectDef>
    private lateinit var panel: EffectPanel
    private lateinit var image: ImageView
    private lateinit var placeholder: TextView
    private lateinit var saveButton: TextView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var original: Bitmap? = null
    private var shown: Bitmap? = null
    private var active: EffectDef? = null
    private var rendering = false
    private var dirty = false
    private var destroyed = false

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)

    private fun chip(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(16), 0, dp(16), 0)
        setBackgroundResource(R.drawable.chip_cam_off)
        setTextColor(color(R.color.cam_ink))
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        settings = EffectSettings(ctx)
        fxList = EffectCatalog.all(ctx)
        FxHub.acquire(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.cam_ground))
        }

        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(ctx).apply {
            text = "Effects"
            textSize = 20f
            setTextColor(color(R.color.cam_ink))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val open = chip("Open photo").also { it.setOnClickListener { pick.launch("image/*") } }
        bar.addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).also { it.marginEnd = dp(8) })
        saveButton = chip("Save").also { it.setOnClickListener { save() }; it.alpha = 0.4f }
        bar.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).also {
            it.marginStart = dp(16); it.marginEnd = dp(16)
        })

        val stage = FrameLayout(ctx)
        image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Preview. Press and hold to see the original"
        }
        image.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { original?.let { image.setImageBitmap(it) }; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { (shown ?: original)?.let { image.setImageBitmap(it) }; true }
                else -> true
            }
        }
        stage.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        placeholder = TextView(ctx).apply {
            text = "Open a photo to try the effects on it.\nPress and hold the picture to compare with the original.\n\nBlob Tracking and Datamosh need motion, so they run in the camera and while recording video."
            setTextColor(color(R.color.cam_dim))
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(dp(32), 0, dp(32), 0)
        }
        stage.addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(ctx).apply {
            setTextColor(color(R.color.cam_ink))
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundResource(R.drawable.glass_cam_pill)
            visibility = View.GONE
        }
        stage.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = dp(8) })
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel = EffectPanel(ctx)
        panel.bind(fxList, settings, 170)
        val motion = HashMap<String, String>()
        fxList.filter { it.needsMotion }.forEach { motion[it.id] = "needs motion, so it runs in the camera and in video" }
        panel.setUnavailable(motion)
        panel.listener = object : EffectPanel.Listener {
            override fun onActiveChanged(def: EffectDef?) { choose(def) }
            override fun onParamsChanged(def: EffectDef) { scheduleRender() }
        }
        val holder = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.glass_cam)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        holder.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.setMargins(dp(12), dp(8), dp(12), dp(12))
        })
        return root
    }

    private fun choose(def: EffectDef?) {
        if (def == null) {
            active = null
            showCurrent()
            return
        }
        FxHub.probe(def) { err ->
            if (destroyed) return@probe
            if (err != null) {
                Toast.makeText(requireContext(), "${def.name} is not supported on this phone: $err", Toast.LENGTH_LONG).show()
                panel.setUnavailable(fxList.filter { FxHub.isProbeFailed(it.id) }.associate { it.id to "not supported on this GPU" } +
                    fxList.filter { it.needsMotion }.associate { it.id to "needs motion, so it runs in the camera and in video" })
                panel.setActive(active?.id)
                return@probe
            }
            active = def
            scheduleRender()
        }
    }

    private fun showCurrent() {
        val b = if (active == null) original else shown
        if (b != null) image.setImageBitmap(b)
    }

    private fun load(uri: Uri) {
        showStatus("Loading photo")
        val resolver = requireContext().contentResolver
        io.execute {
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_SIDE) decoder.setTargetSize(
                        info.size.width * MAX_SIDE / longest, info.size.height * MAX_SIDE / longest
                    )
                }
            } catch (e: Throwable) { null }
            main.post {
                if (destroyed) return@post
                if (bmp == null) {
                    showStatus("That photo could not be opened")
                    return@post
                }
                original = bmp
                shown = null
                placeholder.visibility = View.GONE
                image.setImageBitmap(bmp)
                FxHub.setSource(bmp)
                panel.reloadThumbnails()
                hideStatus()
                if (active != null) scheduleRender()
            }
        }
    }

    private fun scheduleRender() {
        if (original == null) {
            showStatus("Open a photo first")
            return
        }
        if (rendering) { dirty = true; return }
        val def = active ?: return
        val src = original ?: return
        rendering = true
        dirty = false
        FxHub.renderPicture(def, settings.snapshot(def), src, PREVIEW_SIDE) { bmp, err ->
            rendering = false
            if (destroyed) { bmp?.recycle(); return@renderPicture }
            if (bmp == null) {
                showStatus("${def.name} failed: ${err ?: "unknown error"}")
                active = null
                panel.setActive(null)
                showCurrent()
                return@renderPicture
            }
            val old = shown
            shown = bmp
            if (active != null) image.setImageBitmap(bmp)
            old?.recycle()
            saveButton.alpha = 1f
            hideStatus()
            if (dirty) scheduleRender()
        }
    }

    private fun save() {
        val def = active
        val src = original
        if (def == null || src == null) {
            Toast.makeText(requireContext(), "Open a photo and pick an effect first", Toast.LENGTH_SHORT).show()
            return
        }
        showStatus("Saving")
        val resolver = requireContext().contentResolver
        FxHub.renderPicture(def, settings.snapshot(def), src, MAX_SIDE) { bmp, err ->
            if (destroyed) { bmp?.recycle(); return@renderPicture }
            if (bmp == null) { showStatus("Could not apply the effect: ${err ?: "unknown error"}"); return@renderPicture }
            io.execute {
                val ok = try { write(resolver, bmp) } catch (e: Throwable) { false } finally { bmp.recycle() }
                main.post {
                    if (destroyed) return@post
                    if (ok) showStatus("Saved to Pictures/Frame") else showStatus("Could not save the picture")
                    main.postDelayed({ hideStatus() }, 2200)
                }
            }
        }
    }

    private fun write(resolver: android.content.ContentResolver, bmp: Bitmap): Boolean {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Frame_fx_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Frame")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return true
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() { status.visibility = View.GONE }

    override fun onDestroyView() {
        super.onDestroyView()
        destroyed = true
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        FxHub.release()
        image.setImageDrawable(null)
        shown?.recycle(); shown = null
        original = null
    }

    companion object {
        private const val MAX_SIDE = 4096
        private const val PREVIEW_SIDE = 1280
    }
}
