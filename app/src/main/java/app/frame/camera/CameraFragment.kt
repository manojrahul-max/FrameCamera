package app.frame.camera

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import app.frame.camera.fx.EffectCatalog
import app.frame.camera.fx.EffectDef
import app.frame.camera.fx.EffectPanel
import app.frame.camera.fx.EffectSettings
import app.frame.camera.fx.FxEffect
import app.frame.camera.fx.FxHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(R.layout.fragment_camera) {

    private class Filter(val label: String, val matrix: ColorMatrix?)

    // ---- views
    private lateinit var cameraRoot: FrameLayout
    private lateinit var viewfinder: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var gridOverlay: GridOverlay
    private lateinit var referenceImage: ImageView
    private lateinit var focusRing: View
    private lateinit var touchSurface: View
    private lateinit var btnFlash: ToolButton
    private lateinit var btnTimer: ToolButton
    private lateinit var btnRatio: ToolButton
    private lateinit var btnGrid: ToolButton
    private lateinit var btnExposure: ToolButton
    private lateinit var btnMode: ToolButton
    private lateinit var recBadge: View
    private lateinit var recDot: View
    private lateinit var recTime: TextView
    private lateinit var zoomText: TextView
    private lateinit var hintText: TextView
    private lateinit var countdownText: TextView
    private lateinit var exposurePanel: View
    private lateinit var exposureSeek: SeekBar
    private lateinit var exposureValue: TextView
    private lateinit var toolSheet: View
    private lateinit var filterScroll: View
    private lateinit var filterRow: LinearLayout
    private lateinit var referencePanel: View
    private lateinit var uploadButton: TextView
    private lateinit var refControls: View
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var adjustButton: TextView
    private lateinit var mirrorButton: TextView
    private lateinit var replaceButton: TextView
    private lateinit var removeButton: TextView
    private lateinit var chipFilters: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var lookTabs: View
    private lateinit var tabFilters: TextView
    private lateinit var tabEffects: TextView
    private lateinit var effectHost: FrameLayout
    private lateinit var chipReference: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var pauseButton: ImageView
    private lateinit var flipButton: ImageView
    private lateinit var shutter: View
    private lateinit var shutterCore: View

    // ---- camera
    private lateinit var cameraExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingPaused = false
    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        zoomText.text = String.format(Locale.US, "%.1fx", z.zoomRatio)
        lastZoom = z.zoomRatio
        updateZoomChips(z.zoomRatio)
        checkLens(z.zoomRatio)
    }
    private var dotAnimator: ObjectAnimator? = null

    // ---- state
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var timerSeconds = 0
    private var burst = false
    private var busy = false
    private var burstLeft = 0
    private var burstTaken = 0
    private var countdown: CountDownTimer? = null
    private var sheetMode = SHEET_NONE
    private var viewfinderW = 1
    private var viewfinderH = 1
    private var supportedQualities: List<Quality> = emptyList()
    private var exposureLower = 0
    private var exposureStep = 0f
    private var lastUri: Uri? = null

    // effects and compatibility
    private lateinit var fxSettings: EffectSettings
    private var fxList: List<EffectDef> = emptyList()
    private var activeFx: EffectDef? = null
    private var fxEffect: FxEffect? = null
    private var effectPanel: EffectPanel? = null
    private var compat: CompatMatrix? = null
    private var currentLensId: String? = null
    private var lastZoom = 1f
    private var bindFailures = 0
    private var snapGuard = 0
    private var lookTab = 0
    private val zoomChips = ArrayList<Pair<Float, TextView>>()
    private var lastIsVideo = false

    // reference overlay
    private var hasReference = false
    private var adjustMode = false
    private var refScale = 1f
    private var refMirror = false

    private val aspects = listOf("4:3", "16:9", "1:1", "Full")
    private val gridNames = listOf("Off", "Thirds", "Square", "Golden", "Center")
    private val timers = listOf(0, 3, 5, 10)

    private val filters: List<Filter> by lazy {
        listOf(
            Filter("Original", null),
            Filter("Mono", ColorMatrix().apply { setSaturation(0f) }),
            Filter(
                "Sepia", ColorMatrix(
                    floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            ),
            Filter(
                "Warm", ColorMatrix(
                    floatArrayOf(
                        1.10f, 0f, 0f, 0f, 8f,
                        0f, 1.0f, 0f, 0f, 0f,
                        0f, 0f, 0.88f, 0f, -8f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            ),
            Filter(
                "Cool", ColorMatrix(
                    floatArrayOf(
                        0.90f, 0f, 0f, 0f, -6f,
                        0f, 1.0f, 0f, 0f, 0f,
                        0f, 0f, 1.12f, 0f, 8f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            ),
            Filter("Vivid", ColorMatrix().apply {
                setSaturation(1.45f)
                postConcat(contrastMatrix(1.08f))
            }),
            Filter("Fade", ColorMatrix().apply {
                setSaturation(0.85f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            0.86f, 0f, 0f, 0f, 28f,
                            0f, 0.86f, 0f, 0f, 28f,
                            0f, 0f, 0.86f, 0f, 32f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }),
            Filter("Punch", contrastMatrix(1.35f))
        )
    }

    private fun contrastMatrix(c: Float): ColorMatrix {
        val t = (0.5f - 0.5f * c) * 255f
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else toast(getString(R.string.need_permission), long = true)
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Microphone is off, so videos will have no sound")
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importReference(uri)
        }

    // =================================================================== setup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.previewStreamState.observe(viewLifecycleOwner, Observer { state ->
            if (state == PreviewView.StreamState.STREAMING) applyLiveFilter()
        })

        fxSettings = EffectSettings(requireContext())
        fxList = EffectCatalog.all(requireContext())
        activeFx = EffectCatalog.byId(requireContext(), fxSettings.activeId)
        if (activeFx != null) savedFilter = 0
        FxHub.acquire(requireContext())
        buildEffectPanel()
        lookTab = if (activeFx != null) 1 else 0
        buildFilterChips()
        setUpControls()
        setUpTouch()
        gridOverlay.mode = savedGrid
        updateTools()
        updateModeUi()
        updateLooksUi()
        updateReferenceUi()
        loadSavedReference()

        cameraRoot.doOnLayout {
            layoutViewfinder()
            if (hasCameraPermission()) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindViews(v: View) {
        cameraRoot = v.findViewById(R.id.cameraRoot)
        viewfinder = v.findViewById(R.id.viewfinder)
        previewView = v.findViewById(R.id.previewView)
        gridOverlay = v.findViewById(R.id.gridOverlay)
        referenceImage = v.findViewById(R.id.referenceImage)
        focusRing = v.findViewById(R.id.focusRing)
        touchSurface = v.findViewById(R.id.touchSurface)
        btnFlash = v.findViewById(R.id.btnFlash)
        btnTimer = v.findViewById(R.id.btnTimer)
        btnRatio = v.findViewById(R.id.btnRatio)
        btnGrid = v.findViewById(R.id.btnGrid)
        btnExposure = v.findViewById(R.id.btnExposure)
        btnMode = v.findViewById(R.id.btnMode)
        recBadge = v.findViewById(R.id.recBadge)
        recDot = v.findViewById(R.id.recDot)
        recTime = v.findViewById(R.id.recTime)
        zoomText = v.findViewById(R.id.zoomText)
        hintText = v.findViewById(R.id.hint)
        countdownText = v.findViewById(R.id.countdown)
        exposurePanel = v.findViewById(R.id.exposurePanel)
        exposureSeek = v.findViewById(R.id.exposureSeek)
        exposureValue = v.findViewById(R.id.exposureValue)
        toolSheet = v.findViewById(R.id.toolSheet)
        filterScroll = v.findViewById(R.id.filterScroll)
        filterRow = v.findViewById(R.id.filterRow)
        referencePanel = v.findViewById(R.id.referencePanel)
        uploadButton = v.findViewById(R.id.uploadButton)
        refControls = v.findViewById(R.id.refControls)
        opacitySeek = v.findViewById(R.id.opacitySeek)
        opacityValue = v.findViewById(R.id.opacityValue)
        adjustButton = v.findViewById(R.id.adjustButton)
        mirrorButton = v.findViewById(R.id.mirrorButton)
        replaceButton = v.findViewById(R.id.replaceButton)
        removeButton = v.findViewById(R.id.removeButton)
        chipFilters = v.findViewById(R.id.chipFilters)
        zoomRow = v.findViewById(R.id.zoomRow)
        lookTabs = v.findViewById(R.id.lookTabs)
        tabFilters = v.findViewById(R.id.tabFilters)
        tabEffects = v.findViewById(R.id.tabEffects)
        effectHost = v.findViewById(R.id.effectHost)
        chipReference = v.findViewById(R.id.chipReference)
        modePhoto = v.findViewById(R.id.modePhoto)
        modeVideo = v.findViewById(R.id.modeVideo)
        thumbnail = v.findViewById(R.id.thumbnail)
        pauseButton = v.findViewById(R.id.pauseButton)
        flipButton = v.findViewById(R.id.flipButton)
        shutter = v.findViewById(R.id.shutter)
        shutterCore = v.findViewById(R.id.shutterCore)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(text: String, long: Boolean = false) {
        if (!isAdded) return
        Toast.makeText(
            requireContext(), text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun hasPermission(name: String) =
        ContextCompat.checkSelfPermission(requireContext(), name) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission() = hasPermission(Manifest.permission.CAMERA)

    // ============================================================== viewfinder

    /** Sizes the viewfinder to the chosen aspect ratio. The preview and the saved media use the same crop. */
    private fun layoutViewfinder() {
        val cw = cameraRoot.width
        val ch = cameraRoot.height
        if (cw == 0 || ch == 0) return
        val top: Int
        var w = cw
        var h: Int
        when (savedAspect) {
            0 -> { h = cw * 4 / 3; top = dp(74) }
            1 -> { h = cw * 16 / 9; top = 0 }
            2 -> { h = cw; top = dp(74) }
            else -> { h = ch; top = 0 }
        }
        if (top + h > ch) {
            h = ch - top
            w = when (savedAspect) {
                0 -> h * 3 / 4
                1 -> h * 9 / 16
                2 -> h
                else -> cw
            }
        }
        w = w.coerceIn(1, cw)
        h = h.coerceAtLeast(1)
        val lp = viewfinder.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = h
        lp.topMargin = top
        viewfinder.layoutParams = lp
        viewfinderW = w
        viewfinderH = h
    }

    // ================================================================== camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (!isAdded) return@addListener
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun resolutionFor(wide: Boolean): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()

    private fun bindCamera() {
        val cameraProvider = provider ?: return
        if (!isAdded || view == null) return
        val selector = CameraSelector.Builder().requireLensFacing(savedLens).build()
        if (!cameraProvider.hasCamera(selector)) return

        val video = savedMode == MODE_VIDEO
        val wide = video || savedAspect == 1 || savedAspect == 3
        val def = activeFx

        val preview = Preview.Builder().setResolutionSelector(resolutionFor(wide)).build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val viewPort = ViewPort.Builder(Rational(viewfinderW, viewfinderH), Surface.ROTATION_0)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview)

        // The effect is only created while one is active, so an inactive effect costs nothing.
        var fx: FxEffect? = null
        if (def != null) {
            fx = FxEffect.create(requireContext(), video) { t ->
                handler.post { onFxFailure(t.message) }
            }
            fx.processor.effect = def
            fx.processor.params = fxSettings.snapshot(def)
            group.addEffect(fx)
        }

        if (video) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(savedQuality, FallbackStrategy.lowerQualityOrHigherThan(savedQuality))
                )
                .build()
            val vc = VideoCapture.Builder(recorder)
                .setTargetFrameRate(Range(savedFps, savedFps))
                .build()
            videoCapture = vc
            imageCapture = null
            group.addUseCase(vc)
        } else {
            val capture = ImageCapture.Builder()
                .setResolutionSelector(resolutionFor(wide))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
                .build()
            imageCapture = capture
            videoCapture = null
            group.addUseCase(capture)
        }

        cameraProvider.unbindAll()
        fxEffect?.release()
        fxEffect = null
        val cam = try {
            cameraProvider.bindToLifecycle(viewLifecycleOwner, selector, group.build())
        } catch (e: Exception) {
            fx?.release()
            onBindFailed(def != null)
            return
        }
        fxEffect = fx
        camera = cam
        torchOn = false
        bindFailures = 0

        val cm = CompatMatrix(requireContext(), cam.cameraInfo, savedLens)
        compat = cm
        supportedQualities = cm.qualities
        val zoomNow = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        lastZoom = zoomNow
        currentLensId = cm.lensAt(zoomNow).id

        if (video && cm.qualities.isNotEmpty() && !cm.isSupported(zoomNow, def != null, savedQuality, savedFps)) {
            val n = cm.nearest(zoomNow, def != null, savedQuality, savedFps)
            if (n != null && snapGuard < 2) {
                snapGuard++
                val why = if (def != null) "Effects record up to 1080p 30" else "${cm.lensAt(zoomNow).name} lens"
                savedQuality = n.quality
                savedFps = n.fps
                toast("$why: using ${n.label}")
                bindCamera()
                return
            }
        }
        snapGuard = 0

        zoomLive?.removeObserver(zoomObserver)
        zoomLive = cam.cameraInfo.zoomState
        zoomLive?.observe(viewLifecycleOwner, zoomObserver)

        buildZoomChips()
        setUpExposure(cam)
        if (!cam.cameraInfo.hasFlashUnit() && flashMode != ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_OFF
        }
        applyLiveFilter()
        updateTools()
    }

    /** Recovers from a failed bind: first drop the effect, then fall back to a safe video setting. */
    private fun onBindFailed(hadEffect: Boolean) {
        bindFailures++
        if (hadEffect && bindFailures <= 2) {
            val name = activeFx?.name ?: "The effect"
            disableFx()
            toast("$name could not run with these camera settings, so it was turned off", true)
            bindCamera()
            return
        }
        if (savedMode == MODE_VIDEO && (savedQuality != Quality.FHD || savedFps != 30) && bindFailures <= 3) {
            compat?.block(lastZoom, savedQuality, savedFps)
            savedQuality = Quality.FHD
            savedFps = 30
            toast("That video setting is not available on this camera. Using 1080p 30", true)
            bindCamera()
            return
        }
        toast("This camera setting is not available on your phone")
    }

    // ------------------------------------------------------------ lens and zoom

    private val lensRunnable = Runnable {
        val cm = compat ?: return@Runnable
        if (!isAdded || recording != null || savedMode != MODE_VIDEO) return@Runnable
        if (!cm.isSupported(lastZoom, activeFx != null, savedQuality, savedFps)) {
            val n = cm.nearest(lastZoom, activeFx != null, savedQuality, savedFps) ?: return@Runnable
            savedQuality = n.quality
            savedFps = n.fps
            toast("${cm.lensAt(lastZoom).name} lens: using ${n.label}")
            bindCamera()
            updateTools()
        }
    }

    /** A different physical lens takes over at this zoom. Re-check the chosen video setting against it. */
    private fun checkLens(zoom: Float) {
        val cm = compat ?: return
        val id = cm.lensAt(zoom).id
        if (id == currentLensId) return
        currentLensId = id
        if (savedMode != MODE_VIDEO || recording != null) return
        handler.removeCallbacks(lensRunnable)
        handler.postDelayed(lensRunnable, 350L)
    }

    private fun buildZoomChips() {
        zoomRow.removeAllViews()
        zoomChips.clear()
        val cm = compat
        val presets = cm?.presets() ?: emptyList()
        if (presets.size < 2) {
            zoomRow.visibility = View.GONE
            return
        }
        zoomRow.visibility = View.VISIBLE
        for (p in presets) {
            val chip = TextView(requireContext()).apply {
                text = if (p == p.toInt().toFloat()) "${p.toInt()}x" else String.format(Locale.US, "%.1fx", p)
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(12), 0, dp(12), 0)
                minWidth = dp(40)
                setOnClickListener { camera?.cameraControl?.setZoomRatio(p) }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            zoomChips.add(Pair(p, chip))
        }
        updateZoomChips(lastZoom)
    }

    private fun updateZoomChips(zoom: Float) {
        if (zoomChips.isEmpty() || view == null) return
        val best = zoomChips.minByOrNull { Math.abs(it.first - zoom) }
        for ((p, chip) in zoomChips) {
            val on = best != null && p == best.first && Math.abs(p - zoom) < maxOf(0.12f, p * 0.08f)
            chip.setBackgroundResource(if (on) R.drawable.pill_on else R.drawable.pill_off)
            chip.setTextColor(ContextCompat.getColor(requireContext(), if (on) R.color.cam_ground else R.color.cam_dim))
        }
    }

    private fun setUpExposure(cam: Camera) {
        val state = cam.cameraInfo.exposureState
        if (state.isExposureCompensationSupported) {
            val range = state.exposureCompensationRange
            exposureLower = range.lower
            exposureStep = state.exposureCompensationStep.toFloat()
            exposureSeek.max = range.upper - range.lower
            exposureSeek.progress = state.exposureCompensationIndex - range.lower
            exposureSeek.isEnabled = true
            exposureValue.text = formatEv(state.exposureCompensationIndex)
        } else {
            exposureSeek.max = 1
            exposureSeek.progress = 0
            exposureSeek.isEnabled = false
            exposureValue.text = "n/a"
        }
    }

    private fun formatEv(index: Int): String =
        String.format(Locale.US, "%+.1f", index * exposureStep)

    private fun flipCamera() {
        if (recording != null) return
        val cameraProvider = provider ?: return
        val next = if (savedLens == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(next).build()
        if (!cameraProvider.hasCamera(selector)) {
            toast("No second camera found")
            return
        }
        savedLens = next
        bindCamera()
    }

    // ================================================================ controls

    private fun setUpControls() {
        btnFlash.setOnClickListener { onFlashTapped() }
        btnTimer.setOnClickListener {
            val i = timers.indexOf(savedTimer)
            savedTimer = timers[(i + 1) % timers.size]
            updateTools()
        }
        btnRatio.setOnClickListener {
            if (recording != null) return@setOnClickListener
            savedAspect = (savedAspect + 1) % aspects.size
            layoutViewfinder()
            bindCamera()
            updateTools()
        }
        btnGrid.setOnClickListener {
            savedGrid = (savedGrid + 1) % gridNames.size
            gridOverlay.mode = savedGrid
            updateTools()
        }
        btnExposure.setOnClickListener { toggleExposurePanel() }
        btnMode.setOnClickListener {
            if (savedMode == MODE_PHOTO) {
                burst = !burst
                updateTools()
            } else {
                showVideoOptions()
            }
        }
        updateLooksChip()

        chipFilters.setOnClickListener { toggleSheet(SHEET_FILTERS) }
        tabFilters.setOnClickListener { lookTab = 0; updateLooksUi() }
        tabEffects.setOnClickListener { lookTab = 1; updateLooksUi() }
        chipReference.setOnClickListener { toggleSheet(SHEET_REFERENCE) }
        modePhoto.setOnClickListener { setMode(MODE_PHOTO) }
        modeVideo.setOnClickListener { setMode(MODE_VIDEO) }

        shutter.setOnClickListener { onShutter() }
        flipButton.setOnClickListener { flipCamera() }
        pauseButton.setOnClickListener { togglePause() }
        thumbnail.setOnClickListener { openLastCapture() }
        zoomText.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(maxOf(1f, min))
        }

        exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val index = progress + exposureLower
                camera?.cameraControl?.setExposureCompensationIndex(index)
                exposureValue.text = formatEv(index)
                updateTools()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        uploadButton.setOnClickListener { imagePicker.launch("image/*") }
        replaceButton.setOnClickListener { imagePicker.launch("image/*") }
        removeButton.setOnClickListener { removeReference() }
        adjustButton.setOnClickListener { setAdjustMode(!adjustMode) }
        mirrorButton.setOnClickListener {
            refMirror = !refMirror
            applyReferenceTransform()
            setChip(mirrorButton, refMirror)
        }
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                referenceImage.alpha = progress / 100f
                opacityValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        referenceImage.alpha = opacitySeek.progress / 100f
    }

    private fun onFlashTapped() {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            toast("This camera has no flash")
            return
        }
        if (savedMode == MODE_VIDEO) {
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
        }
        updateTools()
    }

    private fun updateTools() {
        if (view == null) return
        if (savedMode == MODE_VIDEO) {
            btnFlash.setLabel(if (torchOn) "Torch on" else "Torch")
            btnFlash.setActive(torchOn)
        } else {
            btnFlash.setLabel(
                when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> "Auto"
                    ImageCapture.FLASH_MODE_ON -> "On"
                    else -> "Off"
                }
            )
            btnFlash.setActive(flashMode != ImageCapture.FLASH_MODE_OFF)
        }

        btnTimer.setLabel(if (savedTimer == 0) "Off" else "${savedTimer}s")
        btnTimer.setActive(savedTimer != 0)

        btnRatio.setLabel(aspects[savedAspect])
        btnRatio.setActive(false)

        btnGrid.setLabel(gridNames[savedGrid])
        btnGrid.setActive(savedGrid != 0)

        val ev = exposureSeek.progress + exposureLower
        btnExposure.setLabel(if (ev == 0 || !exposureSeek.isEnabled) "EV" else formatEv(ev))
        btnExposure.setActive(exposurePanel.visibility == View.VISIBLE || (ev != 0 && exposureSeek.isEnabled))

        if (savedMode == MODE_PHOTO) {
            btnMode.setIcon(R.drawable.ic_burst)
            btnMode.setLabel(if (burst) "Burst on" else "Burst")
            btnMode.setActive(burst)
        } else {
            btnMode.setIcon(R.drawable.ic_video)
            btnMode.setLabel(qualityLabel(savedQuality) + savedFps)
            btnMode.setActive(false)
        }
    }

    private fun qualityLabel(q: Quality): String = when (q) {
        Quality.UHD -> "4K "
        Quality.FHD -> "1080p "
        Quality.HD -> "720p "
        else -> "480p "
    }

    private fun setMode(mode: Int) {
        if (recording != null || savedMode == mode) return
        savedMode = mode
        if (mode == MODE_VIDEO) {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        updateModeUi()
        updateLooksUi()
        bindCamera()
        updateTools()
    }

    private fun updateModeUi() {
        val ground = ContextCompat.getColor(requireContext(), R.color.cam_ground)
        val dim = ContextCompat.getColor(requireContext(), R.color.cam_dim)
        val photo = savedMode == MODE_PHOTO
        modePhoto.setBackgroundResource(if (photo) R.drawable.pill_on else R.drawable.pill_off)
        modeVideo.setBackgroundResource(if (photo) R.drawable.pill_off else R.drawable.pill_on)
        modePhoto.setTextColor(if (photo) ground else dim)
        modeVideo.setTextColor(if (photo) dim else ground)
        shutterCore.setBackgroundResource(if (photo) R.drawable.shutter_core else R.drawable.shutter_rec_dot)
    }

    private fun toggleExposurePanel() {
        val show = exposurePanel.visibility != View.VISIBLE
        if (show && sheetMode != SHEET_NONE) closeSheet()
        exposurePanel.visibility = if (show) View.VISIBLE else View.GONE
        updateTools()
    }

    private fun showVideoOptions() {
        if (recording != null) {
            toast("Stop recording to change video quality")
            return
        }
        val cm = compat
        if (cm == null) {
            toast("The camera is still starting")
            return
        }
        val lens = cm.lensAt(lastZoom)
        val options = cm.options(lastZoom, activeFx != null)
        if (options.isEmpty()) {
            toast("No video settings are available for this lens")
            return
        }
        val labels = options.map {
            it.label + " fps"
        }.toTypedArray()
        val checked = options.indexOfFirst { it.quality == savedQuality && it.fps == savedFps }
        val note = if (activeFx != null) "\nEffects record up to 1080p 30" else ""
        AlertDialog.Builder(requireContext())
            .setTitle("Video, ${lens.name} lens, ${String.format(Locale.US, "%.1f", lastZoom)}x$note")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                savedQuality = options[which].quality
                savedFps = options[which].fps
                dialog.dismiss()
                bindCamera()
                updateTools()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----- tool sheet (filters and reference)

    private fun toggleSheet(which: Int) {
        sheetMode = if (sheetMode == which) SHEET_NONE else which
        if (sheetMode != SHEET_NONE) {
            exposurePanel.visibility = View.GONE
            updateTools()
        }
        toolSheet.visibility = if (sheetMode == SHEET_NONE) View.GONE else View.VISIBLE
        updateLooksUi()
        referencePanel.visibility = if (sheetMode == SHEET_REFERENCE) View.VISIBLE else View.GONE
        updateLooksChip()
        setChip(chipReference, sheetMode == SHEET_REFERENCE)
    }

    private fun closeSheet() {
        if (sheetMode != SHEET_NONE) toggleSheet(sheetMode)
    }

    private fun setChip(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.chip_cam_on else R.drawable.chip_cam_off)
        tv.setTextColor(
            ContextCompat.getColor(requireContext(), if (on) R.color.cam_ground else R.color.cam_ink)
        )
    }

    // ================================================================== filters

    private fun buildFilterChips() {
        filters.forEachIndexed { index, filter ->
            val chip = TextView(requireContext()).apply {
                text = filter.label
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { selectFilter(index) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
            lp.marginEnd = dp(8)
            filterRow.addView(chip, lp)
        }
        selectFilter(savedFilter)
    }

    private fun selectFilter(index: Int) {
        if (index != 0 && activeFx != null) {
            val name = activeFx?.name
            applyFx(null)
            effectPanel?.setActive(null)
            toast("$name turned off. Filters and effects are not combined")
        }
        savedFilter = index
        for (i in 0 until filterRow.childCount) {
            setChip(filterRow.getChildAt(i) as TextView, i == index)
        }
        applyLiveFilter()
    }

    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findTextureView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /** Tints the live preview for photos. Videos are recorded without a filter. */
    private fun applyLiveFilter() {
        val tv = findTextureView(previewView) ?: return
        val matrix = if (savedMode == MODE_PHOTO) filters[savedFilter].matrix else null
        if (matrix == null) {
            tv.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    // ================================================================== effects

    private fun buildEffectPanel() {
        val panel = EffectPanel(requireContext())
        panel.bind(fxList, fxSettings, 190)
        panel.listener = object : EffectPanel.Listener {
            override fun onActiveChanged(def: EffectDef?) { requestFx(def) }
            override fun onParamsChanged(def: EffectDef) {
                fxEffect?.processor?.params = fxSettings.snapshot(def)
            }
        }
        panel.setActive(activeFx?.id)
        effectHost.removeAllViews()
        effectHost.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        effectPanel = panel
    }

    private fun updateLooksUi() {
        if (view == null) return
        val looks = sheetMode == SHEET_FILTERS
        val photo = savedMode == MODE_PHOTO
        if (!photo) lookTab = 1
        lookTabs.visibility = if (looks && photo) View.VISIBLE else View.GONE
        filterScroll.visibility = if (looks && lookTab == 0) View.VISIBLE else View.GONE
        effectHost.visibility = if (looks && lookTab == 1) View.VISIBLE else View.GONE
        setChip(tabFilters, lookTab == 0)
        setChip(tabEffects, lookTab == 1)
    }

    private fun updateLooksChip() {
        if (view == null) return
        chipFilters.text = activeFx?.name ?: "Looks"
        setChip(chipFilters, sheetMode == SHEET_FILTERS || activeFx != null)
    }

    private fun markUnavailable() {
        val m = HashMap<String, String>()
        fxList.forEach { if (FxHub.isProbeFailed(it.id)) m[it.id] = "not supported on this GPU" }
        effectPanel?.setUnavailable(m)
    }

    /** The user picked an effect (or none). Check it compiles on this GPU before touching the camera. */
    private fun requestFx(def: EffectDef?) {
        if (def == null) { applyFx(null); return }
        if (recording != null && activeFx == null) {
            toast("Stop recording to turn effects on")
            effectPanel?.setActive(null)
            return
        }
        FxHub.probe(def) { err ->
            if (!isAdded || view == null) return@probe
            if (err != null) {
                toast("${def.name} is not supported on this phone: $err", true)
                markUnavailable()
                effectPanel?.setActive(activeFx?.id)
            } else {
                applyFx(def)
            }
        }
    }

    private fun applyFx(def: EffectDef?) {
        val old = activeFx
        if (old?.id == def?.id) return
        val liveSwitch = old != null && def != null && fxEffect != null
        if (recording != null && !liveSwitch) {
            toast("Stop recording to turn effects on or off")
            effectPanel?.setActive(old?.id)
            return
        }
        activeFx = def
        fxSettings.activeId = def?.id
        if (def != null && savedFilter != 0) selectFilter(0)
        updateLooksChip()
        if (liveSwitch && def != null) {
            fxEffect?.processor?.setEffect(def, fxSettings.snapshot(def)) { msg ->
                handler.post { onFxFailure(msg) }
            }
            return
        }
        if (savedMode == MODE_VIDEO && def != null) {
            val cm = compat
            if (cm != null && !cm.isSupported(lastZoom, true, savedQuality, savedFps)) {
                val n = cm.nearest(lastZoom, true, savedQuality, savedFps)
                if (n != null) {
                    savedQuality = n.quality
                    savedFps = n.fps
                    toast("Effects record up to 1080p 30: using ${n.label}")
                }
            }
        }
        bindCamera()
        updateTools()
    }

    private fun disableFx() {
        activeFx = null
        fxSettings.activeId = null
        effectPanel?.setActive(null)
        updateLooksChip()
    }

    /** The GPU path failed while running. Switch the effect off and put the plain camera back. */
    private fun onFxFailure(msg: String?) {
        if (!isAdded || view == null) return
        val def = activeFx ?: return
        val wasRecording = recording != null
        if (wasRecording) stopRecording()
        disableFx()
        val reason = if (msg.isNullOrBlank()) "" else ": $msg"
        toast("${def.name} stopped$reason. The camera is back to normal", true)
        handler.postDelayed({
            if (isAdded && view != null) {
                bindCamera()
                updateTools()
            }
        }, if (wasRecording) 800L else 0L)
    }

    // ========================================================= reference image

    private fun importReference(uri: Uri) {
        cameraExecutor.execute {
            val bitmap = decodeReference(uri)
            handler.post {
                if (!isAdded) return@post
                if (bitmap == null) {
                    toast("Could not open that image")
                } else {
                    try {
                        requireContext().openFileOutput(REFERENCE_FILE, android.content.Context.MODE_PRIVATE).use {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                    } catch (e: Exception) {
                        // Not being able to remember the image is not fatal.
                    }
                    resetReferenceTransform()
                    showReference(bitmap)
                }
            }
        }
    }

    private fun decodeReference(uri: Uri): Bitmap? = try {
        val source = ImageDecoder.createSource(requireContext().contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 2048) {
                val s = 2048f / longest
                decoder.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun loadSavedReference() {
        val file = File(requireContext().filesDir, REFERENCE_FILE)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) showReference(bitmap)
    }

    private fun showReference(bitmap: Bitmap) {
        hasReference = true
        referenceImage.setImageBitmap(bitmap)
        referenceImage.visibility = View.VISIBLE
        referenceImage.alpha = opacitySeek.progress / 100f
        applyReferenceTransform()
        updateReferenceUi()
        if (sheetMode != SHEET_REFERENCE) toggleSheet(SHEET_REFERENCE)
    }

    private fun removeReference() {
        hasReference = false
        setAdjustMode(false)
        referenceImage.setImageDrawable(null)
        referenceImage.visibility = View.GONE
        File(requireContext().filesDir, REFERENCE_FILE).delete()
        resetReferenceTransform()
        updateReferenceUi()
    }

    private fun updateReferenceUi() {
        uploadButton.visibility = if (hasReference) View.GONE else View.VISIBLE
        refControls.visibility = if (hasReference) View.VISIBLE else View.GONE
        setChip(adjustButton, adjustMode)
        setChip(mirrorButton, refMirror)
        setChip(replaceButton, false)
        setChip(removeButton, false)
    }

    private fun setAdjustMode(on: Boolean) {
        adjustMode = on && hasReference
        hintText.visibility = if (adjustMode) View.VISIBLE else View.GONE
        setChip(adjustButton, adjustMode)
    }

    private fun resetReferenceTransform() {
        refScale = 1f
        refMirror = false
        referenceImage.translationX = 0f
        referenceImage.translationY = 0f
        applyReferenceTransform()
        setChip(mirrorButton, false)
    }

    private fun applyReferenceTransform() {
        referenceImage.scaleX = refScale * (if (refMirror) -1f else 1f)
        referenceImage.scaleY = refScale
    }

    // ============================================================== gestures

    private fun setUpTouch() {
        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    if (adjustMode) {
                        refScale = (refScale * d.scaleFactor).coerceIn(0.2f, 6f)
                        applyReferenceTransform()
                    } else {
                        val cam = camera ?: return true
                        val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        cam.cameraControl.setZoomRatio(current * d.scaleFactor)
                    }
                    return true
                }
            })
        val gestures = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!adjustMode) focusAt(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (adjustMode) {
                    refScale = 1f
                    referenceImage.translationX = 0f
                    referenceImage.translationY = 0f
                    applyReferenceTransform()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (adjustMode && !scaleDetector.isInProgress) {
                    referenceImage.translationX -= dx
                    referenceImage.translationY -= dy
                }
                return true
            }
        })
        touchSurface.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            gestures.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.animate().alpha(0f).setStartDelay(700).setDuration(250).start()
    }

    // ============================================================== shutter

    private fun onShutter() {
        if (countdown != null) {
            cancelCountdown()
            return
        }
        if (savedMode == MODE_VIDEO) {
            if (recording != null) {
                stopRecording()
            } else if (savedTimer > 0) {
                startCountdown { startRecording() }
            } else {
                startRecording()
            }
        } else {
            if (busy) return
            if (savedTimer > 0) startCountdown { startPhoto() } else startPhoto()
        }
    }

    private fun startCountdown(onDone: () -> Unit) {
        countdownText.visibility = View.VISIBLE
        countdownText.text = savedTimer.toString()
        countdown = object : CountDownTimer(savedTimer * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                countdownText.text = ((msLeft + 999L) / 1000L).toString()
            }

            override fun onFinish() {
                countdown = null
                countdownText.visibility = View.GONE
                onDone()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
        countdownText.visibility = View.GONE
    }

    // ================================================================= photos

    private fun photoValues(pending: Boolean): ContentValues {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Frame_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Frame")
            if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    private fun startPhoto() {
        burstLeft = if (burst) BURST_COUNT else 1
        burstTaken = 0
        captureNext()
    }

    private fun captureNext() {
        if (burstLeft <= 0 || !isAdded) return
        burstLeft--
        takePhoto()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        viewfinder.animate().alpha(0.25f).setDuration(60).withEndAction {
            viewfinder.animate().alpha(1f).setDuration(140).start()
        }.start()

        val fx = fxEffect
        if (activeFx != null && fx != null) {
            takeEffectPhoto(fx)
            return
        }

        val matrix = filters[savedFilter].matrix
        if (matrix == null) {
            val options = ImageCapture.OutputFileOptions.Builder(
                requireContext().contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                photoValues(false)
            ).build()
            capture.takePicture(options, ContextCompat.getMainExecutor(requireContext()),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        finishPhoto(result.savedUri)
                    }

                    override fun onError(exc: ImageCaptureException) {
                        finishPhoto(null)
                    }
                })
        } else {
            capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val uri = try {
                        saveFiltered(image, matrix)
                    } catch (e: Throwable) {
                        null
                    } finally {
                        image.close()
                    }
                    handler.post { finishPhoto(uri) }
                }

                override fun onError(exception: ImageCaptureException) {
                    handler.post { finishPhoto(null) }
                }
            })
        }
    }

    /** Saves what the viewfinder shows: the processed frame at the preview's size and crop. */
    private fun takeEffectPhoto(fx: FxEffect) {
        if (flashMode != ImageCapture.FLASH_MODE_OFF) toast("Flash is not used with effects")
        fx.processor.snapshot { bmp, err ->
            if (bmp == null) {
                handler.post { toast(err ?: "Could not capture the effect"); finishPhoto(null) }
                return@snapshot
            }
            try {
                cameraExecutor.execute {
                    val uri = try { saveBitmap(bmp) } catch (e: Throwable) { null } finally { bmp.recycle() }
                    handler.post { finishPhoto(uri) }
                }
            } catch (e: Exception) {
                bmp.recycle()
                handler.post { finishPhoto(null) }
            }
        }
    }

    private fun saveBitmap(output: Bitmap): Uri? {
        val context = context ?: return null
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(true)) ?: return null
        resolver.openOutputStream(uri)?.use { output.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    private fun saveFiltered(image: ImageProxy, matrix: ColorMatrix): Uri? {
        val context = context ?: return null
        val rotation = image.imageInfo.rotationDegrees
        var source = image.toBitmap()

        val crop = image.cropRect
        val needsCrop = crop.width() > 0 && crop.height() > 0 &&
            (crop.width() < source.width || crop.height() < source.height) &&
            crop.right <= source.width && crop.bottom <= source.height
        if (needsCrop) {
            val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            if (cropped !== source) source.recycle()
            source = cropped
        }

        val rotateBy = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotateBy, true)
        if (rotated !== source) source.recycle()

        val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(output).drawBitmap(rotated, 0f, 0f, paint)
        rotated.recycle()

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(true))
        if (uri == null) {
            output.recycle()
            return null
        }
        resolver.openOutputStream(uri)?.use { output.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        output.recycle()
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    private fun finishPhoto(uri: Uri?) {
        busy = false
        if (!isAdded) return
        if (uri != null) {
            burstTaken++
            lastUri = uri
            lastIsVideo = false
            showThumbnail(uri)
        }
        if (burstLeft > 0) {
            handler.postDelayed({ captureNext() }, 150L)
            return
        }
        if (burstTaken == 0) toast("Could not save the photo")
        else toast(if (burstTaken > 1) "Saved $burstTaken photos" else "Saved to Pictures/Frame")
    }

    // ================================================================== video

    private fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null) return
        val context = requireContext()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Frame_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Frame")
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(context, options)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        recordingPaused = false
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            onVideoEvent(event)
        }
        setRecordingUi(true)
    }

    private fun onVideoEvent(event: VideoRecordEvent) {
        if (!isAdded) return
        when (event) {
            is VideoRecordEvent.Status -> {
                val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                recTime.text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                setRecordingUi(false)
                if (event.hasError()) {
                    if (event.error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR
                    ) {
                        compat?.block(lastZoom, savedQuality, savedFps)
                        toast("This video setting failed on your phone and has been hidden. Pick another one", true)
                    } else {
                        toast("The video could not be saved")
                    }
                } else {
                    val uri = event.outputResults.outputUri
                    lastUri = uri
                    lastIsVideo = true
                    showThumbnail(uri)
                    toast("Saved to Movies/Frame")
                }
            }
            else -> {}
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun togglePause() {
        val r = recording ?: return
        if (recordingPaused) {
            r.resume()
            recordingPaused = false
            pauseButton.setImageResource(R.drawable.ic_pause)
            dotAnimator?.start()
        } else {
            r.pause()
            recordingPaused = true
            pauseButton.setImageResource(R.drawable.ic_play)
            dotAnimator?.cancel()
            recDot.alpha = 1f
        }
    }

    private fun setRecordingUi(active: Boolean) {
        recBadge.visibility = if (active) View.VISIBLE else View.GONE
        pauseButton.visibility = if (active) View.VISIBLE else View.GONE
        thumbnail.visibility = if (active) View.GONE else View.VISIBLE
        flipButton.alpha = if (active) 0.35f else 1f
        shutterCore.setBackgroundResource(
            if (active) R.drawable.shutter_rec_square else R.drawable.shutter_rec_dot
        )
        pauseButton.setImageResource(R.drawable.ic_pause)
        if (active) {
            recTime.text = "00:00"
            dotAnimator?.cancel()
            dotAnimator = ObjectAnimator.ofFloat(recDot, "alpha", 1f, 0.2f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            dotAnimator?.cancel()
            recDot.alpha = 1f
        }
    }

    // ================================================================ thumbnail

    private fun showThumbnail(uri: Uri) {
        val resolver = context?.contentResolver ?: return
        cameraExecutor.execute {
            val bitmap = try {
                resolver.loadThumbnail(uri, Size(200, 200), null)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) handler.post { if (isAdded) thumbnail.setImageBitmap(bitmap) }
        }
    }

    private fun openLastCapture() {
        val uri = lastUri ?: return
        startActivity(ViewerActivity.intent(requireContext(), uri, lastIsVideo))
    }

    // ================================================================ lifecycle

    override fun onPause() {
        super.onPause()
        if (recording != null) stopRecording()
        cancelCountdown()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dotAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        zoomLive?.removeObserver(zoomObserver)
        zoomLive = null
        recording = null
        camera = null
        provider?.unbindAll()
        fxEffect?.release()
        fxEffect = null
        effectPanel = null
        FxHub.release()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REFERENCE_FILE = "reference.jpg"
        private const val MODE_PHOTO = 0
        private const val MODE_VIDEO = 1
        private const val SHEET_NONE = 0
        private const val SHEET_FILTERS = 1
        private const val SHEET_REFERENCE = 2
        private const val BURST_COUNT = 5

        // Remembered while the app is open, including when you visit the Gallery and come back.
        private var savedMode = MODE_PHOTO
        private var savedAspect = 0
        private var savedGrid = 0
        private var savedTimer = 0
        private var savedFilter = 0
        private var savedLens = CameraSelector.LENS_FACING_BACK
        private var savedQuality: Quality = Quality.FHD
        private var savedFps = 30
    }
}
