package app.frame.camera.studio

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Saved effects and collage layouts. One small JSON file each, in the app's private storage. */
object CreationStore {
    const val EFFECT = "effect"
    const val LAYOUT = "layout"

    class Entry(val id: String, val type: String, val name: String, val fav: Boolean, val collection: String, val modified: Long)

    private fun dir(ctx: Context, type: String): File =
        File(ctx.applicationContext.filesDir, "creations/$type").also { it.mkdirs() }

    private fun file(ctx: Context, type: String, id: String) = File(dir(ctx, type), id.filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".json")

    private fun read(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (t: Throwable) { null }

    fun list(ctx: Context, type: String): List<Entry> {
        val out = ArrayList<Entry>()
        dir(ctx, type).listFiles()?.forEach { f ->
            val o = read(f) ?: return@forEach
            val d = o.optJSONObject("data") ?: return@forEach
            out.add(Entry(d.optString("id"), type, d.optString("name", "Untitled"), o.optBoolean("fav"), o.optString("collection"), o.optLong("modified")))
        }
        return out.sortedWith(compareByDescending<Entry> { it.fav }.thenByDescending { it.modified })
    }

    private fun write(ctx: Context, type: String, id: String, data: JSONObject, fav: Boolean?, collection: String?) {
        val f = file(ctx, type, id)
        val old = if (f.exists()) read(f) else null
        val o = JSONObject()
        o.put("v", 1); o.put("type", type)
        o.put("fav", fav ?: old?.optBoolean("fav") ?: false)
        o.put("collection", collection ?: old?.optString("collection") ?: "")
        o.put("modified", System.currentTimeMillis())
        o.put("data", data)
        f.writeText(o.toString())
    }

    fun saveEffect(ctx: Context, e: CustomEffect) = write(ctx, EFFECT, e.id, e.toJson(), null, null)
    fun saveLayout(ctx: Context, c: Collage) = write(ctx, LAYOUT, c.id, CollageJson.toJson(c), null, null)

    fun loadEffect(ctx: Context, id: String): CustomEffect? {
        CustomSchema.load(ctx)
        val d = read(file(ctx, EFFECT, id))?.optJSONObject("data") ?: return null
        return try { CustomEffect.fromJson(d) } catch (t: Throwable) { null }
    }

    fun loadLayout(ctx: Context, id: String): Collage? {
        val d = read(file(ctx, LAYOUT, id))?.optJSONObject("data") ?: return null
        return try { CollageJson.fromJson(d) } catch (t: Throwable) { null }
    }

    fun delete(ctx: Context, type: String, id: String) { file(ctx, type, id).delete() }

    private fun patch(ctx: Context, type: String, id: String, edit: (JSONObject) -> Unit) {
        val f = file(ctx, type, id); val o = read(f) ?: return
        edit(o); f.writeText(o.toString())
    }

    fun setFavorite(ctx: Context, type: String, id: String, fav: Boolean) = patch(ctx, type, id) { it.put("fav", fav) }
    fun setCollection(ctx: Context, type: String, id: String, name: String) = patch(ctx, type, id) { it.put("collection", name.trim()) }
    fun rename(ctx: Context, type: String, id: String, name: String) = patch(ctx, type, id) { it.getJSONObject("data").put("name", name.trim().ifEmpty { "Untitled" }) }

    fun collections(ctx: Context): List<String> =
        (list(ctx, EFFECT) + list(ctx, LAYOUT)).map { it.collection }.filter { it.isNotBlank() }.distinct().sorted()

    fun duplicate(ctx: Context, type: String, id: String): String? {
        if (type == EFFECT) { val e = loadEffect(ctx, id)?.duplicate() ?: return null; saveEffect(ctx, e); return e.id }
        val c = loadLayout(ctx, id) ?: return null
        val n = c.layoutCopy(); n.id = java.util.UUID.randomUUID().toString(); n.name = c.name + " copy"
        saveLayout(ctx, n); return n.id
    }

    /** The text that is shared or exported. */
    fun exportText(ctx: Context, type: String, id: String): String? {
        val o = read(file(ctx, type, id)) ?: return null
        val out = JSONObject()
        out.put("app", "frame"); out.put("v", 1); out.put("type", type); out.put("data", o.getJSONObject("data"))
        return out.toString()
    }

    /** Reads shared text and saves it as a new creation. Returns null when the text is not a Frame creation. */
    fun importText(ctx: Context, text: String): Entry? {
        return try {
            CustomSchema.load(ctx)
            val o = JSONObject(text.trim())
            if (o.optString("app") != "frame") return null
            val type = o.getString("type"); val d = o.getJSONObject("data")
            val newId = java.util.UUID.randomUUID().toString()
            if (type == EFFECT) {
                val e = CustomEffect.fromJson(d); e.id = newId; saveEffect(ctx, e)
                Entry(newId, type, e.name, false, "", System.currentTimeMillis())
            } else if (type == LAYOUT) {
                val c = CollageJson.fromJson(d); c.id = newId
                if (c.frames.isEmpty()) return null
                saveLayout(ctx, c)
                Entry(newId, type, c.name, false, "", System.currentTimeMillis())
            } else null
        } catch (t: Throwable) { null }
    }

    // The effect chosen to run on the main camera.
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("studio", Context.MODE_PRIVATE)
    fun cameraEffectId(ctx: Context): String? = prefs(ctx).getString("camera_effect", null)
    fun setCameraEffect(ctx: Context, id: String?) { prefs(ctx).edit().apply { if (id == null) remove("camera_effect") else putString("camera_effect", id) }.apply() }
}
