package app.frame.camera

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Full-screen viewer for one photo or video. */
class ViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        val d = resources.displayMetrics.density

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.fitsSystemWindows = true

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        if (video) {
            val player = VideoView(this)
            root.addView(player, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
            val controls = MediaController(this)
            controls.setAnchorView(player)
            player.setMediaController(controls)
            player.setVideoURI(uri)
            player.setOnPreparedListener { player.start() }
        } else {
            val picture = ImageView(this)
            picture.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(picture, FrameLayout.LayoutParams(match, match))
            Executors.newSingleThreadExecutor().execute {
                val bitmap = try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > 3000) {
                            val s = 3000f / longest
                            decoder.setTargetSize(
                                (info.size.width * s).toInt().coerceAtLeast(1),
                                (info.size.height * s).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (bitmap != null) picture.setImageBitmap(bitmap)
                    else Toast.makeText(this, "Could not open this photo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val size = (44 * d).toInt()
        val margin = (14 * d).toInt()
        root.addView(
            roundButton(R.drawable.ic_close, "Close") { finish() },
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                setMargins(margin, margin, margin, margin)
            }
        )
        root.addView(
            roundButton(R.drawable.ic_share, "Share") { share(uri, video) },
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                setMargins(margin, margin, margin, margin)
            }
        )
        setContentView(root)
    }

    private fun roundButton(icon: Int, description: String, onClick: () -> Unit): ImageView {
        val pad = (11 * resources.displayMetrics.density).toInt()
        return ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setBackgroundResource(R.drawable.circle_cam)
            contentDescription = description
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    private fun share(uri: Uri, video: Boolean) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (video) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share"))
    }

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun intent(context: Context, uri: Uri, video: Boolean): Intent =
            Intent(context, ViewerActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_VIDEO, video)
            }
    }
}
