package app.frame.camera.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.frame.camera.fx.EffectDef
import app.frame.camera.fx.FxEffect
import app.frame.camera.fx.FxHub
import java.util.concurrent.Executors

/**
 * Camera for the creative workspaces. It binds a preview and a still capture to a viewport that has
 * the aspect of the canvas being worked on, so the live view, the captured picture and the saved
 * result frame the scene identically. The look (a built in effect or a custom layered effect) runs
 * on the GPU in the preview path and the same shader is used for the capture.
 */
class StudioCamera(private val ctx: Context, private val owner: LifecycleOwner, private val previewView: PreviewView) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var fx: FxEffect? = null

    var lens = CameraSelector.LENS_FACING_BACK
        private set
    private var aspect = 0.75f
    private var def: EffectDef? = null
    private var params = FloatArray(8)
    private var custom: CustomEffect? = null
    private var bound = false
    private var failures = 0

    var onMessage: (String) -> Unit = {}
    var onReady: () -> Unit = {}

    init {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    fun start(viewAspect: Float) {
        aspect = viewAspect
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(ctx))
    }

    fun setAspect(a: Float) { if (Math.abs(a - aspect) > 0.001f) { aspect = a; if (provider != null) bind() } }

    fun flip() {
        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bind()
    }

    fun hasFront(): Boolean = provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true

    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    fun zoom(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    /** Sets a built in effect (or none). Rebinds only when switching between "no effect" and "an effect". */
    fun setLook(d: EffectDef?, p: FloatArray) {
        custom = null
        val needRebind = (d != null) != (fx != null)
        def = d; params = p
        if (needRebind) { if (provider != null) bind(); return }
        fx?.processor?.custom = null
        fx?.processor?.setEffect(d, p) { m -> main.post { onMessage(m) } }
    }

    /** Sets a custom layered effect. Call again after any edit; the camera updates without rebinding. */
    fun setCustom(e: CustomEffect?, bypass: Boolean = false) {
        custom = e
        if (e == null) { setLookInternal(null, FloatArray(8)); return }
        val needRebind = fx == null
        def = CustomEffect.DEF; params = CustomEffect.params(e)
        if (bypass) params[0] = 0f
        if (needRebind) { if (provider != null) bind(); return }
        val proc = fx!!.processor
        proc.custom = e.payload(aspect)
        proc.setEffect(CustomEffect.DEF, params) { m -> main.post { onMessage(m) } }
    }

    private fun setLookInternal(d: EffectDef?, p: FloatArray) {
        val needRebind = (d != null) != (fx != null)
        def = d; params = p
        if (needRebind) { if (provider != null) bind(); return }
        fx?.processor?.custom = null
        fx?.processor?.setEffect(d, p) { m -> main.post { onMessage(m) } }
    }

    private fun res() = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()

    fun bind() {
        val p = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lens).build()
        if (!p.hasCamera(selector)) { onMessage("This camera is not available"); return }
        val preview = Preview.Builder().setResolutionSelector(res()).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val vp = ViewPort.Builder(Rational(Math.round(aspect * 1000f), 1000), Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build()
        val group = UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview)
        val capture = ImageCapture.Builder().setResolutionSelector(res()).setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        group.addUseCase(capture)
        var newFx: FxEffect? = null
        val d = def
        if (d != null) {
            newFx = FxEffect.create(ctx, false) { t -> main.post { onFxFailure(t.message) } }
            newFx.processor.effect = d
            newFx.processor.params = params
            newFx.processor.custom = custom?.payload(aspect)
            group.addEffect(newFx)
        }
        p.unbindAll()
        fx?.release(); fx = null
        try {
            camera = p.bindToLifecycle(owner, selector, group.build())
        } catch (e: Exception) {
            newFx?.release()
            if (d != null && failures < 1) {
                failures++
                def = null; custom = null
                onMessage("This look could not start on the camera, so it was switched off")
                bind()
            } else onMessage("The camera could not start")
            return
        }
        failures = 0
        fx = newFx
        imageCapture = capture
        bound = true
        onReady()
    }

    private fun onFxFailure(msg: String?) {
        onMessage(msg ?: "The effect stopped, so it was switched off")
        def = null; custom = null
        if (provider != null) bind()
    }

    /** True when a look is running on the live preview. */
    fun hasLook(): Boolean = fx != null

    /**
     * Takes a picture that matches the viewfinder. Without a look it is the full resolution still.
     * With a custom look on the back camera it is the full resolution still run through the same shader.
     * Otherwise it is the processed preview frame.
     */
    fun capture(cb: (Bitmap?, String?) -> Unit) {
        val ic = imageCapture ?: run { cb(null, "The camera is not ready"); return }
        val f = fx
        val ce = custom
        val front = lens == CameraSelector.LENS_FACING_FRONT
        if (f != null && (ce == null || front)) {
            f.processor.snapshot { b, e -> main.post { cb(b, e) } }
            return
        }
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try { upright(image, front) } catch (t: Throwable) { null } finally { image.close() }
                if (bmp == null) { main.post { cb(null, "The picture could not be read") }; return }
                if (ce == null) { main.post { cb(bmp, null) }; return }
                main.post {
                    FxHub.renderCustom(ce, bmp, 3072) { out, err ->
                        if (out != null) { bmp.recycle(); cb(out, null) }
                        else {
                            bmp.recycle()
                            // Fall back to what the preview shows so the picture still has the effect.
                            val ff = fx
                            if (ff != null) ff.processor.snapshot { b, e -> main.post { cb(b, e ?: err) } } else cb(null, err)
                        }
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) { main.post { cb(null, "Capture failed: ${exception.message}") } }
        })
    }

    private fun upright(image: ImageProxy, mirror: Boolean): Bitmap {
        var src = image.toBitmap()
        val crop = image.cropRect
        if (crop.width() > 0 && crop.height() > 0 && (crop.width() < src.width || crop.height() < src.height) &&
            crop.right <= src.width && crop.bottom <= src.height) {
            val c = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height())
            if (c !== src) src.recycle()
            src = c
        }
        val m = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    fun stop() {
        provider?.unbindAll()
        fx?.release(); fx = null
        camera = null; imageCapture = null; bound = false
    }
}
