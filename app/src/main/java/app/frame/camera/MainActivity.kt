package app.frame.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private class Filter(val label: String, val matrix: ColorMatrix?)

    // Views
    private lateinit var root: FrameLayout
    private lateinit var viewfinder: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var gridOverlay: GridOverlay
    private lateinit var referenceImage: ImageView
    private lateinit var focusRing: View
    private lateinit var countdownText: TextView
    private lateinit var hintText: TextView
    private lateinit var zoomText: TextView
    private lateinit var touchSurface: View
    private lateinit var flashButton: TextView
    private lateinit var timerButton: TextView
    private lateinit var gridButton: TextView
    private lateinit var flipButton: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var tabFilter: TextView
    private lateinit var tabReference: TextView
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

    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        zoomText.text = String.format(Locale.US, "%.1fx", z.zoomRatio)
    }

    // State
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var gridMode = 0
    private var filterIndex = 0
    private var busy = false
    private var countdown: CountDownTimer? = null
    private var lastUri: Uri? = null

    // Reference overlay state
    private var hasReference = false
    private var adjustMode = false
    private var refScale = 1f
    private var refMirror = false

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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importReference(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.previewStreamState.observe(this, Observer { state ->
            if (state == PreviewView.StreamState.STREAMING) applyLiveFilter()
        })

        sizeViewfinder()
        buildFilterChips()
        setUpControls()
        setUpTouch()
        showTab(0)
        updateTopLabels()
        loadSavedReference()

        if (hasCameraPermission()) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        viewfinder = findViewById(R.id.viewfinder)
        previewView = findViewById(R.id.previewView)
        gridOverlay = findViewById(R.id.gridOverlay)
        referenceImage = findViewById(R.id.referenceImage)
        focusRing = findViewById(R.id.focusRing)
        countdownText = findViewById(R.id.countdown)
        hintText = findViewById(R.id.hint)
        zoomText = findViewById(R.id.zoomText)
        touchSurface = findViewById(R.id.touchSurface)
        flashButton = findViewById(R.id.flashButton)
        timerButton = findViewById(R.id.timerButton)
        gridButton = findViewById(R.id.gridButton)
        flipButton = findViewById(R.id.flipButton)
        thumbnail = findViewById(R.id.thumbnail)
        tabFilter = findViewById(R.id.tabFilter)
        tabReference = findViewById(R.id.tabReference)
        filterScroll = findViewById(R.id.filterScroll)
        filterRow = findViewById(R.id.filterRow)
        referencePanel = findViewById(R.id.referencePanel)
        uploadButton = findViewById(R.id.uploadButton)
        refControls = findViewById(R.id.refControls)
        opacitySeek = findViewById(R.id.opacitySeek)
        opacityValue = findViewById(R.id.opacityValue)
        adjustButton = findViewById(R.id.adjustButton)
        mirrorButton = findViewById(R.id.mirrorButton)
        replaceButton = findViewById(R.id.replaceButton)
        removeButton = findViewById(R.id.removeButton)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Make the viewfinder exactly 3:4 so the preview matches the saved photo. */
    private fun sizeViewfinder() {
        root.doOnLayout {
            val availW = root.width - root.paddingLeft - root.paddingRight
            val availH = root.height - root.paddingTop - root.paddingBottom - dp(52) - dp(212)
            val w: Int
            val h: Int
            if (availW * 4 <= availH * 3) {
                w = availW
                h = availW * 4 / 3
            } else {
                h = availH
                w = availH * 3 / 4
            }
            val lp = viewfinder.layoutParams as FrameLayout.LayoutParams
            lp.width = w
            lp.height = h
            viewfinder.layoutParams = lp
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val cameraProvider = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!cameraProvider.hasCamera(selector)) return

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(resolution).build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setResolutionSelector(resolution)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        cameraProvider.unbindAll()
        val cam = cameraProvider.bindToLifecycle(this, selector, preview, capture)
        camera = cam
        imageCapture = capture

        zoomLive?.removeObserver(zoomObserver)
        zoomLive = cam.cameraInfo.zoomState
        zoomLive?.observe(this, zoomObserver)

        if (!cam.cameraInfo.hasFlashUnit() && flashMode != ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_OFF
            capture.flashMode = flashMode
            updateTopLabels()
        }
    }

    private fun flipCamera() {
        val cameraProvider = provider ?: return
        val next = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(next).build()
        if (!cameraProvider.hasCamera(selector)) return
        lensFacing = next
        bindCamera()
    }

    // --------------------------------------------------------------- filters

    private fun buildFilterChips() {
        filters.forEachIndexed { index, filter ->
            val chip = TextView(this).apply {
                text = filter.label
                gravity = android.view.Gravity.CENTER
                textSize = 12f
                isAllCaps = true
                letterSpacing = 0.06f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(16), 0, dp(16), 0)
                tag = index
                setOnClickListener { selectFilter(index) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            lp.marginEnd = dp(8)
            filterRow.addView(chip, lp)
        }
        selectFilter(0)
    }

    private fun selectFilter(index: Int) {
        filterIndex = index
        for (i in 0 until filterRow.childCount) {
            val chip = filterRow.getChildAt(i) as TextView
            val on = i == index
            chip.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
            chip.setTextColor(ContextCompat.getColor(this, if (on) R.color.ground else R.color.bone))
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

    /** Tints the live preview. The saved photo is filtered separately in takePhoto. */
    private fun applyLiveFilter() {
        val tv = findTextureView(previewView) ?: return
        val matrix = filters[filterIndex].matrix
        if (matrix == null) {
            tv.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    // ------------------------------------------------------------- controls

    private fun setUpControls() {
        flashButton.setOnClickListener { cycleFlash() }
        timerButton.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            updateTopLabels()
        }
        gridButton.setOnClickListener {
            gridMode = (gridMode + 1) % 3
            gridOverlay.mode = gridMode
            updateTopLabels()
        }
        flipButton.setOnClickListener { flipCamera() }
        thumbnail.setOnClickListener { openLastPhoto() }
        findViewById<View>(R.id.shutter).setOnClickListener { onShutter() }

        tabFilter.setOnClickListener { showTab(0) }
        tabReference.setOnClickListener { showTab(1) }

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

    private fun setChip(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
        tv.setTextColor(ContextCompat.getColor(this, if (on) R.color.ground else R.color.bone))
    }

    private fun updateTopLabels() {
        val bone = ContextCompat.getColor(this, R.color.bone)
        val brass = ContextCompat.getColor(this, R.color.brass)

        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "Flash auto"
            ImageCapture.FLASH_MODE_ON -> "Flash on"
            else -> "Flash off"
        }
        flashButton.setTextColor(if (flashMode == ImageCapture.FLASH_MODE_OFF) bone else brass)

        timerButton.text = if (timerSeconds == 0) "Timer off" else "Timer ${timerSeconds}s"
        timerButton.setTextColor(if (timerSeconds == 0) bone else brass)

        gridButton.text = when (gridMode) {
            1 -> "Grid thirds"
            2 -> "Grid center"
            else -> "Grid off"
        }
        gridButton.setTextColor(if (gridMode == 0) bone else brass)
    }

    private fun cycleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) {
            Toast.makeText(this, R.string.no_flash, Toast.LENGTH_SHORT).show()
            return
        }
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateTopLabels()
    }

    private fun showTab(index: Int) {
        val brass = ContextCompat.getColor(this, R.color.brass)
        val dim = ContextCompat.getColor(this, R.color.dim)
        filterScroll.visibility = if (index == 0) View.VISIBLE else View.GONE
        referencePanel.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabFilter.setTextColor(if (index == 0) brass else dim)
        tabReference.setTextColor(if (index == 1) brass else dim)
    }

    // ----------------------------------------------------- reference overlay

    private fun importReference(uri: Uri) {
        cameraExecutor.execute {
            val bitmap = decodeReference(uri)
            runOnUiThread {
                if (bitmap == null) {
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        openFileOutput(REFERENCE_FILE, MODE_PRIVATE).use {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                    } catch (e: Exception) {
                        // Not being able to remember the image is not fatal.
                    }
                    resetReferenceTransform()
                    showReference(bitmap)
                    showTab(1)
                }
            }
        }
    }

    private fun decodeReference(uri: Uri): Bitmap? = try {
        val source = ImageDecoder.createSource(contentResolver, uri)
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
        val file = File(filesDir, REFERENCE_FILE)
        if (!file.exists()) {
            updateReferenceUi()
            return
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) showReference(bitmap) else updateReferenceUi()
    }

    private fun showReference(bitmap: Bitmap) {
        hasReference = true
        referenceImage.setImageBitmap(bitmap)
        referenceImage.visibility = View.VISIBLE
        referenceImage.alpha = opacitySeek.progress / 100f
        applyReferenceTransform()
        updateReferenceUi()
    }

    private fun removeReference() {
        hasReference = false
        setAdjustMode(false)
        referenceImage.setImageDrawable(null)
        referenceImage.visibility = View.GONE
        File(filesDir, REFERENCE_FILE).delete()
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

    // --------------------------------------------------------- touch gestures

    private fun setUpTouch() {
        val scaleDetector = ScaleGestureDetector(this,
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
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
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

    // ---------------------------------------------------------------- capture

    private fun onShutter() {
        if (countdown != null) {
            cancelCountdown()
            return
        }
        if (busy) return
        if (timerSeconds > 0) startCountdown() else takePhoto()
    }

    private fun startCountdown() {
        countdownText.visibility = View.VISIBLE
        countdown = object : CountDownTimer(timerSeconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                countdownText.text = ((msLeft + 999L) / 1000L).toString()
            }

            override fun onFinish() {
                countdown = null
                countdownText.visibility = View.GONE
                takePhoto()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
        countdownText.visibility = View.GONE
    }

    private fun photoValues(pending: Boolean): ContentValues {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Frame_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Frame")
            if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        findViewById<View>(R.id.shutter).performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        viewfinder.animate().alpha(0.25f).setDuration(60).withEndAction {
            viewfinder.animate().alpha(1f).setDuration(140).start()
        }.start()

        val matrix = filters[filterIndex].matrix
        if (matrix == null) {
            val options = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(false)
            ).build()
            capture.takePicture(options, ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        finishCapture(result.savedUri)
                    }

                    override fun onError(exc: ImageCaptureException) {
                        finishCapture(null)
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
                    runOnUiThread { finishCapture(uri) }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { finishCapture(null) }
                }
            })
        }
    }

    private fun saveFiltered(image: ImageProxy, matrix: ColorMatrix): Uri? {
        val rotation = image.imageInfo.rotationDegrees
        val source = image.toBitmap()
        val rotateBy = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotateBy, true)
        if (rotated !== source) source.recycle()

        val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(rotated, 0f, 0f, paint)
        rotated.recycle()

        val values = photoValues(true)
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            output.recycle()
            return null
        }
        contentResolver.openOutputStream(uri)?.use {
            output.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
        output.recycle()
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
        return uri
    }

    private fun finishCapture(uri: Uri?) {
        busy = false
        if (uri == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lastUri = uri
        showThumbnail(uri)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun showThumbnail(uri: Uri) {
        cameraExecutor.execute {
            val bitmap = try {
                contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) runOnUiThread { thumbnail.setImageBitmap(bitmap) }
        }
    }

    private fun openLastPhoto() {
        val uri = lastUri ?: return
        startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        countdown?.cancel()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REFERENCE_FILE = "reference.jpg"
    }
}
