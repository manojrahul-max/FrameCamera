package app.frame.camera

import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private class Tab(
        val label: String,
        val icon: Int,
        val isCamera: Boolean,
        val create: () -> Fragment
    )

    private val tabs = listOf(
        Tab("Camera", R.drawable.ic_camera, true) { CameraFragment() },
        Tab("Studio", R.drawable.ic_effects, true) { app.frame.camera.studio.StudioFragment() },
        Tab("Gallery", R.drawable.ic_gallery, false) { GalleryFragment() }
    )

    private lateinit var navBar: LinearLayout
    private var current = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        navBar = findViewById(R.id.navBar)

        tabs.forEachIndexed { index, tab ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { select(index) }
            }
            val icon = ImageView(this).apply {
                setImageResource(tab.icon)
                tag = "icon"
            }
            val label = TextView(this).apply {
                text = tab.label
                textSize = 11f
                letterSpacing = 0.03f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tag = "label"
            }
            val d = resources.displayMetrics.density
            item.addView(icon, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * d).toInt()
            item.addView(label, lp)
            navBar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

        val start = if (savedInstanceState != null) savedInstanceState.getInt(KEY_TAB, 0) else 0
        select(start, force = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, current)
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun select(index: Int, force: Boolean = false) {
        if (index == current && !force) return
        current = index
        val tab = tabs[index]
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.container, tab.create())
            .commit()
        applyChrome(tab.isCamera)
    }

    /** The camera is always dark. Other tabs follow the phone's light or dark setting. */
    private fun applyChrome(camera: Boolean) {
        val bg = ContextCompat.getColor(this, if (camera) R.color.cam_ground else R.color.bg)
        val navBg = ContextCompat.getColor(this, if (camera) R.color.cam_ground else R.color.surface)
        val line = ContextCompat.getColor(this, if (camera) R.color.cam_stroke else R.color.nav_line)
        val idle = ContextCompat.getColor(this, if (camera) R.color.cam_dim else R.color.ink_dim)
        val on = ContextCompat.getColor(this, if (camera) R.color.cam_accent else R.color.accent)

        findViewById<View>(R.id.appRoot).setBackgroundColor(bg)
        navBar.setBackgroundColor(navBg)
        findViewById<View>(R.id.navLine).setBackgroundColor(line)

        for (i in 0 until navBar.childCount) {
            val item = navBar.getChildAt(i) as LinearLayout
            val color = if (i == current) on else idle
            (item.findViewWithTag<ImageView>("icon")).imageTintList = ColorStateList.valueOf(color)
            (item.findViewWithTag<TextView>("label")).setTextColor(color)
        }

        val light = !camera && !isNight()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    companion object {
        private const val KEY_TAB = "tab"
    }
}
