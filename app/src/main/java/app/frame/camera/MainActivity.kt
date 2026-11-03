package app.frame.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashButton: TextView
    private lateinit var flipButton: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var focusRing: View
    private lateinit var cameraExecutor: ExecutorService

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var lastUri: Uri? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.need_permission, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        flashButton = findViewById(R.id.flashButton)
        flipButton = findViewById(R.id.flipButton)
        thumbnail = findViewById(R.id.thumbnail)
        focusRing = findViewById(R.id.focusRing)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<View>(R.id.shutter).setOnClickListener { takePhoto() }
        flashButton.setOnClickListener { cycleFlash() }
        flipButton.setOnClickListener { flipCamera() }
        thumbnail.setOnClickListener { openLastPhoto() }
        setUpTouch()

        if (hasCameraPermission()) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

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

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(this, selector, preview, capture)
        imageCapture = capture
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

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        flashButton.setText(
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
                ImageCapture.FLASH_MODE_ON -> R.string.flash_on
                else -> R.string.flash_off
            }
        )
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Frame_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Frame")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        previewView.animate().alpha(0.2f).setDuration(60).withEndAction {
            previewView.animate().alpha(1f).setDuration(120).start()
        }.start()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val uri = result.savedUri ?: return
                    lastUri = uri
                    showThumbnail(uri)
                    Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            })
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

    private fun setUpTouch() {
        val scale = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(current * d.scaleFactor)
                    return true
                }
            })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            tap.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())

        focusRing.x = previewView.x + x - focusRing.width / 2f
        focusRing.y = previewView.y + y - focusRing.height / 2f
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.animate().alpha(0f).setStartDelay(700).setDuration(250).start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
