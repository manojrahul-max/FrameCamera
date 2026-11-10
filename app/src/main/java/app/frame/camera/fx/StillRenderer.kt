package app.frame.camera.fx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies an effect to a Bitmap on the GPU, off the main thread. Used for the effect thumbnails,
 * the Effects Lab preview and for exporting a processed picture. Also serves as the preflight
 * probe: an effect is compiled here before the camera is ever bound to it.
 */
class StillRenderer(private val ctx: Context) {
    private val thread = HandlerThread("frame-fx-still").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var egl: EglCore? = null
    private var renderer: EffectRenderer? = null

    private fun gl(): EffectRenderer {
        if (egl == null) {
            egl = EglCore().also { it.makeCurrentOffscreen() }
            renderer = EffectRenderer(ctx, false)
        }
        egl!!.makeCurrentOffscreen()
        return renderer!!
    }

    /** Checks that [def] compiles and runs on this GPU. Reports a readable reason when it does not. */
    fun probe(def: EffectDef, done: (String?) -> Unit) {
        handler.post {
            val err = try {
                val r = gl()
                r.setEffect(def)
                r.setEffect(null)
                null
            } catch (t: Throwable) {
                t.message ?: "This effect is not supported on this device"
            }
            main.post { done(err) }
        }
    }

    /**
     * Renders [def] over [frames]. Single frame effects use frames.last(). Motion effects feed all
     * frames in order so they have real frame to frame motion to analyse.
     */
    fun render(def: EffectDef, params: FloatArray, frames: List<Bitmap>, maxSide: Int, timeSec: Float,
               custom: app.frame.camera.studio.CustomPayload? = null,
               done: (Bitmap?, String?) -> Unit) {
        handler.post {
            var result: Bitmap? = null
            var error: String? = null
            try {
                result = renderNow(def, params, frames, maxSide, timeSec, custom)
            } catch (t: Throwable) {
                error = t.message ?: "The effect could not be applied"
            }
            main.post { done(result, error) }
        }
    }

    private fun renderNow(def: EffectDef, params: FloatArray, frames: List<Bitmap>, maxSide: Int, timeSec: Float,
                          custom: app.frame.camera.studio.CustomPayload? = null): Bitmap {
        val r = gl()
        r.setEffect(def)
        r.setCustom(custom)
        r.resetHistory()
        val last = frames.last()
        val scale = minOf(1f, maxSide.toFloat() / maxOf(last.width, last.height))
        val w = maxOf(2, (last.width * scale).toInt())
        val h = maxOf(2, (last.height * scale).toInt())
        val srcTex = Gl.newTexture()
        val (outTex, outFbo) = Gl.allocTarget(w, h)
        try {
            val texMat = FloatArray(16)
            Matrix.setIdentityM(texMat, 0)
            texMat[5] = -1f; texMat[13] = 1f      // bitmaps have their first row at the top
            val ident = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            for ((i, f) in frames.withIndex()) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, f, 0)
                r.prepareFrame(srcTex, texMat, f.width, f.height, params, timeSec + i / 30f)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            r.draw(srcTex, texMat, ident, w, h, params, timeSec + frames.size / 30f, outFbo)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            raw.copyPixelsFromBuffer(buf)
            val m = android.graphics.Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
            val out = Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
            if (out !== raw) raw.recycle()
            return out
        } finally {
            Gl.deleteTexture(srcTex); Gl.deleteFbo(outFbo); Gl.deleteTexture(outTex)
        }
    }

    fun release() {
        handler.post {
            try {
                egl?.makeCurrentOffscreen()
                renderer?.release()
                egl?.release()
            } catch (_: Throwable) {
            } finally {
                renderer = null; egl = null
                thread.quitSafely()
            }
        }
    }
}

/** A drawn scene used for effect thumbnails when no photo is loaded. Frame [i] moves the ball. */
object SampleScene {
    fun frame(w: Int, h: Int, i: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, 0xFF2B3A67.toInt(), 0xFFF2A65A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFFFE9A8.toInt()
        c.drawCircle(w * 0.72f, h * 0.30f, h * 0.11f, p)
        p.color = 0xFF1F2A44.toInt()
        val hills = Path().apply {
            moveTo(0f, h * 0.70f); quadTo(w * 0.25f, h * 0.52f, w * 0.5f, h * 0.68f)
            quadTo(w * 0.75f, h * 0.80f, w.toFloat(), h * 0.60f); lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(hills, p)
        p.color = 0xFF3C5A3A.toInt()
        c.drawRect(0f, h * 0.84f, w.toFloat(), h.toFloat(), p)
        p.color = 0xFFEDE7DA.toInt()
        c.drawRect(w * 0.12f, h * 0.45f, w * 0.30f, h * 0.84f, p)
        p.color = 0xFF30343F.toInt()
        c.drawRect(w * 0.17f, h * 0.55f, w * 0.23f, h * 0.65f, p)
        val bx = w * (0.38f + 0.04f * i)
        val by = h * 0.74f
        val r = h * 0.09f
        p.shader = RadialGradient(bx - r * 0.3f, by - r * 0.3f, r * 1.2f, 0xFFFFF3B0.toInt(), 0xFFE8571F.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(bx, by, r, p)
        return b
    }
}
