package app.frame.camera.studio

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import app.frame.camera.R
import app.frame.camera.fx.FxHub
import app.frame.camera.fx.SampleScene

/** My Creations: saved effects and collage layouts, with favourites, collections, import, export and sharing. */
class CreationsFragment : Fragment() {

    private lateinit var list: LinearLayout
    private lateinit var filterRow: LinearLayout
    private var filter = "All"
    private var pendingExport: String? = null

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importFrom(uri) }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport
        if (uri != null && text != null) {
            val ok = try { requireContext().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }; true } catch (t: Throwable) { false }
            Ui.toast(requireContext(), if (ok) "Exported" else "The file could not be written")
        }
        pendingExport = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        FxHub.acquire(ctx)
        CustomSchema.load(ctx)
        val root = Ui.col(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_ground)) }
        val bar = Ui.row(ctx)
        bar.addView(Ui.chip(ctx, "Studio") { (parentFragment as? StudioFragment)?.home() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 12) })
        bar.addView(Ui.title(ctx, "My Creations"), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Ui.chip(ctx, "Import") { importDialog() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)))
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 52)).also { it.marginStart = Ui.dp(ctx, 16); it.marginEnd = Ui.dp(ctx, 16) })

        val fs = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        filterRow = Ui.row(ctx)
        fs.addView(filterRow)
        root.addView(fs, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 36)).also { it.marginStart = Ui.dp(ctx, 16); it.bottomMargin = Ui.dp(ctx, 6) })
        val sv = ScrollView(ctx)
        list = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 24)) }
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh()
        return root
    }

    override fun onDestroyView() { FxHub.release(); super.onDestroyView() }

    private fun refresh() {
        val ctx = requireContext()
        filterRow.removeAllViews()
        val names = arrayListOf("All", "Favourites", "Effects", "Layouts")
        names.addAll(CreationStore.collections(ctx))
        for (n in names) {
            filterRow.addView(Ui.chip(ctx, n, n == filter) { filter = n; refresh() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 34)).also { it.marginEnd = Ui.dp(ctx, 8) })
        }
        list.removeAllViews()
        val all = (CreationStore.list(ctx, CreationStore.EFFECT) + CreationStore.list(ctx, CreationStore.LAYOUT))
            .sortedWith(compareByDescending<CreationStore.Entry> { it.fav }.thenByDescending { it.modified })
        val shown = all.filter {
            when (filter) {
                "All" -> true
                "Favourites" -> it.fav
                "Effects" -> it.type == CreationStore.EFFECT
                "Layouts" -> it.type == CreationStore.LAYOUT
                else -> it.collection == filter
            }
        }
        if (shown.isEmpty()) {
            list.addView(Ui.label(ctx, if (all.isEmpty()) "Nothing here yet. Save an effect from the Playground, or a layout from Collage Studio. You can also import one that someone shared." else "Nothing matches this filter.", 13f).apply { setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 32), Ui.dp(ctx, 8), 0) })
            return
        }
        for (e in shown) list.addView(row(e), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, 8) })
    }

    private fun row(e: CreationStore.Entry): View {
        val ctx = requireContext()
        val r = Ui.row(ctx).apply {
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
            setBackgroundResource(R.drawable.tile_bg)
            isClickable = true
            setOnClickListener { primary(e) }
        }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.color(ctx, R.color.cam_well)) }
        r.addView(img, LinearLayout.LayoutParams(Ui.dp(ctx, 64), Ui.dp(ctx, 64)).also { it.marginEnd = Ui.dp(ctx, 12) })
        thumb(e, img)
        val c = Ui.col(ctx)
        c.addView(Ui.label(ctx, e.name, 15f, false).apply { maxLines = 1 })
        val sub = (if (e.type == CreationStore.EFFECT) "Effect" else "Layout") + (if (e.collection.isNotEmpty()) "  in " + e.collection else "")
        c.addView(Ui.label(ctx, sub, 12f))
        r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        if (e.type == CreationStore.EFFECT && CreationStore.cameraEffectId(ctx) == e.id) c.addView(Ui.label(ctx, "Used on the camera", 11f).apply { setTextColor(Ui.color(ctx, R.color.cam_accent)) })
        r.addView(Ui.chip(ctx, if (e.fav) "Fav" else "Fav", e.fav) { CreationStore.setFavorite(ctx, e.type, e.id, !e.fav); refresh() }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 32)).also { it.marginEnd = Ui.dp(ctx, 6) })
        r.addView(Ui.chip(ctx, "More") { actions(e) }, LinearLayout.LayoutParams(-2, Ui.dp(ctx, 32)))
        return r
    }

    private fun thumb(e: CreationStore.Entry, img: ImageView) {
        val ctx = requireContext()
        if (e.type == CreationStore.LAYOUT) {
            val c = CreationStore.loadLayout(ctx, e.id) ?: return
            val w = 160; val h = Math.max(40, Math.round(w / c.aspect))
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            CollageRenderer.draw(Canvas(b), c, w, h, CollageRenderer.Mode.THUMB)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageBitmap(b)
        } else {
            val fx = CreationStore.loadEffect(ctx, e.id) ?: return
            val src = SampleScene.frame(240, 240, 2)
            FxHub.renderCustom(fx, src, 160) { out, _ -> src.recycle(); if (out != null && view != null) img.setImageBitmap(out) }
        }
    }

    private fun primary(e: CreationStore.Entry) {
        if (e.type == CreationStore.EFFECT) previewEffect(e) else (parentFragment as? StudioFragment)?.open(StudioFragment.COLLAGE, layoutId = e.id)
    }

    private fun previewEffect(e: CreationStore.Entry) {
        val ctx = requireContext()
        val fx = CreationStore.loadEffect(ctx, e.id) ?: return
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Ui.color(ctx, R.color.cam_well)) }
        val src = SampleScene.frame(600, 450, 2)
        val shown = arrayOfNulls<Bitmap>(1)
        iv.setImageBitmap(src)
        FxHub.renderCustom(fx, src, 900) { out, _ -> if (out != null) { shown[0] = out; iv.setImageBitmap(out) } }
        iv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { iv.setImageBitmap(src); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { shown[0]?.let { iv.setImageBitmap(it) }; true }
                else -> true
            }
        }
        AlertDialog.Builder(ctx).setTitle(e.name + " (hold the picture to see the original)").setView(iv)
            .setPositiveButton("Edit") { _, _ -> (parentFragment as? StudioFragment)?.open(StudioFragment.PLAYGROUND, effectId = e.id) }
            .setNeutralButton("Use on camera") { _, _ -> CreationStore.setCameraEffect(ctx, e.id); Ui.toast(ctx, "The Camera tab will use ${e.name}"); refresh() }
            .setNegativeButton("Close", null).show()
    }

    private fun actions(e: CreationStore.Entry) {
        val ctx = requireContext()
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (e.type == CreationStore.EFFECT) {
            items.add("Preview" to { previewEffect(e) })
            items.add("Edit in Playground" to { (parentFragment as? StudioFragment)?.open(StudioFragment.PLAYGROUND, effectId = e.id) })
            val on = CreationStore.cameraEffectId(ctx) == e.id
            items.add((if (on) "Stop using on camera" else "Use on camera") to { CreationStore.setCameraEffect(ctx, if (on) null else e.id); refresh() })
        } else items.add("Open in Collage Studio" to { primary(e) })
        items.add("Duplicate" to { CreationStore.duplicate(ctx, e.type, e.id); refresh() })
        items.add("Rename" to { Ui.input(ctx, "Name", e.name) { n -> CreationStore.rename(ctx, e.type, e.id, n); refresh() } })
        items.add("Add to a collection" to { collection(e) })
        items.add("Share" to { share(e) })
        items.add("Export to a file" to { pendingExport = CreationStore.exportText(ctx, e.type, e.id); exportFile.launch(e.name.replace(Regex("[^A-Za-z0-9 _-]"), "") + ".frame.json") })
        items.add("Delete" to {
            AlertDialog.Builder(ctx).setTitle("Delete ${e.name}?").setPositiveButton("Delete") { _, _ ->
                if (CreationStore.cameraEffectId(ctx) == e.id) CreationStore.setCameraEffect(ctx, null)
                CreationStore.delete(ctx, e.type, e.id); refresh()
            }.setNegativeButton("Cancel", null).show()
        })
        AlertDialog.Builder(ctx).setTitle(e.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun collection(e: CreationStore.Entry) {
        val ctx = requireContext()
        val existing = CreationStore.collections(ctx)
        val names = (existing + "New collection" + "No collection").toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Collection").setItems(names) { _, i ->
            when {
                i < existing.size -> { CreationStore.setCollection(ctx, e.type, e.id, existing[i]); refresh() }
                i == existing.size -> Ui.input(ctx, "Collection name", "") { n -> if (n.isNotEmpty()) { CreationStore.setCollection(ctx, e.type, e.id, n); refresh() } }
                else -> { CreationStore.setCollection(ctx, e.type, e.id, ""); refresh() }
            }
        }.show()
    }

    private fun share(e: CreationStore.Entry) {
        val ctx = requireContext()
        val text = CreationStore.exportText(ctx, e.type, e.id) ?: return
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Frame " + e.type + ": " + e.name) }
        startActivity(Intent.createChooser(send, "Share " + e.name))
    }

    private fun importDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx).setTitle("Import").setItems(arrayOf("Paste from clipboard", "Open a file")) { _, i ->
            if (i == 0) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                finishImport(if (t == null) null else CreationStore.importText(ctx, t))
            } else importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }.show()
    }

    private fun importFrom(uri: Uri) {
        val ctx = requireContext()
        val text = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (t: Throwable) { null }
        finishImport(if (text == null) null else CreationStore.importText(ctx, text))
    }

    private fun finishImport(e: CreationStore.Entry?) {
        val ctx = requireContext()
        if (e == null) Ui.toast(ctx, "That is not a Frame effect or layout") else { Ui.toast(ctx, "Imported ${e.name}"); refresh() }
    }
}
