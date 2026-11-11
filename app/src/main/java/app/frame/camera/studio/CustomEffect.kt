package app.frame.camera.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import app.frame.camera.fx.EffectDef
import app.frame.camera.fx.ParamDef
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

class Field(
    val key: String, val label: String, val min: Float, val max: Float, val def: Float,
    val group: String, val arr: Int, val comp: Int, val choices: List<String>?
)

/** Field layout shared with the GPU shader and the WebGL test page (assets/custom_schema.json). */
object CustomSchema {
    const val MAX_LAYERS = 4
    const val ARRAYS = 9
    private var adjustments: List<Field> = emptyList()
    private var maskFields: List<Field> = emptyList()

    fun load(ctx: Context) {
        if (adjustments.isNotEmpty()) return
        val o = JSONObject(ctx.applicationContext.assets.open("custom_schema.json").bufferedReader().use { it.readText() })
        adjustments = parse(o.getJSONArray("adjustments"))
        maskFields = parse(o.getJSONArray("mask"))
    }

    private fun parse(a: JSONArray): List<Field> = List(a.length()) {
        val f = a.getJSONObject(it)
        val ch = f.optJSONArray("choices")
        Field(
            f.getString("key"), f.getString("label"), f.getDouble("min").toFloat(), f.getDouble("max").toFloat(),
            f.getDouble("def").toFloat(), f.getString("group"), f.getInt("arr"), f.getInt("comp"),
            if (ch == null) null else List(ch.length()) { i -> ch.getString(i) }
        )
    }

    val adj: List<Field> get() = adjustments
    val mask: List<Field> get() = maskFields
    val all: List<Field> get() = adjustments + maskFields
    fun field(key: String): Field = all.first { it.key == key }
}

/** One brush stroke. Points are x,y pairs in 0..1 view space; size is a fraction of the view width. */
class Stroke(val pts: FloatArray, val size: Float, val softness: Float, val strength: Float, val erase: Boolean)

class CLayer(
    var name: String,
    var enabled: Boolean = true,
    val values: HashMap<String, Float> = HashMap(),
    val strokes: MutableList<Stroke> = ArrayList()
) {
    fun get(f: Field): Float = values[f.key] ?: f.def
    fun set(f: Field, v: Float) { values[f.key] = v.coerceIn(f.min, f.max) }
    fun copy(): CLayer = CLayer(name, enabled, HashMap(values), strokes.toMutableList())
    val maskType: Int get() = Math.round(values["maskType"] ?: 0f)
}

/** What the GPU needs: packed layer values and the brush masks of the four layers (RGBA, one layer per channel). */
class CustomPayload(val uniforms: FloatArray, val mask: ByteArray?, val maskW: Int, val maskH: Int, val version: Int)

class CustomEffect(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "My effect",
    val layers: MutableList<CLayer> = ArrayList(),
    var intensity: Float = 1f
) {
    /** Changes whenever any brush stroke changes; unique across effects so GPU mask uploads can be skipped safely. */
    var strokeRev: Int = REV.incrementAndGet()
    private var cacheKey = -1
    private var cacheMask: ByteArray? = null

    fun touchStrokes() { strokeRev = REV.incrementAndGet() }

    fun copy(): CustomEffect = CustomEffect(id, name, layers.map { it.copy() }.toMutableList(), intensity).also { it.strokeRev = strokeRev }

    fun duplicate(): CustomEffect = copy().also { it.id = UUID.randomUUID().toString(); it.name = "$name copy" }

    fun pack(): FloatArray {
        val out = FloatArray(CustomSchema.ARRAYS * 16)
        for (l in 0 until CustomSchema.MAX_LAYERS) {
            val layer = layers.getOrNull(l)
            for (f in CustomSchema.all) {
                val v = when {
                    layer == null -> if (f.key == "opacity") 0f else f.def
                    f.key == "opacity" -> if (layer.enabled) layer.get(f) else 0f
                    else -> layer.get(f)
                }
                out[f.arr * 16 + l * 4 + f.comp] = v
            }
        }
        return out
    }

    fun payload(viewAspect: Float): CustomPayload {
        val w = 256
        val h = Math.max(16, Math.round(w / viewAspect))
        val hasBrush = layers.any { it.maskType == 4 && it.strokes.isNotEmpty() }
        val key = strokeRev * 4096 + h
        var mask: ByteArray? = null
        if (hasBrush) {
            if (cacheKey != key || cacheMask == null) { cacheMask = rasterize(w, h); cacheKey = key }
            mask = cacheMask
        }
        return CustomPayload(pack(), mask, w, h, if (hasBrush) key else -key - 1)
    }

    /** Draws every layer's strokes into one RGBA buffer, layer 0 in red up to layer 3 in alpha. */
    fun rasterize(w: Int, h: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        val bytes = ByteArray(w * h)
        for ((li, layer) in layers.withIndex()) {
            if (li >= CustomSchema.MAX_LAYERS) break
            if (layer.strokes.isEmpty()) continue
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            for (s in layer.strokes) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = Math.max(1f, s.size * w)
                    alpha = (s.strength.coerceIn(0f, 1f) * 255).toInt()
                    if (s.softness > 0.03f) maskFilter = BlurMaskFilter(Math.max(1f, s.softness * s.size * w * 0.7f), BlurMaskFilter.Blur.NORMAL)
                    if (s.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                if (s.pts.size < 4) {
                    c.drawPoint(s.pts[0] * w, s.pts[1] * h, p)
                } else {
                    val path = Path()
                    path.moveTo(s.pts[0] * w, s.pts[1] * h)
                    var i = 2
                    while (i + 1 < s.pts.size) { path.lineTo(s.pts[i] * w, s.pts[i + 1] * h); i += 2 }
                    c.drawPath(path, p)
                }
            }
            val buf = ByteBuffer.wrap(bytes)
            bmp.copyPixelsToBuffer(buf)
            bmp.recycle()
            for (i in 0 until w * h) rgba[i * 4 + li] = bytes[i]
        }
        return rgba
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("intensity", intensity.toDouble())
        val ls = JSONArray()
        for (l in layers) {
            val lo = JSONObject()
            lo.put("name", l.name); lo.put("enabled", l.enabled)
            val vals = JSONObject()
            for ((k, v) in l.values) vals.put(k, v.toDouble())
            lo.put("values", vals)
            val ss = JSONArray()
            for (s in l.strokes) {
                val so = JSONObject()
                val pa = JSONArray()
                for (v in s.pts) pa.put(Math.round(v * 10000f) / 10000.0)
                so.put("p", pa); so.put("s", s.size.toDouble()); so.put("f", s.softness.toDouble())
                so.put("a", s.strength.toDouble()); so.put("e", s.erase)
                ss.put(so)
            }
            lo.put("strokes", ss)
            ls.put(lo)
        }
        o.put("layers", ls)
        return o
    }

    companion object {
        private val REV = java.util.concurrent.atomic.AtomicInteger(1)

        fun fromJson(o: JSONObject): CustomEffect {
            val e = CustomEffect(o.optString("id", UUID.randomUUID().toString()).ifBlank { UUID.randomUUID().toString() }, o.optString("name", "My effect"))
            e.intensity = o.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1f)
            val ls = o.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until Math.min(ls.length(), CustomSchema.MAX_LAYERS)) {
                val lo = ls.getJSONObject(i)
                val layer = CLayer(lo.optString("name", "Layer ${i + 1}"), lo.optBoolean("enabled", true))
                val vals = lo.optJSONObject("values")
                if (vals != null) {
                    val keys = vals.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val f = CustomSchema.all.firstOrNull { it.key == k } ?: continue
                        layer.values[k] = vals.getDouble(k).toFloat().coerceIn(f.min, f.max)
                    }
                }
                val ss = lo.optJSONArray("strokes")
                if (ss != null) for (j in 0 until ss.length()) {
                    val so = ss.getJSONObject(j)
                    val pa = so.getJSONArray("p")
                    layer.strokes.add(
                        Stroke(FloatArray(pa.length()) { pa.getDouble(it).toFloat() }, so.getDouble("s").toFloat(),
                            so.getDouble("f").toFloat(), so.getDouble("a").toFloat(), so.optBoolean("e", false))
                    )
                }
                e.layers.add(layer)
            }
            if (e.layers.isEmpty()) e.layers.add(CLayer("Layer 1"))
            return e
        }

        fun fromJson(text: String): CustomEffect = fromJson(JSONObject(text))

        /** Creative presets shipped with the app (assets/presets.json). */
        fun presets(ctx: Context): List<CustomEffect> {
            CustomSchema.load(ctx)
            val o = JSONObject(ctx.applicationContext.assets.open("presets.json").bufferedReader().use { it.readText() })
            val arr = o.getJSONArray("presets")
            return List(arr.length()) {
                val p = arr.getJSONObject(it)
                val e = CustomEffect("preset_" + p.getString("id"), p.getString("name"))
                val ls = p.getJSONArray("layers")
                for (i in 0 until ls.length()) {
                    val lo = ls.getJSONObject(i)
                    val layer = CLayer(lo.getString("name"))
                    val v = lo.getJSONObject("values")
                    val keys = v.keys()
                    while (keys.hasNext()) { val k = keys.next(); layer.values[k] = v.getDouble(k).toFloat() }
                    e.layers.add(layer)
                }
                e
            }
        }

        fun presetDescription(ctx: Context, id: String): String {
            val o = JSONObject(ctx.applicationContext.assets.open("presets.json").bufferedReader().use { it.readText() })
            val arr = o.getJSONArray("presets")
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if ("preset_" + p.getString("id") == id) return p.getString("desc")
            }
            return ""
        }

        /** The effect definition the GPU renderer uses for every custom effect. */
        val DEF = EffectDef(
            "custom", "Custom", "Your own layers and masks", "none", true, "effect_custom",
            listOf(
                ParamDef("intensity", "Intensity", 0f, 1f, 1f, null),
                ParamDef("scale", "Scale", 0.5f, 3f, 1f, null),
                ParamDef("contrast", "Contrast", 0.5f, 2f, 1f, null),
                ParamDef("brightness", "Brightness", -0.4f, 0.4f, 0f, null)
            )
        )

        fun params(e: CustomEffect): FloatArray = floatArrayOf(e.intensity, 1f, 1f, 0f, 0f, 0f, 0f, 0f)
    }
}
