package app.frame.camera.fx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.frame.camera.R
import java.util.Locale

/**
 * Effect picker with thumbnails and the controls for the selected effect. Used in the camera sheet
 * and in the Effects tab, so both behave the same.
 */
class EffectPanel @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    interface Listener {
        /** [def] is the effect now active, or null when effects were switched off. */
        fun onActiveChanged(def: EffectDef?)
        /** Values for the active effect changed; read them with [EffectSettings.snapshot]. */
        fun onParamsChanged(def: EffectDef)
    }

    var listener: Listener? = null

    private lateinit var effects: List<EffectDef>
    private lateinit var settings: EffectSettings
    private var editing: EffectDef? = null
    private var active: EffectDef? = null
    private var unavailable: Map<String, String> = emptyMap()
    private var controlsMaxPx = 0

    private val tileRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tiles = HashMap<String?, View>()
    private val header = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val titleText = TextView(ctx)
    private val subText = TextView(ctx)
    private val toggleChip = TextView(ctx)
    private val resetChip = TextView(ctx)
    private val controls = LinearLayout(ctx).apply { orientation = VERTICAL }
    private lateinit var controlsScroll: ScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    fun bind(effects: List<EffectDef>, settings: EffectSettings, controlsMaxDp: Int) {
        this.effects = effects
        this.settings = settings
        controlsMaxPx = dp(controlsMaxDp)
        orientation = VERTICAL
        removeAllViews()

        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(tileRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tileRow.removeAllViews()
        tiles.clear()
        tileRow.addView(makeTile(null, "None"))
        effects.forEach { tileRow.addView(makeTile(it, it.name)) }

        titleText.apply { setTextColor(color(R.color.cam_ink)); textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        subText.apply { setTextColor(color(R.color.cam_dim)); textSize = 11f }
        val names = LinearLayout(context).apply { orientation = VERTICAL }
        names.addView(titleText)
        names.addView(subText)
        header.removeAllViews()
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        styleChip(toggleChip); styleChip(resetChip)
        resetChip.text = "Reset"
        resetChip.setOnClickListener { resetEditing() }
        toggleChip.setOnClickListener { toggleActive() }
        header.addView(toggleChip, chipLp())
        header.addView(resetChip, chipLp())
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        hlp.topMargin = dp(8)
        addView(header, hlp)

        controlsScroll = object : ScrollView(context) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(controlsMaxPx, MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
        controlsScroll.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refresh()
    }

    private fun chipLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).also { it.marginStart = dp(8) }

    private fun styleChip(t: TextView) {
        t.gravity = Gravity.CENTER
        t.textSize = 12f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setPadding(dp(14), 0, dp(14), 0)
        t.setBackgroundResource(R.drawable.chip_cam_off)
        t.setTextColor(color(R.color.cam_ink))
    }

    /** Effects that cannot be used right now, with the reason shown when one is tapped. */
    fun setUnavailable(map: Map<String, String>) {
        unavailable = map
        refresh()
    }

    /** Sets which effect is active (null = none) and which one is shown for editing. */
    fun setActive(id: String?) {
        active = effects.firstOrNull { it.id == id }
        if (active != null) editing = active
        refresh()
    }

    private fun makeTile(def: EffectDef?, label: String): View {
        val tile = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(color(R.color.cam_well))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) }
            }
            tag = "img"
        }
        tile.addView(img, LinearLayout.LayoutParams(dp(64), dp(80)))
        val name = TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tag = "name"
        }
        val nlp = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.topMargin = dp(4)
        tile.addView(name, nlp)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(6)
        tile.layoutParams = lp
        tile.setOnClickListener { onTile(def) }
        tiles[def?.id] = tile

        if (def == null) {
            FxHub.sampleBitmap { b -> if (b != null) img.setImageBitmap(b) }
        } else {
            FxHub.thumbnail(def) { b -> if (b != null) img.post { img.setImageBitmap(b) } }
        }
        return tile
    }

    /** Called after the hub has a thumbnail source; re-requests all thumbnails. */
    fun reloadThumbnails() {
        for ((id, tile) in tiles) {
            val img = tile.findViewWithTag<ImageView>("img") ?: continue
            val def = effects.firstOrNull { it.id == id }
            if (def == null) { FxHub.sampleBitmap { b -> if (b != null) img.post { img.setImageBitmap(b) } } }
            else FxHub.thumbnail(def) { b -> if (b != null) img.post { img.setImageBitmap(b) } }
        }
    }

    private fun onTile(def: EffectDef?) {
        if (def == null) {
            if (active != null) { active = null; listener?.onActiveChanged(null) }
            editing = null
            refresh()
            return
        }
        val why = unavailable[def.id]
        if (why != null) {
            Toast.makeText(context, "${def.name}: $why", Toast.LENGTH_SHORT).show()
            return
        }
        editing = def
        active = def
        refresh()
        listener?.onActiveChanged(def)
    }

    private fun toggleActive() {
        val e = editing ?: return
        if (active != null) {
            active = null
            listener?.onActiveChanged(null)
        } else {
            val why = unavailable[e.id]
            if (why != null) { Toast.makeText(context, "${e.name}: $why", Toast.LENGTH_SHORT).show(); return }
            active = e
            listener?.onActiveChanged(e)
        }
        refresh()
    }

    private fun resetEditing() {
        val e = editing ?: return
        settings.reset(e)
        buildControls()
        updateHeader()
        if (active?.id == e.id) listener?.onParamsChanged(e)
    }

    private fun refresh() {
        if (!::effects.isInitialized) return
        for ((id, tile) in tiles) {
            val isActive = id == active?.id && active != null
            val isNone = id == null && active == null
            val on = isActive || isNone
            tile.setBackgroundResource(if (on) R.drawable.chip_cam_on else R.drawable.chip_cam_off)
            tile.findViewWithTag<TextView>("name")?.setTextColor(color(if (on) R.color.cam_ground else R.color.cam_ink))
            tile.alpha = if (id != null && unavailable.containsKey(id)) 0.4f else 1f
        }
        buildControls()
        updateHeader()
    }

    private fun updateHeader() {
        val e = editing
        header.visibility = if (e == null) GONE else VISIBLE
        controlsScroll.visibility = if (e == null) GONE else VISIBLE
        if (e == null) return
        titleText.text = e.name
        subText.text = e.tagline
        toggleChip.text = if (active != null) "On" else "Off"
        toggleChip.setBackgroundResource(if (active != null) R.drawable.chip_cam_on else R.drawable.chip_cam_off)
        toggleChip.setTextColor(color(if (active != null) R.color.cam_ground else R.color.cam_ink))
        resetChip.alpha = if (settings.isDefault(e)) 0.4f else 1f
    }

    private fun buildControls() {
        controls.removeAllViews()
        val e = editing ?: return
        for (p in e.params) controls.addView(makeRow(e, p))
    }

    private fun makeRow(e: EffectDef, p: ParamDef): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(context).apply {
            text = p.label
            textSize = 12f
            setTextColor(color(R.color.cam_dim))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }
        row.addView(label, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (p.choices != null) {
            val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            val box = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val current = Math.round(settings.get(e, p))
            p.choices.forEachIndexed { i, text ->
                val chip = TextView(context)
                styleChip(chip)
                chip.setPadding(dp(12), 0, dp(12), 0)
                chip.text = text
                val on = i == current
                chip.setBackgroundResource(if (on) R.drawable.chip_cam_on else R.drawable.chip_cam_off)
                chip.setTextColor(color(if (on) R.color.cam_ground else R.color.cam_ink))
                chip.setOnClickListener {
                    settings.set(e, p, i.toFloat())
                    for (k in 0 until box.childCount) {
                        val c = box.getChildAt(k) as TextView
                        c.setBackgroundResource(if (k == i) R.drawable.chip_cam_on else R.drawable.chip_cam_off)
                        c.setTextColor(color(if (k == i) R.color.cam_ground else R.color.cam_ink))
                    }
                    updateHeader()
                    if (active?.id == e.id) listener?.onParamsChanged(e)
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
                lp.marginEnd = dp(6)
                box.addView(chip, lp)
            }
            scroll.addView(box)
            row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            val value = TextView(context).apply {
                textSize = 12f
                setTextColor(color(R.color.cam_ink))
                gravity = Gravity.END
            }
            val steps = 1000
            val seek = SeekBar(context).apply {
                max = steps
                progress = ((settings.get(e, p) - p.min) / (p.max - p.min) * steps).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.cam_accent))
                thumbTintList = android.content.res.ColorStateList.valueOf(color(R.color.cam_accent))
            }
            value.text = format(settings.get(e, p), p)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p.min + (p.max - p.min) * progress / steps
                    settings.set(e, p, v)
                    value.text = format(v, p)
                    if (active?.id == e.id) listener?.onParamsChanged(e)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { updateHeader() }
            })
            row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        return row
    }

    private fun format(v: Float, p: ParamDef): String =
        if (p.max - p.min >= 5f) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.2f", v)
}
