package app.frame.camera.studio

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.frame.camera.R

/** Small view helpers shared by the creative workspaces (they are built in code, not XML). */
object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun color(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)

    fun chip(ctx: Context, text: String, on: Boolean = false, click: (() -> Unit)? = null): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
        setChipOn(this, on)
        if (click != null) setOnClickListener { click() }
    }

    fun setChipOn(t: TextView, on: Boolean) {
        val ctx = t.context
        t.setBackgroundResource(if (on) R.drawable.chip_cam_on else R.drawable.chip_cam_off)
        t.setTextColor(color(ctx, if (on) R.color.cam_ground else R.color.cam_ink))
    }

    fun label(ctx: Context, text: String, size: Float = 12f, dim: Boolean = true): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color(ctx, if (dim) R.color.cam_dim else R.color.cam_ink))
    }

    fun title(ctx: Context, text: String, size: Float = 20f): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color(ctx, R.color.cam_ink))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun col(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun scrollRow(ctx: Context, items: List<View>, h: Int = 36, gap: Int = 8): HorizontalScrollView {
        val sv = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val r = row(ctx)
        items.forEach { r.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, h)).also { lp -> lp.marginEnd = dp(ctx, gap) }) }
        sv.addView(r)
        return sv
    }

    class SliderHandle(val view: View, val set: (Float) -> Unit)

    /** A labelled slider. [onStart] runs once when a drag begins (used for undo checkpoints). */
    fun slider(ctx: Context, name: String, min: Float, max: Float, value: Float, fmt: (Float) -> String = { String.format("%.2f", it) },
               onStart: () -> Unit = {}, onChange: (Float) -> Unit): SliderHandle {
        val box = col(ctx)
        val top = row(ctx)
        val l = label(ctx, name, 12f)
        val v = label(ctx, fmt(value), 12f, false)
        top.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(v)
        val sb = SeekBar(ctx).apply { this.max = 1000; progress = (((value - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000) }
        var quiet = false
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                val x = min + (max - min) * p / 1000f
                v.text = fmt(x)
                if (user && !quiet) onChange(x)
            }
            override fun onStartTrackingTouch(s: SeekBar) { onStart() }
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(top)
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 30)))
        return SliderHandle(box) { x -> quiet = true; sb.progress = (((x - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000); v.text = fmt(x); quiet = false }
    }

    fun swatches(ctx: Context, colors: IntArray, selected: Int, pick: (Int) -> Unit): View {
        val r = row(ctx)
        val views = ArrayList<View>()
        fun paint() { views.forEachIndexed { i, vw ->
            (vw.background as GradientDrawable).setStroke(dp(ctx, if (colors[i] == selected) 3 else 1), if (colors[i] == selected) color(ctx, R.color.cam_accent) else 0x55FFFFFF)
        } }
        var cur = selected
        for (c in colors) {
            val v = View(ctx)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            v.setOnClickListener { cur = c; views.forEachIndexed { i, vw ->
                (vw.background as GradientDrawable).setStroke(dp(ctx, if (colors[i] == cur) 3 else 1), if (colors[i] == cur) color(ctx, R.color.cam_accent) else 0x55FFFFFF) }; pick(c) }
            views.add(v)
            r.addView(v, LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)).also { it.marginEnd = dp(ctx, 10) })
        }
        paint()
        return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    val PALETTE = intArrayOf(
        0xFF121315.toInt(), 0xFFFFFFFF.toInt(), 0xFFEFE8D8.toInt(), 0xFFC8B186.toInt(), 0xFFF6D9DE.toInt(), 0xFFFF9A8B.toInt(),
        0xFFFFE066.toInt(), 0xFF7CC8A4.toInt(), 0xFF5CA8FF.toInt(), 0xFF6C5CE7.toInt(), 0xFFE0405C.toInt(), 0xFF1F2A20.toInt()
    )

    fun input(ctx: Context, title: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(ctx).apply { setText(initial); setSelection(initial.length); setSingleLine(true); setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12)) }
        AlertDialog.Builder(ctx).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

    /** Saves a picture to Pictures/Frame. Returns true on success. Safe to call off the main thread. */
    fun saveJpeg(ctx: Context, bmp: Bitmap, prefix: String): Boolean {
        return try {
            val name = prefix + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Frame")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (t: Throwable) { false }
    }
}
