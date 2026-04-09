package flare.client.app.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextSwitcher
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.core.content.res.ResourcesCompat
import flare.client.app.R

class AnimatedDigitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextSwitcher(context, attrs), ViewSwitcher.ViewFactory {

    private var currentDigit: Int = -1

    init {
        if (childCount == 0) {
            setFactory(this)
        }
        setInAnimation(context, R.anim.slide_in_top)
        setOutAnimation(context, R.anim.slide_out_bottom)
    }

    override fun makeView(): View {
        return TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ResourcesCompat.getColor(resources, R.color.text_secondary, null))
            typeface = try {
                ResourcesCompat.getFont(context, R.font.geologica_regular)
            } catch (e: Exception) {
                android.graphics.Typeface.DEFAULT
            }
        }
    }

    fun setDigit(digit: Int, animate: Boolean = true) {
        if (currentDigit == digit) return
        currentDigit = digit
        if (animate) {
            setText(digit.toString())
        } else {
            val outAnim = outAnimation
            val inAnim = inAnimation
            setInAnimation(null)
            setOutAnimation(null)
            setCurrentText(digit.toString())
            setInAnimation(inAnim)
            setOutAnimation(outAnim)
        }
    }

    fun setTextColorRes(colorRes: Int) {
        val color = ResourcesCompat.getColor(resources, colorRes, null)
        (currentView as? TextView)?.setTextColor(color)
        (nextView as? TextView)?.setTextColor(color)
    }

    fun setDigitTextSize(sizeSp: Float) {
        (currentView as? TextView)?.textSize = sizeSp
        (nextView as? TextView)?.textSize = sizeSp
    }
}
