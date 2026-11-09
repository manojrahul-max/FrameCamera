package app.frame.camera.fx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL-thread-only renderer that runs one effect. It owns the shader programs and the small
 * history textures that motion based effects need. Nothing is allocated until an effect is
 * selected, and everything is freed when the effect is cleared, so an inactive effect costs nothing.
 *
 * Pass order per camera frame: history pass (low resolution), optional datamosh pass, then one
 * draw per output surface (preview, recorder, snapshot).
 */
class EffectRenderer(private val ctx: Context, private val external: Boolean) {

    companion object {
        private const val VS = "attribute vec4 aPosition;\nuniform mat4 uTransMatrix;\nvoid main(){gl_Position=uTransMatrix*aPosition;}\n"
        private const val HIST_LONG = 384
        private const val MOSH_LONG = 640
        private val sources = HashMap<String, String>()
        const val GLYPH_CELL_W = 32
        const val GLYPH_CELL_H = 56
        val GLYPH_SETS = arrayOf(" .:-=+*#%@", " 1-7456098", " .+x*o&\$%@", " .ilxoXKMW")
    }

    var effect: EffectDef? = null
        private set

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val locs = HashMap<Int, HashMap<String, Int>>()

    private var progEffect = 0
    private var progHist = 0
    private var progMosh = 0
    private var glyphTex = 0

    private val histTex = IntArray(2)
    private val histFbo = IntArray(2)
    private val moshTex = IntArray(2)
    private val moshFbo = IntArray(2)
    private var histW = 0
    private var histH = 0
    private var moshW = 0
    private var moshH = 0
    private var cur = 0
    private var mcur = 0
    private var first = true

    private fun asset(name: String): String = synchronized(sources) {
        sources.getOrPut(name) { ctx.applicationContext.assets.open("shaders/$name").bufferedReader().use { it.readText() } }
    }

    /** Compiles the shaders for [def] (or clears the effect when null). Throws [FxException] on failure. */
    fun setEffect(def: EffectDef?) {
        if (def != null && def.id == effect?.id && progEffect != 0) return
        releaseAll()
        effect = null
        if (def == null) return
        try {
            val head = (if (external) "#extension GL_OES_EGL_image_external : require\n#define EXTERNAL_TEX\n" else "") +
                (if (def.hist == "depth") "#define HIST_DEPTH\n" else "")
            val common = asset("common.glsl")
            progEffect = Gl.program(VS, head + common + asset(def.shader + ".glsl") + asset("main_effect.glsl"))
            if (def.hist != "none") progHist = Gl.program(VS, head + common + asset("hist.glsl"))
            if (def.hist == "mosh") progMosh = Gl.program(VS, head + common + asset("mosh.glsl"))
            if (def.id == "glyph") glyphTex = makeGlyphAtlas()
            effect = def
            first = true
        } catch (t: Throwable) {
            releaseAll()
            effect = null
            if (t is FxException) throw t
            throw FxException("Effect ${def.name} could not start: ${t.message}", t)
        }
    }

    /** Runs the per-frame history passes once per camera frame. */
    fun prepareFrame(srcTex: Int, texMat: FloatArray, streamW: Int, streamH: Int, params: FloatArray, time: Float) {
        val def = effect ?: return
        if (def.hist == "none") return
        ensureHistory(streamW, streamH, def)
        val prev = cur
        val n = 1 - cur
        pass(progHist, histFbo[n], histW, histH, identity, texMat, params, time, srcTex,
            histTex[prev], histTex[prev], 0)
        cur = n
        if (def.hist == "mosh") {
            val mn = 1 - mcur
            pass(progMosh, moshFbo[mn], moshW, moshH, identity, texMat, params, time, srcTex,
                histTex[cur], histTex[prev], moshTex[mcur])
            mcur = mn
        }
        first = false
    }

    /**
     * Draws the effect to [targetFbo] (0 = the current EGL surface).
     * [texMat] is the camera stream's own texture matrix; [q] maps output coordinates to the
     * coordinates the history textures use (inverse stream matrix times the output matrix CameraX gives).
     * The output matrix itself is what the shader uses to sample the camera, so crop and rotation match.
     */
    fun draw(srcTex: Int, texMat: FloatArray, q: FloatArray, w: Int, h: Int,
             params: FloatArray, time: Float, targetFbo: Int) {
        val def = effect ?: throw FxException("No effect selected")
        val hist = if (def.hist == "none") 0 else histTex[cur]
        val prev = if (def.hist == "none") 0 else histTex[1 - cur]
        val mosh = if (def.hist == "mosh") moshTex[mcur] else 0
        pass(progEffect, targetFbo, w, h, q, texMat, params, time, srcTex, hist, prev, mosh)
    }

    fun resetHistory() { first = true }

    private fun loc(p: Int, name: String): Int {
        val m = locs.getOrPut(p) { HashMap() }
        return m.getOrPut(name) { GLES20.glGetUniformLocation(p, name) }
    }

    private fun pass(prog: Int, fbo: Int, w: Int, h: Int, invT: FloatArray, texMat: FloatArray,
                     params: FloatArray, time: Float, srcTex: Int, hist: Int, prev: Int, mosh: Int) {
        GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(loc(prog, "uTransMatrix"), 1, false, identity, 0)
        GLES20.glUniformMatrix4fv(loc(prog, "uTexMat"), 1, false, texMat, 0)
        GLES20.glUniformMatrix4fv(loc(prog, "uInvT"), 1, false, invT, 0)
        GLES20.glUniform2f(loc(prog, "uRes"), w.toFloat(), h.toFloat())
        GLES20.glUniform2f(loc(prog, "uHistTexel"), 1f / maxOf(histW, 1), 1f / maxOf(histH, 1))
        GLES20.glUniform1f(loc(prog, "uTime"), time)
        GLES20.glUniform1f(loc(prog, "uFirst"), if (first) 1f else 0f)
        GLES20.glUniform1f(loc(prog, "uIntensity"), params[0])
        GLES20.glUniform1f(loc(prog, "uScale"), params[1])
        GLES20.glUniform1f(loc(prog, "uContrast"), params[2])
        GLES20.glUniform1f(loc(prog, "uBrightness"), params[3])
        GLES20.glUniform1f(loc(prog, "uP0"), params[4])
        GLES20.glUniform1f(loc(prog, "uP1"), params[5])
        GLES20.glUniform1f(loc(prog, "uP2"), params[6])
        GLES20.glUniform1f(loc(prog, "uP3"), params[7])
        bind(prog, "uTex", 0, if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, srcTex)
        bind(prog, "uHist", 1, GLES20.GL_TEXTURE_2D, hist)
        bind(prog, "uPrevH", 2, GLES20.GL_TEXTURE_2D, prev)
        bind(prog, "uMosh", 3, GLES20.GL_TEXTURE_2D, mosh)
        bind(prog, "uGlyph", 4, GLES20.GL_TEXTURE_2D, glyphTex)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw FxException("The GPU reported an error while drawing (0x${Integer.toHexString(err)})")
    }

    private fun bind(prog: Int, name: String, unit: Int, target: Int, tex: Int) {
        val l = loc(prog, name)
        if (l < 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(l, unit)
    }

    private fun ensureHistory(streamW: Int, streamH: Int, def: EffectDef) {
        val sw = maxOf(streamW, 2)
        val sh = maxOf(streamH, 2)
        val (hw, hh) = fit(sw, sh, HIST_LONG)
        val (mw, mh) = fit(sw, sh, MOSH_LONG)
        val needMosh = def.hist == "mosh"
        if (hw == histW && hh == histH && histTex[0] != 0 && (!needMosh || (mw == moshW && mh == moshH && moshTex[0] != 0))) return
        releaseHistory()
        histW = hw; histH = hh
        for (i in 0..1) { val (t, f) = Gl.allocTarget(hw, hh); histTex[i] = t; histFbo[i] = f }
        if (needMosh) {
            moshW = mw; moshH = mh
            for (i in 0..1) { val (t, f) = Gl.allocTarget(mw, mh); moshTex[i] = t; moshFbo[i] = f }
        }
        cur = 0; mcur = 0; first = true
    }

    private fun fit(w: Int, h: Int, long: Int): Pair<Int, Int> =
        if (w >= h) Pair(long, maxOf(2, Math.round(long.toFloat() * h / w)))
        else Pair(maxOf(2, Math.round(long.toFloat() * w / h)), long)

    private fun makeGlyphAtlas(): Int {
        val bmp = Bitmap.createBitmap(GLYPH_CELL_W * 10, GLYPH_CELL_H * GLYPH_SETS.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 44f
            textAlign = Paint.Align.CENTER
        }
        val fm = p.fontMetrics
        GLYPH_SETS.forEachIndexed { r, set ->
            for (i in 0 until 10) {
                val cy = r * GLYPH_CELL_H + GLYPH_CELL_H / 2f + 2f
                c.drawText(set[i].toString(), i * GLYPH_CELL_W + GLYPH_CELL_W / 2f, cy - (fm.ascent + fm.descent) / 2f, p)
            }
        }
        val t = Gl.newTexture()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return t
    }

    private fun releaseHistory() {
        for (i in 0..1) {
            Gl.deleteTexture(histTex[i]); Gl.deleteFbo(histFbo[i]); histTex[i] = 0; histFbo[i] = 0
            Gl.deleteTexture(moshTex[i]); Gl.deleteFbo(moshFbo[i]); moshTex[i] = 0; moshFbo[i] = 0
        }
        histW = 0; histH = 0; moshW = 0; moshH = 0
    }

    private fun releaseAll() {
        releaseHistory()
        if (progEffect != 0) GLES20.glDeleteProgram(progEffect)
        if (progHist != 0) GLES20.glDeleteProgram(progHist)
        if (progMosh != 0) GLES20.glDeleteProgram(progMosh)
        progEffect = 0; progHist = 0; progMosh = 0
        Gl.deleteTexture(glyphTex); glyphTex = 0
        locs.clear()
        cur = 0; mcur = 0; first = true
    }

    /** Frees every GPU object. The GL context must be current. */
    fun release() {
        releaseAll()
        effect = null
    }
}
