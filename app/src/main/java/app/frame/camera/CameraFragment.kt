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

        buildFilterChips()
        setUpControls()
        setUpTouch()
        gridOverlay.mode = savedGrid
        updateTools()
        updateModeUi()
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

        val preview = Preview.Builder().setResolutionSelector(resolutionFor(wide)).build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val viewPort = ViewPort.Builder(Rational(viewfinderW, viewfinderH), Surface.ROTATION_0)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview)

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
        val cam = try {
            cameraProvider.bindToLifecycle(viewLifecycleOwner, selector, group.build())
        } catch (e: Exception) {
            toast("This camera setting is not available on your phone")
            return
        }
        camera = cam
        torchOn = false

        if (video) {
            supportedQualities = try {
                QualitySelector.getSupportedQualities(cam.cameraInfo)
                    .filter { it == Quality.UHD || it == Quality.FHD || it == Quality.HD || it == Quality.SD }
            } catch (e: Exception) {
                emptyList()
            }
            if (supportedQualities.isNotEmpty() && !supportedQualities.contains(savedQuality)) {
                savedQuality = if (supportedQualities.contains(Quality.FHD)) Quality.FHD
                else supportedQualities.first()
                bindCamera()
                return
            }
        }

        zoomLive?.removeObserver(zoomObserver)
        zoomLive = cam.cameraInfo.zoomState
        zoomLive?.observe(viewLifecycleOwner, zoomObserver)

        setUpExposure(cam)
        if (!cam.cameraInfo.hasFlashUnit() && flashMode != ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_OFF
        }
        applyLiveFilter()
        updateTools()
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

        chipFilters.setOnClickListener { toggleSheet(SHEET_FILTERS) }
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
            if (sheetMode == SHEET_FILTERS) toggleSheet(SHEET_FILTERS)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        updateModeUi()
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
        chipFilters.visibility = if (photo) View.VISIBLE else View.INVISIBLE
        shutterCore.setBackgroundResource(if (photo) R.drawable.shutter_core else R.drawable.shutter_rec_dot)
    }

    private fun toggleExposurePanel() {
        val show = exposurePanel.visibility != View.VISIBLE
        if (show && sheetMode != SHEET_NONE) closeSheet()
        exposurePanel.visibility = if (show) View.VISIBLE else View.GONE
        updateTools()
    }

    private fun showVideoOptions() {
        val fpsRanges = try {
            camera?.cameraInfo?.supportedFrameRateRanges ?: emptySet<Range<Int>>()
        } catch (e: Exception) {
            emptySet<Range<Int>>()
        }
        val qualities = if (supportedQualities.isEmpty()) listOf(Quality.FHD) else supportedQualities
        val labels = ArrayList<String>()
        val picks = ArrayList<Pair<Quality, Int>>()
        for (q in qualities) {
            for (fps in listOf(24, 30, 60)) {
                val ok = fpsRanges.isEmpty() && fps == 30 ||
                    fpsRanges.any { it.lower <= fps && fps <= it.upper }
                if (ok) {
                    labels.add(qualityLabel(q) + fps + " fps")
                    picks.add(Pair(q, fps))
                }
            }
        }
        if (picks.isEmpty()) {
            toast("No other video settings are available")
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Video quality")
            .setItems(labels.toTypedArray()) { _, which ->
                savedQuality = picks[which].first
                savedFps = picks[which].second
                bindCamera()
                updateTools()
            }
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
        filterScroll.visibility = if (sheetMode == SHEET_FILTERS) View.VISIBLE else View.GONE
        referencePanel.visibility = if (sheetMode == SHEET_REFERENCE) View.VISIBLE else View.GONE
        setChip(chipFilters, sheetMode == SHEET_FILTERS)
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
                    toast("The video could not be saved")
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
