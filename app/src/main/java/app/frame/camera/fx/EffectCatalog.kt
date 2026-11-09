package app.frame.camera.fx

import android.content.Context
import org.json.JSONObject

data class ParamDef(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val choices: List<String>?
)

data class EffectDef(
    val id: String,
    val name: String,
    val tagline: String,
    /** none | motion | depth | mosh : what per-frame history the effect needs. */
    val hist: String,
    /** True when the look animates by itself over time. */
    val live: Boolean,
    val shader: String,
    val params: List<ParamDef>
) {
    /** True when the effect compares frames, so a single photo has no motion to show. */
    val needsMotion: Boolean get() = hist == "motion" || hist == "mosh"
    val specific: List<ParamDef> get() = params.drop(4)
}

/** Single source of truth for the ten effects. The WebGL test harness reads the same file. */
object EffectCatalog {
    @Volatile private var cache: List<EffectDef>? = null

    fun all(ctx: Context): List<EffectDef> {
        cache?.let { return it }
        val text = ctx.applicationContext.assets.open("effects.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("effects")
        val list = ArrayList<EffectDef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ps = o.getJSONArray("params")
            val params = ArrayList<ParamDef>()
            for (j in 0 until ps.length()) {
                val p = ps.getJSONObject(j)
                val ch = p.optJSONArray("choices")
                val choices = if (ch == null) null else List(ch.length()) { ch.getString(it) }
                params.add(
                    ParamDef(
                        p.getString("id"), p.getString("label"),
                        p.getDouble("min").toFloat(), p.getDouble("max").toFloat(),
                        p.getDouble("def").toFloat(), choices
                    )
                )
            }
            list.add(
                EffectDef(
                    o.getString("id"), o.getString("name"), o.getString("tagline"),
                    o.getString("hist"), o.getBoolean("live"), o.getString("shader"), params
                )
            )
        }
        cache = list
        return list
    }

    fun byId(ctx: Context, id: String?): EffectDef? = all(ctx).firstOrNull { it.id == id }
}

/** Per-effect parameter values, persisted so settings survive restarts and camera switches. */
class EffectSettings(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("frame_fx", Context.MODE_PRIVATE)

    var activeId: String?
        get() = prefs.getString("active", null)
        set(v) { prefs.edit().putString("active", v).apply() }

    fun get(effect: EffectDef, param: ParamDef): Float {
        val v = prefs.getFloat("${effect.id}.${param.id}", param.def)
        return v.coerceIn(param.min, param.max)
    }

    fun set(effect: EffectDef, param: ParamDef, value: Float) {
        prefs.edit().putFloat("${effect.id}.${param.id}", value.coerceIn(param.min, param.max)).apply()
    }

    fun reset(effect: EffectDef) {
        val e = prefs.edit()
        effect.params.forEach { e.remove("${effect.id}.${it.id}") }
        e.apply()
    }

    fun isDefault(effect: EffectDef): Boolean =
        effect.params.all { kotlin.math.abs(get(effect, it) - it.def) < 1e-4f }

    /** [intensity, scale, contrast, brightness, p0, p1, p2, p3] in the order the shaders expect. */
    fun snapshot(effect: EffectDef): FloatArray {
        val out = FloatArray(8)
        effect.params.forEachIndexed { i, p -> if (i < 8) out[i] = get(effect, p) }
        return out
    }
}
