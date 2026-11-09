package app.frame.camera.fx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/**
 * Runs the selected effect on the live camera stream. CameraX hands this class the camera frames
 * and the surfaces for the preview and the recorder; every frame is drawn once per surface, so the
 * viewfinder and the recorded video always show the same result.
 */
class FxProcessor(
    private val ctx: Context,
    private val onFatal: (Throwable) -> Unit
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("frame-fx-gl").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var releaseRequested = false
    @Volatile private var released = false

    /** Keeps running until CameraX has returned every surface, so unbinding can never stall. */
    val executor = Executor { r -> handler.post(r) }

    private var egl: EglCore? = null
    private var renderer: EffectRenderer? = null

    private var inTex = 0
    private var inST: SurfaceTexture? = null
    private var inSurface: Surface? = null
    private var inW = 0
    private var inH = 0

    private class Out(val surface: EGLSurface, val size: Size, val targets: Int) {
        /** Matrix CameraX supplies for this output: output coordinates to camera texture coordinates. */
        val out = FloatArray(16)
        /** Output coordinates to history coordinates: inverse stream matrix times [out]. */
        val q = FloatArray(16)
    }
    private val outs = LinkedHashMap<SurfaceOutput, Out>()

    private val texMat = FloatArray(16)
    private val texInv = FloatArray(16)
    private val startNs = System.nanoTime()
    private var failed = false

    @Volatile var effect: EffectDef? = null
    @Volatile var params: FloatArray = FloatArray(8)

    private var snapshotCb: ((Bitmap?, String?) -> Unit)? = null

    /** Applies a new effect without rebinding the camera. Errors go to [onFail]. */
    fun setEffect(def: EffectDef?, p: FloatArray, onFail: (String) -> Unit) {
        params = p
        effect = def
        handler.post {
            try {
                if (egl != null) {
                    egl!!.makeCurrentOffscreen()
                    renderer?.setEffect(def)
                }
            } catch (t: Throwable) {
                Log.e("FrameFx", "effect switch failed", t)
                effect = null
                onFail(t.message ?: "The effect could not start")
            }
        }
    }

    private fun ensureGl() {
        if (egl != null) return
        val e = EglCore()
        e.makeCurrentOffscreen()
        egl = e
        renderer = EffectRenderer(ctx, true).also { it.setEffect(effect) }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released || releaseRequested) { request.willNotProvideSurface(); return }
        try {
            ensureGl()
            egl!!.makeCurrentOffscreen()
            val tex = Gl.newExternalTexture()
            val st = SurfaceTexture(tex)
            val res = request.resolution
            st.setDefaultBufferSize(res.width, res.height)
            val surface = Surface(st)
            inTex = tex; inST = st; inSurface = surface; inW = res.width; inH = res.height
            request.provideSurface(surface, executor, Consumer { _ ->
                if (inSurface === surface) {
                    st.setOnFrameAvailableListener(null)
                    inST = null; inSurface = null
                    try { egl?.makeCurrentOffscreen(); Gl.deleteTexture(tex) } catch (_: Throwable) {}
                }
                st.release()
                surface.release()
                maybeFinish()
            })
            st.setOnFrameAvailableListener(this, handler)
        } catch (t: Throwable) {
            request.willNotProvideSurface()
            fail(t)
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released || releaseRequested) { output.close(); return }
        try {
            ensureGl()
            val surface = output.getSurface(executor, Consumer { _ ->
                val o = outs.remove(output)
                if (o != null) try { egl?.destroySurface(o.surface) } catch (_: Throwable) {}
                output.close()
                maybeFinish()
            })
            val es = egl!!.createWindowSurface(surface)
            outs[output] = Out(es, output.size, output.targets)
        } catch (t: Throwable) {
            output.close()
            fail(t)
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released || releaseRequested || failed || st !== inST) return
        val e = egl ?: return
        val r = renderer ?: return
        try {
            e.makeCurrentOffscreen()
            st.updateTexImage()
            st.getTransformMatrix(texMat)
            val def = effect
            if (def == null || r.effect?.id != def.id) {
                // Effect was cleared or is still switching: pass nothing through, drop the frame.
                return
            }
            Matrix.invertM(texInv, 0, texMat, 0)
            val p = params
            val t = ((System.nanoTime() - startNs) / 1e9f) % 3600f
            r.prepareFrame(inTex, texMat, inW, inH, p, t)
            var snapDone = false
            for ((output, o) in outs) {
                e.makeCurrent(o.surface)
                output.updateTransformMatrix(o.out, texMat)
                Matrix.multiplyMM(o.q, 0, texInv, 0, o.out, 0)
                r.draw(inTex, texMat, o.q, o.size.width, o.size.height, p, t, 0)
                e.setPresentationTime(o.surface, st.timestamp)
                e.swap(o.surface)
                if (!snapDone && snapshotCb != null && (o.targets and 1) != 0) {
                    snapDone = true
                    runSnapshot(e, r, o, p, t)
                }
            }
        } catch (t: Throwable) {
            Log.e("FrameFx", "frame failed", t)
            fail(t)
        }
    }

    /** Captures the processed preview frame at the preview surface's own crop and size. */
    fun snapshot(cb: (Bitmap?, String?) -> Unit) {
        if (released || failed) { cb(null, "Effects are not running"); return }
        handler.post {
            if (snapshotCb != null) { cb(null, "A capture is already in progress"); return@post }
            snapshotCb = cb
            // If frames are not arriving (paused camera), do not wait forever.
            handler.postDelayed({
                if (snapshotCb === cb) { snapshotCb = null; cb(null, "No camera frame arrived for the capture") }
            }, 2500)
        }
    }

    private fun runSnapshot(e: EglCore, r: EffectRenderer, o: Out, p: FloatArray, t: Float) {
        val cb = snapshotCb ?: return
        snapshotCb = null
        var tex = 0; var fbo = 0
        try {
            val w = o.size.width
            val h = o.size.height
            val pair = Gl.allocTarget(w, h)
            tex = pair.first; fbo = pair.second
            r.draw(inTex, texMat, o.q, w, h, p, t, fbo)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            raw.copyPixelsFromBuffer(buf)
            val m = android.graphics.Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
            val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
            if (flipped !== raw) raw.recycle()
            cb(flipped, null)
        } catch (t: Throwable) {
            Log.e("FrameFx", "snapshot failed", t)
            cb(null, t.message ?: "Capture failed")
        } finally {
            Gl.deleteFbo(fbo); Gl.deleteTexture(tex)
            // The output surface must be current again for the next frame.
        }
    }

    private fun fail(t: Throwable) {
        if (failed) return
        failed = true
        Log.e("FrameFx", "effects disabled after error", t)
        onFatal(t)
    }

    /**
     * Asks the processor to shut down. It frees everything once CameraX has handed back its surfaces
     * (or after a short timeout), so it is safe to call right after unbinding.
     */
    fun release() {
        if (releaseRequested) return
        releaseRequested = true
        handler.post { maybeFinish() }
        handler.postDelayed({ finish() }, 3000)
    }

    private fun maybeFinish() {
        if (releaseRequested && !released && inSurface == null && outs.isEmpty()) finish()
    }

    private fun finish() {
        if (released) return
        released = true
        try {
            egl?.makeCurrentOffscreen()
            for ((_, o) in outs) try { egl?.destroySurface(o.surface) } catch (_: Throwable) {}
            outs.clear()
            inST?.setOnFrameAvailableListener(null)
            renderer?.release()
            Gl.deleteTexture(inTex)
            egl?.release()
        } catch (_: Throwable) {
        } finally {
            renderer = null; egl = null
            thread.quitSafely()
        }
    }
}

/** The CameraX effect object that binds [FxProcessor] to the preview and, in video mode, the recorder. */
class FxEffect private constructor(
    val processor: FxProcessor,
    targets: Int,
    onError: Consumer<Throwable>
) : CameraEffect(targets, processor.executor, processor, onError) {

    companion object {
        /** [video] true: preview and recorder get the effect. false: preview only (photos are snapped from it). */
        fun create(ctx: Context, video: Boolean, onError: (Throwable) -> Unit): FxEffect {
            val p = FxProcessor(ctx, onError)
            val targets = if (video) (CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE) else CameraEffect.PREVIEW
            return FxEffect(p, targets, Consumer { onError(it) })
        }
    }

    fun release() = processor.release()
}
