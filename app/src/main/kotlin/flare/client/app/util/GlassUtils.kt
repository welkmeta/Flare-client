package flare.client.app.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import eightbitlab.com.blurview.BlurView
import flare.client.app.R
import flare.client.app.ui.widget.LiquidGlassShader

object GlassUtils {

    data class MenuItem(val id: Int, val title: CharSequence, val onClick: () -> Unit)

    fun showGlassMenu(
        anchor: View,
        items: List<MenuItem>
    ) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val tempParent = android.widget.FrameLayout(context)
        val popupView = inflater.inflate(R.layout.layout_glass_menu, tempParent, false)
        val blurView = popupView.findViewById<BlurView>(R.id.glass_blur_view)
        val container = popupView.findViewById<ViewGroup>(R.id.menu_item_container)
        val activity = context as? Activity ?: run {
            var ctx = context
            while (ctx is android.content.ContextWrapper) {
                if (ctx is Activity) return@run ctx
                ctx = ctx.baseContext
            }
            null
        }

        activity?.let {
            val windowBg = it.window?.decorView?.background
            val blurTargetView = it.findViewById<View>(R.id.blur_target) as? eightbitlab.com.blurview.BlurTarget
            if (blurTargetView != null) {
                val builder = blurView.setupWith(blurTargetView)
                    .setBlurRadius(12f)
                    .setBlurAutoUpdate(true)

                if (windowBg != null) {
                    builder.setFrameClearDrawable(windowBg)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val shader = LiquidGlassShader(blurView)
                    val dp = context.resources.displayMetrics.density
                    val radius = 16f * dp
                    val isNightMode = (context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                    val fgColor = if (isNightMode)
                        Color.argb(120, 32, 34, 40)
                    else
                        Color.argb(60, 210, 215, 225)
                    blurView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        shader.update(
                            left = 0f, top = 0f,
                            right = blurView.width.toFloat(), bottom = blurView.height.toFloat(),
                            radiusLeftTop = radius, radiusRightTop = radius,
                            radiusRightBottom = radius, radiusLeftBottom = radius,
                            thickness = 2f * dp,
                            intensity = 1.6f, index = 1.5f,
                            foregroundColor = fgColor
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val colorMatrix = android.graphics.ColorMatrix().apply { setSaturation(1.45f) }
                    blurView.setRenderEffect(
                        android.graphics.RenderEffect.createColorFilterEffect(
                            android.graphics.ColorMatrixColorFilter(colorMatrix)
                        )
                    )
                } else {
                    val isNightModeFallback = (context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                    val overlayColor = if (isNightModeFallback)
                        Color.argb(80, 20, 20, 25)
                    else
                        Color.argb(120, 255, 255, 255)
                    builder.setOverlayColor(overlayColor)
                }
            }
        }

        blurView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        blurView.clipToOutline = true

        val lp = popupView.layoutParams
        val popupWidth = if (lp != null && lp.width > 0) lp.width else ViewGroup.LayoutParams.WRAP_CONTENT

        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 24f

        items.forEachIndexed { index, item ->
            val itemView = inflater.inflate(R.layout.item_glass_menu, container, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tv_menu_title)
            tvTitle.text = item.title
            itemView.setOnClickListener {
                item.onClick()
                popupWindow.dismiss()
            }
            container.addView(itemView)
            if (index < items.size - 1) {
                val divider = View(context)
                val dp = context.resources.displayMetrics.density
                divider.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()).apply {
                    marginStart = (16 * dp).toInt()
                    marginEnd = (16 * dp).toInt()
                }
                val isNightMode = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val dividerColor = if (isNightMode)
                    Color.argb(20, 255, 255, 255)
                else
                    Color.argb(30, 0, 0, 0)
                divider.setBackgroundColor(dividerColor)
                container.addView(divider)
            }
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val dp = context.resources.displayMetrics.density
        popupWindow.showAsDropDown(anchor, -(12 * dp).toInt(), -(12 * dp).toInt(), Gravity.END)
    }
}
