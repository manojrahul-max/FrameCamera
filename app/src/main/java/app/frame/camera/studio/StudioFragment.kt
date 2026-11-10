package app.frame.camera.studio

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import app.frame.camera.EffectsFragment
import app.frame.camera.R

/** The creative hub: Collage Studio, Live Effects, Effect Playground and My Creations. */
class StudioFragment : Fragment() {

    companion object {
        const val COLLAGE = 0
        const val LIVE = 1
        const val PLAYGROUND = 2
        const val CREATIONS = 3
        const val PHOTO_LAB = 4
    }

    private lateinit var homeView: View
    private lateinit var host: FrameLayout
    private var back: OnBackPressedCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val root = FrameLayout(ctx).apply { setBackgroundColor(Ui.color(ctx, R.color.cam_ground)) }
        homeView = buildHome()
        host = FrameLayout(ctx).apply { id = R.id.studio_host; visibility = View.GONE }
        root.addView(homeView, FrameLayout.LayoutParams(-1, -1))
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val c = childFragmentManager.findFragmentById(R.id.studio_host)
                if (c is CollageFragment && c.onBack()) return
                if (c is CreationsFragment) { /* fall through to home */ }
                home()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back!!)
        return root
    }

    private fun buildHome(): View {
        val ctx = requireContext()
        val sv = ScrollView(ctx)
        val col = Ui.col(ctx).apply { setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 24)) }
        col.addView(Ui.title(ctx, "Studio", 28f))
        col.addView(Ui.label(ctx, "Design collages and build your own camera effects.", 13f), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, 4); it.bottomMargin = Ui.dp(ctx, 18) })
        val cards = listOf(
            Triple("Collage Studio", "65 templates, or design your own. Capture straight into each frame.", intArrayOf(0xFF3A2A5C.toInt(), 0xFFB5527A.toInt())) to COLLAGE,
            Triple("Live Effects", "Real-time effects on the camera, from the ready-made set or your own.", intArrayOf(0xFF123B4A.toInt(), 0xFF2E8C8A.toInt())) to LIVE,
            Triple("Effect Playground", "Build an effect from layers, regions and brushes and watch it live.", intArrayOf(0xFF4A2A12.toInt(), 0xFFD08B2E.toInt())) to PLAYGROUND,
            Triple("My Creations", "Saved effects and layouts. Edit, duplicate, share and import.", intArrayOf(0xFF1F2A20.toInt(), 0xFF5E8C6A.toInt())) to CREATIONS
        )
        for ((c, id) in cards) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 20))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, c.third).apply { cornerRadius = Ui.dp(ctx, 22).toFloat(); setStroke(Ui.dp(ctx, 1), 0x33FFFFFF) }
                isClickable = true; isFocusable = true
                setOnClickListener { open(id) }
            }
            card.addView(TextView(ctx).apply { text = c.first; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            card.addView(TextView(ctx).apply { text = c.second; textSize = 12.5f; setTextColor(0xCCFFFFFF.toInt()); setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Ui.dp(ctx, 4) })
            col.addView(card, LinearLayout.LayoutParams(-1, Ui.dp(ctx, 132)).also { it.bottomMargin = Ui.dp(ctx, 14) })
        }
        sv.addView(col)
        return sv
    }

    /** Opens a section. [effectId] or [presetId] opens the Playground on that effect, [layoutId] opens a saved layout. */
    fun open(section: Int, effectId: String? = null, presetId: String? = null, layoutId: String? = null) {
        val f: Fragment = when (section) {
            COLLAGE -> CollageFragment.newInstance(layoutId)
            LIVE -> LiveEffectsFragment()
            PLAYGROUND -> PlaygroundFragment.newInstance(effectId, presetId)
            CREATIONS -> CreationsFragment()
            else -> EffectsFragment()
        }
        childFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.studio_host, f)
            .commitAllowingStateLoss()
        host.visibility = View.VISIBLE
        homeView.visibility = View.GONE
        back?.isEnabled = true
    }

    fun home() {
        childFragmentManager.findFragmentById(R.id.studio_host)?.let {
            childFragmentManager.beginTransaction().setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out).remove(it).commitAllowingStateLoss()
        }
        host.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        back?.isEnabled = false
    }
}
