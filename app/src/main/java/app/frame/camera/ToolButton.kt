package app.frame.camera

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Icon with a small label underneath, used in the camera tool bar. */
class ToolButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val iconView = ImageView(context)
    private val labelView = TextView(context)
    private val idleColor = ContextCompat.getColor(context, R.color.cam_ink)
    private val activeColor = ContextCompat.getColor(context, R.color.cam_accent)

    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        addView(iconView, LayoutParams((22 * d).toInt(), (22 * d).toInt()))
        labelView.textSize = 10f
        labelView.gravity = Gravity.CENTER
        labelView.letterSpacing = 0.03f
        labelView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = (3 * d).toInt()
        addView(labelView, lp)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.ToolButton)
            val icon = a.getResourceId(R.styleable.ToolButton_toolIcon, 0)
            if (icon != 0) iconView.setImageResource(icon)
            labelView.text = a.getString(R.styleable.ToolButton_toolLabel) ?: ""
            a.recycle()
        }
        setActive(false)
    }

    fun setLabel(text: String) {
        labelView.text = text
        contentDescription = text
    }

    fun setIcon(res: Int) {
        iconView.setImageResource(res)
    }

    fun setActive(on: Boolean) {
        val color = if (on) activeColor else idleColor
        iconView.imageTintList = ColorStateList.valueOf(color)
        labelView.setTextColor(color)
    }
}
