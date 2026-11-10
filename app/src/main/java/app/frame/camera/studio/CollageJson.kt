package app.frame.camera.studio

import org.json.JSONArray
import org.json.JSONObject

/** Saves and loads collage layouts. Photos are never stored, only the layout and style. */
object CollageJson {
    fun toJson(c: Collage): JSONObject {
        val o = JSONObject()
        o.put("id", c.id); o.put("name", c.name); o.put("aspect", c.aspect.toDouble())
        o.put("gap", c.gap.toDouble()); o.put("outer", c.outer.toDouble()); o.put("radius", c.radius.toDouble())
        o.put("borderW", c.borderW.toDouble()); o.put("borderColor", c.borderColor)
        o.put("bg1", c.bg1); o.put("bg2", c.bg2); o.put("bgAngle", c.bgAngle.toDouble()); o.put("gradient", c.gradient)
        o.put("texture", c.texture); o.put("shadow", c.shadow)
        val fs = JSONArray()
        for (f in c.frames) {
            val fo = JSONObject(); fo.put("shape", f.shape)
            val pa = JSONArray(); for (v in f.pts) pa.put(Math.round(v * 100000f) / 100000.0); fo.put("pts", pa)
            fs.put(fo)
        }
        o.put("frames", fs)
        val ds = JSONArray()
        for (d in c.decos) {
            val dobj = JSONObject()
            dobj.put("k", d.kind); dobj.put("x", d.x.toDouble()); dobj.put("y", d.y.toDouble()); dobj.put("w", d.w.toDouble()); dobj.put("h", d.h.toDouble())
            dobj.put("rot", d.rot.toDouble()); dobj.put("color", d.color); dobj.put("text", d.text); dobj.put("size", d.size.toDouble())
            dobj.put("below", d.below); dobj.put("shadow", d.shadow); dobj.put("st", d.sticker); dobj.put("bold", d.bold)
            dobj.put("serif", d.serif); dobj.put("round", d.round.toDouble())
            ds.put(dobj)
        }
        o.put("decos", ds)
        return o
    }

    fun fromJson(o: JSONObject): Collage {
        val c = Collage(o.optString("id", java.util.UUID.randomUUID().toString()), o.optString("name", "Layout"), o.optDouble("aspect", 0.8).toFloat().coerceIn(0.3f, 3f))
        c.gap = o.optDouble("gap", 0.012).toFloat(); c.outer = o.optDouble("outer", 0.0).toFloat(); c.radius = o.optDouble("radius", 0.0).toFloat()
        c.borderW = o.optDouble("borderW", 0.0).toFloat(); c.borderColor = o.optInt("borderColor", -1)
        c.bg1 = o.optInt("bg1", 0xFF121315.toInt()); c.bg2 = o.optInt("bg2", c.bg1); c.bgAngle = o.optDouble("bgAngle", 90.0).toFloat()
        c.gradient = o.optBoolean("gradient"); c.texture = o.optInt("texture"); c.shadow = o.optBoolean("shadow")
        val fs = o.optJSONArray("frames") ?: JSONArray()
        for (i in 0 until fs.length()) {
            val fo = fs.getJSONObject(i); val pa = fo.getJSONArray("pts")
            if (pa.length() < 6) continue
            c.add(FloatArray(pa.length()) { pa.getDouble(it).toFloat() }, fo.optInt("shape"))
        }
        val ds = o.optJSONArray("decos") ?: JSONArray()
        for (i in 0 until ds.length()) {
            val d = ds.getJSONObject(i)
            c.decos.add(Deco(d.getInt("k"), d.getDouble("x").toFloat(), d.getDouble("y").toFloat(), d.optDouble("w").toFloat(), d.optDouble("h").toFloat(),
                d.optDouble("rot").toFloat(), d.optInt("color", -1), d.optString("text"), d.optDouble("size", 0.05).toFloat(), d.optBoolean("below"),
                d.optBoolean("shadow"), d.optInt("st"), d.optBoolean("bold", true), d.optBoolean("serif"), d.optDouble("round").toFloat()))
        }
        return c
    }
}
