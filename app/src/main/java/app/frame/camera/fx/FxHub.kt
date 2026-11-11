package app.frame.camera.fx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

/**
 * Shared, reference counted owner of the still renderer and the thumbnail cache. The GL thread
 * behind it is created when the first screen needs it and released when the last screen goes away.
 */
object FxHub {
    private var still: StillRenderer? = null
    private var refs = 0
    private val thumbs = HashMap<String, Bitmap>()
    private val pending = HashSet<String>()
    private val probed = HashMap<String, String?>()
    private var source: Bitmap? = null
    private var sourceVersion = 0

    const val THUMB_W = 128
    const val THUMB_H = 160

    @Synchronized fun acquire(ctx: Context) {
        if (still == null) still = StillRenderer(ctx.applicationContext)
        refs++
    }

    @Synchronized fun release() {
        refs--
        if (refs <= 0) {
            refs = 0
            still?.release()
            still = null
            thumbs.clear()
            pending.clear()
        }
    }

    /** Photo to use for thumbnails. Null means the built-in sample scene. */
    @Synchronized fun setSource(bmp: Bitmap?) {
        source = bmp
        sourceVersion++
        thumbs.clear()
        pending.clear()
    }

    /** Checks once per effect that it compiles on this GPU. Callback gets null when fine, otherwise the reason. */
    fun probe(def: EffectDef, cb: (String?) -> Unit) {
        val cached: Boolean
        val cachedErr: String?
        val s: StillRenderer?
        synchronized(this) {
            cached = probed.containsKey(def.id)
            cachedErr = probed[def.id]
            s = still
        }
        if (cached) { cb(cachedErr); return }
        if (s == null) { cb("Effects are not ready"); return }
        s.probe(def) { err ->
            synchronized(this) { probed[def.id] = err }
            cb(err)
        }
    }

    fun probeResult(id: String): String? = synchronized(this) { probed[id] }
    fun isProbeFailed(id: String): Boolean = synchronized(this) { probed.containsKey(id) && probed[id] != null }

    /** The unprocessed sample (or loaded photo) used for the "None" tile. */
    fun sampleBitmap(cb: (Bitmap?) -> Unit) {
        val src = synchronized(this) { source }
        cb(if (src != null) cover(src) else SampleScene.frame(THUMB_W, THUMB_H, 2))
    }

    /** Renders (or returns the cached) thumbnail for [def] with default settings. */
    fun thumbnail(def: EffectDef, cb: (Bitmap?) -> Unit) {
        val key: String
        val s: StillRenderer
        val frames: List<Bitmap>
        synchronized(this) {
            key = "${def.id}#$sourceVersion"
            thumbs[key]?.let { cb(it); return }
            s = still ?: run { cb(null); return }
            if (!pending.add(key)) { return }
        }
        val src = synchronized(this) { source }
        frames = if (src != null && !def.needsMotion) listOf(cover(src)) else {
            val n = if (def.needsMotion) 6 else 1
            List(n) { SampleScene.frame(THUMB_W, THUMB_H, if (def.needsMotion) it else 2) }
        }
        val params = FloatArray(8).also { a -> def.params.forEachIndexed { i, p -> if (i < 8) a[i] = p.def } }
        s.render(def, params, frames, maxOf(THUMB_W, THUMB_H), 1.5f) { bmp, _ ->
            frames.forEach { if (it !== src) it.recycle() }
            synchronized(this) {
                pending.remove(key)
                if (bmp != null && key.endsWith("#$sourceVersion")) thumbs[key] = bmp
            }
            cb(bmp)
        }
    }

    /** Applies [def] with [params] to a full picture. Used by the Effects tab preview and export. */
    fun renderPicture(def: EffectDef, params: FloatArray, bmp: Bitmap, maxSide: Int, cb: (Bitmap?, String?) -> Unit) {
        val s = synchronized(this) { still }
        if (s == null) { cb(null, "Effects are not ready"); return }
        s.render(def, params, listOf(bmp), maxSide, 1.5f, null, cb)
    }

    /** Applies a custom layered effect to a picture, with the same shader the live camera uses. */
    fun renderCustom(e: app.frame.camera.studio.CustomEffect, bmp: Bitmap, maxSide: Int, cb: (Bitmap?, String?) -> Unit) {
        val s = synchronized(this) { still }
        if (s == null) { cb(null, "Effects are not ready"); return }
        val aspect = bmp.width.toFloat() / bmp.height
        s.render(app.frame.camera.studio.CustomEffect.DEF, app.frame.camera.studio.CustomEffect.params(e),
            listOf(bmp), maxSide, 1.5f, e.payload(aspect), cb)
    }

    private fun cover(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
        val target = THUMB_W.toFloat() / THUMB_H
        val sr = src.width.toFloat() / src.height
        val crop = if (sr > target) {
            val w = (src.height * target).toInt()
            Rect((src.width - w) / 2, 0, (src.width + w) / 2, src.height)
        } else {
            val h = (src.width / target).toInt()
            Rect(0, (src.height - h) / 2, src.width, (src.height + h) / 2)
        }
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, THUMB_W, THUMB_H), null)
        return out
    }
}
