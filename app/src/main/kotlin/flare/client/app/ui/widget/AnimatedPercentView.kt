package flare.client.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import flare.client.app.R

class AnimatedPercentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val digits = mutableListOf<AnimatedDigitView>()
    private val percentSign: TextView
    private var currentProgress: Int = -1

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL

        for (i in 0 until 3) {
            val digit = AnimatedDigitView(context).apply {
                visibility = GONE
                setDigitTextSize(14f)
            }
            digits.add(digit)
            addView(digit)
        }

        percentSign = TextView(context).apply {
            text = "%"
            textSize = 14f
            setTextColor(ResourcesCompat.getColor(resources, R.color.text_secondary, null))
            typeface = try {
                ResourcesCompat.getFont(context, R.font.geologica_regular)
            } catch (e: Exception) {
                android.graphics.Typeface.DEFAULT
            }
        }
        addView(percentSign)
    }

    fun setProgress(progress: Int, animate: Boolean = true) {
        if (currentProgress == progress) return
        currentProgress = progress.coerceIn(0, 100)

        val s = currentProgress.toString()
        for (i in 0 until 3) {
            val digitView = digits[i]
            val digitIndexFromEnd = 3 - 1 - i
            val charIndex = s.length - 1 - digitIndexFromEnd
            if (charIndex >= 0) {
                digitView.visibility = VISIBLE
                val digitValue = s[charIndex].toString().toInt()
                digitView.setDigit(digitValue, animate)
            } else {
                digitView.visibility = GONE
            }
        }
    }

    fun setTextColorRes(colorRes: Int) {
        val color = ResourcesCompat.getColor(resources, colorRes, null)
        digits.forEach { it.setTextColorRes(colorRes) }
        percentSign.setTextColor(color)
    }

    fun setTextSizeSp(sizeSp: Float) {
        digits.forEach { it.setDigitTextSize(sizeSp) }
        percentSign.textSize = sizeSp
    }
}
