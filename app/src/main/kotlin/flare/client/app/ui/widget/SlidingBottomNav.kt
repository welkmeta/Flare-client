package flare.client.app.ui.widget

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import flare.client.app.R
import kotlin.math.abs

class GlowView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    private val strokeGloss = 2f * resources.displayMetrics.density
    private val strokeBlur = 6f * resources.displayMetrics.density

    private val paintGloss =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeGloss
            }

    private val paintBlur =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeBlur
                maskFilter =
                        BlurMaskFilter(
                                6f * resources.displayMetrics.density,
                                BlurMaskFilter.Blur.NORMAL
                        )
            }

    private val rect = RectF()
    var baseWidth = 0f
    var baseHeight = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var shaderWidth = 0f
    private var shaderHeight = 0f

    override fun onDraw(canvas: Canvas) {
        if (baseWidth <= 0f || baseHeight <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        if (shaderWidth != width.toFloat() || shaderHeight != height.toFloat()) {
            shaderWidth = width.toFloat()
            shaderHeight = height.toFloat()
            val gradient =
                    android.graphics.LinearGradient(
                            cx,
                            cy - (baseHeight / 2f),
                            cx,
                            cy + (baseHeight / 2f),
                            intArrayOf(
                                    Color.parseColor("#E6FFFFFF"),
                                    Color.parseColor("#1AFFFFFF")
                            ),
                            null,
                            android.graphics.Shader.TileMode.CLAMP
                    )
            paintGloss.shader = gradient
            paintBlur.shader = gradient
        }

        rect.set(
                cx - baseWidth / 2f,
                cy - baseHeight / 2f,
                cx + baseWidth / 2f,
                cy + baseHeight / 2f
        )
        val radius = baseHeight / 2f

        canvas.drawRoundRect(rect, radius, radius, paintBlur)
        canvas.drawRoundRect(rect, radius, radius, paintGloss)
    }
}

class SlidingBottomNav
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        FrameLayout(context, attrs, defStyleAttr) {

    internal var currentTab = -1 // -1: Not initialized, 0: Settings, 1: Home, 2: Servers
    var onTabSelected: ((Int) -> Unit)? = null

    private val navContainerFrame: FrameLayout
    private val navBlurBg: View
    private val indicator: View
    private val indicatorGlow: GlowView
    private val btnSettings: ImageView
    private val btnHome: ImageView
    private val btnServers: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_bottom_nav, this, true)

        clipChildren = false
        clipToPadding = false

        navContainerFrame = findViewById(R.id.nav_container_frame)
        navContainerFrame.clipChildren = false
        navContainerFrame.clipToPadding = false

        navBlurBg = findViewById(R.id.nav_blur_bg)
        indicator = findViewById(R.id.nav_indicator)
        indicatorGlow = findViewById(R.id.nav_indicator_glow)
        btnSettings = findViewById(R.id.iv_nav_settings)
        btnHome = findViewById(R.id.iv_nav_home)
        btnServers = findViewById(R.id.iv_nav_servers)

        btnSettings.setOnClickListener { selectTab(0, true) }
        btnHome.setOnClickListener { selectTab(1, true) }
        btnServers.setOnClickListener { selectTab(2, true) }

        setupBlurBackground()
    }

    private fun setupBlurBackground() {
        navBlurBg.post {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val blurEffect =
                        android.graphics.RenderEffect.createBlurEffect(
                                22f,
                                22f,
                                android.graphics.Shader.TileMode.CLAMP
                        )
                navBlurBg.setRenderEffect(blurEffect)
            }
            navBlurBg.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            navBlurBg.clipToOutline = true
        }
    }

    private var isInitialized = false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val containerWidth = navBlurBg.width
        if (containerWidth > 0) {
            val lp = indicator.layoutParams as FrameLayout.LayoutParams
            val margin = lp.leftMargin + lp.rightMargin
            val expectedWidth = containerWidth / 3 - margin
            if (lp.width != expectedWidth) {
                lp.width = expectedWidth
                indicator.layoutParams = lp

                val glowPadding = (40 * resources.displayMetrics.density).toInt()
                val lpGlow = indicatorGlow.layoutParams as FrameLayout.LayoutParams
                lpGlow.width = expectedWidth + glowPadding * 2
                lpGlow.height = navBlurBg.height + glowPadding * 2
                lpGlow.leftMargin = lp.leftMargin - glowPadding
                lpGlow.bottomMargin = lp.bottomMargin - glowPadding
                indicatorGlow.layoutParams = lpGlow

                indicatorGlow.baseWidth = expectedWidth.toFloat()
                indicatorGlow.baseHeight =
                        (navBlurBg.height.takeIf { it > 0 } ?: expectedWidth).toFloat()
                indicatorGlow.invalidate()
            }

            if (!isInitialized) {
                isInitialized = true
                val tab = if (currentTab == -1) 1 else currentTab
                selectTab(tab, false)
            } else if (changed) {
                selectTab(currentTab, false)
            }
        }
    }

    internal fun selectTab(index: Int, animate: Boolean) {
        val changed = currentTab != index
        currentTab = index
        if (changed) {
            onTabSelected?.invoke(index)
        }

        val targetView =
                when (index) {
                    0 -> btnSettings
                    1 -> btnHome
                    else -> btnServers
                }

        btnSettings.isSelected = (index == 0)
        btnHome.isSelected = (index == 1)
        btnServers.isSelected = (index == 2)

        val targetTx = targetView.left.toFloat()

        if (animate) {
            ObjectAnimator.ofFloat(indicator, "translationX", targetTx).apply {
                duration = 350
                interpolator = OvershootInterpolator(1.2f)
                start()
            }
            ObjectAnimator.ofFloat(indicatorGlow, "translationX", targetTx).apply {
                duration = 350
                interpolator = OvershootInterpolator(1.2f)
                start()
            }

            indicator
                    .animate()
                    .scaleX(1.06f)
                    .scaleY(0.96f)
                    .setDuration(150)
                    .withEndAction {
                        indicator
                                .animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(200)
                                .setInterpolator(OvershootInterpolator(1.5f))
                                .start()
                    }
                    .start()

            indicatorGlow
                    .animate()
                    .scaleX(1.06f)
                    .scaleY(0.96f)
                    .setDuration(150)
                    .withEndAction {
                        indicatorGlow
                                .animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(200)
                                .setInterpolator(OvershootInterpolator(1.5f))
                                .start()
                    }
                    .start()
        } else {
            indicator.translationX = targetTx
            indicatorGlow.translationX = targetTx
        }
    }

    private var dX = 0f
    private var isDragging = false
    private val loc = IntArray(2)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                indicator.getLocationOnScreen(loc)
                val iLeft = loc[0]
                val iTop = loc[1]
                val iRight = iLeft + indicator.width
                val iBottom = iTop + indicator.height

                // Touch buffer for gesture handling
                val buffer = (24 * resources.displayMetrics.density).toInt()

                if (event.rawX >= iLeft - buffer &&
                                event.rawX <= iRight + buffer &&
                                event.rawY >= iTop - buffer &&
                                event.rawY <= iBottom + buffer
                ) {
                    dX = indicator.translationX - event.rawX
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)

                    indicator.animate().scaleX(0.97f).scaleY(1.22f).setDuration(150).start()

                    indicatorGlow
                            .animate()
                            .alpha(1.0f)
                            .scaleX(0.97f)
                            .scaleY(1.22f)
                            .setDuration(150)
                            .start()

                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragging) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val containerWidth = navBlurBg.width
                val indicatorWidth = indicator.width
                val margin = (indicator.layoutParams as FrameLayout.LayoutParams).leftMargin

                val maxTx = (containerWidth - indicatorWidth - margin * 2).toFloat()

                var newTx = event.rawX + dX
                newTx = newTx.coerceIn(0f, maxTx)
                indicator.translationX = newTx
                indicatorGlow.translationX = newTx
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false

                indicator
                        .animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(250)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()

                indicatorGlow.animate().alpha(0f).scaleX(1.0f).scaleY(1.0f).setDuration(250).start()

                val target0 = btnSettings.left.toFloat()
                val target1 = btnHome.left.toFloat()
                val target2 = btnServers.left.toFloat()

                val tx = indicator.translationX
                val dist0 = kotlin.math.abs(tx - target0)
                val dist1 = kotlin.math.abs(tx - target1)
                val dist2 = kotlin.math.abs(tx - target2)

                val newTab =
                        when {
                            dist0 <= dist1 && dist0 <= dist2 -> 0
                            dist1 <= dist0 && dist1 <= dist2 -> 1
                            else -> 2
                        }
                selectTab(newTab, true)
                return true
            }
        }
        return true
    }
}
