package flare.client.app.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import flare.client.app.R
import kotlin.math.abs
import kotlin.math.min


class LiquidPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blendMode = android.graphics.BlendMode.SCREEN
        } else {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val glowRect = RectF()

    var leftBound = 0f
        set(value) { field = value; invalidate() }
    var rightBound = 0f
        set(value) { field = value; invalidate() }
    var pillHeight = 0f
    var glowAlpha = 0f
    var verticalExpansion = 0f
        set(value) { field = value; invalidate() }
    
    private val glowCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillInnerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * dp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaders()
    }

    internal fun updateShaders() {
        val h = pillHeight.takeIf { it > 0 } ?: return
        pillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.argb(220, 80, 200, 255), Color.argb(245, 0, 100, 255)),
            null, Shader.TileMode.CLAMP
        )
        pillBorderPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(200, 255, 255, 255), 
                Color.argb(40, 255, 255, 255), 
                Color.argb(80, 0, 120, 255)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        
        pillInnerGlowPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.argb(100, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (leftBound >= rightBound || pillHeight <= 0) return

        val cy = height - (32f * dp)
        val expansion = verticalExpansion * dp
        val halfH = (pillHeight / 2f) + expansion
        val radius = halfH
        
        rect.set(leftBound, cy - halfH, rightBound, cy + halfH)

        // Base glow for depth
        val effectiveGlowAlpha = (0.05f + glowAlpha * 0.95f)
        
        val ambientMargin = 22f * dp
        glowRect.set(rect.left - ambientMargin, rect.top - ambientMargin, rect.right + ambientMargin, rect.bottom + ambientMargin)
        glowOuterPaint.shader = RadialGradient(
            rect.centerX(), rect.centerY(),
            glowRect.width() * 0.6f,
            intArrayOf(Color.argb((50 * effectiveGlowAlpha).toInt(), 0, 100, 255), Color.argb(0, 0, 100, 255)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(glowRect, radius + ambientMargin, radius + ambientMargin, glowOuterPaint)

        if (glowAlpha > 0.01f) {
            val coreMargin = 10f * dp
            glowRect.set(rect.left - coreMargin, rect.top - coreMargin, rect.right + coreMargin, rect.bottom + coreMargin)
            glowCorePaint.shader = RadialGradient(
                rect.centerX(), rect.centerY(),
                glowRect.width() * 0.5f,
                intArrayOf(Color.argb((180 * glowAlpha).toInt(), 100, 230, 255), Color.argb(0, 20, 120, 255)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(glowRect, radius + coreMargin, radius + coreMargin, glowCorePaint)
        }

        pillPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(Color.argb(255, 80, 200, 255), Color.argb(255, 0, 100, 255)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, pillPaint)

        val innerB = 1.0f * dp
        canvas.drawRoundRect(
            RectF(rect.left + innerB, rect.top + innerB, rect.right - innerB, rect.bottom - innerB),
            radius - innerB, radius - innerB, pillInnerGlowPaint
        )

        val b = 0.65f * dp
        canvas.drawRoundRect(
            RectF(rect.left + b, rect.top + b, rect.right - b, rect.bottom - b),
            radius - b, radius - b, pillBorderPaint
        )
    }

    fun animateToBounds(newLeft: Float, newRight: Float, dur: Long = 380) {
        val sL = leftBound; val sR = rightBound
        ValueAnimator.ofFloat(0f, 1f).apply {
            // Use sticky interpolator for viscosity
            duration = (dur * 1.2).toLong()
            interpolator = android.view.animation.AnticipateOvershootInterpolator(0.6f, 1.2f)
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                leftBound = sL + (newLeft - sL) * t
                rightBound = sR + (newRight - sR) * t
                updateShaders()
            }
            start()
        }
    }

    fun animateGlow(target: Float, duration: Long = 200) {
        ValueAnimator.ofFloat(glowAlpha, target).apply {
            this.duration = duration
            addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun animateExpansion(target: Float, duration: Long = 280) {
        ValueAnimator.ofFloat(verticalExpansion, target).apply {
            this.duration = duration
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { verticalExpansion = it.animatedValue as Float; invalidate() }
            start()
        }
    }
}


// SlidingBottomNav — navigation panel with blur effect.
class SlidingBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    internal var currentTab = -1
    var onTabSelected: ((Int) -> Unit)? = null

    private val navContainerFrame: FrameLayout
    private val glassBlurView: BlurView
    private val liquidPill: LiquidPillView
    private var liquidGlassShader: Any? = null
    private val btnSettings: ImageView
    private val btnHome: ImageView
    private val btnServers: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_bottom_nav, this, true)
        clipChildren = false
        clipToPadding = false

        navContainerFrame = findViewById(R.id.nav_container_frame)
        glassBlurView = findViewById(R.id.glass_blur_view)
        liquidPill = findViewById(R.id.nav_liquid_indicator)
        btnSettings = findViewById(R.id.iv_nav_settings)
        btnHome = findViewById(R.id.iv_nav_home)
        btnServers = findViewById(R.id.iv_nav_servers)

        btnSettings.setOnClickListener { selectTab(0, true) }
        btnHome.setOnClickListener { selectTab(1, true) }
        btnServers.setOnClickListener { selectTab(2, true) }

        glassBlurView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        glassBlurView.clipToOutline = true
    }

    fun setupBlur(blurTarget: BlurTarget) {
        try {
            val windowBg = (context as? android.app.Activity)?.window?.decorView?.background
            val builder = glassBlurView.setupWith(blurTarget)
                .setBlurRadius(8f)

            if (windowBg != null) {
                builder.setFrameClearDrawable(windowBg)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // LiquidGlassShader будет обновлять RenderEffect в onLayout
                val shader = LiquidGlassShader(glassBlurView)
                liquidGlassShader = shader
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val colorMatrix = android.graphics.ColorMatrix().apply {
                    setSaturation(1.45f)
                }
                glassBlurView.setRenderEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(colorMatrix)
                    )
                )
            } else {
                builder.setOverlayColor(android.graphics.Color.argb(40, 255, 255, 255))
            }
        } catch (e: Exception) {
            Log.e("SlidingBottomNav", "BlurView setup failed: ${e.message}", e)
        }
    }

    private var isInitialized = false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val containerWidth = glassBlurView.width
        if (containerWidth > 0) {
            val dp = resources.displayMetrics.density
            val pillH = glassBlurView.height.toFloat() - 14 * dp
            liquidPill.pillHeight = pillH
            liquidPill.updateShaders()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (liquidGlassShader as? LiquidGlassShader)?.update(
                    left = 0f, top = 0f, 
                    right = glassBlurView.width.toFloat(), 
                    bottom = glassBlurView.height.toFloat(),
                    radiusLeftTop = 24f * dp, radiusRightTop = 24f * dp,
                    radiusRightBottom = 24f * dp, radiusLeftBottom = 24f * dp,
                    thickness = 5f * dp,
                    intensity = 1.6f,
                    index = 1.5f,
                    foregroundColor = android.graphics.Color.argb(160, 32, 34, 40)
                )
            }
            
            if (!isInitialized) {
                isInitialized = true
                selectTab(if (currentTab == -1) 1 else currentTab, false)
            } else if (changed) {
                selectTab(currentTab, false)
            }
        }
    }

    internal fun selectTab(index: Int, animate: Boolean) {
        val changed = currentTab != index
        currentTab = index
        if (changed) onTabSelected?.invoke(index)

        val targetView = when (index) { 0 -> btnSettings; 1 -> btnHome; else -> btnServers }
        val dp = resources.displayMetrics.density

        listOf(btnSettings, btnHome, btnServers).forEachIndexed { i, btn ->
            val sel = (i == index)
            btn.animate()
                .alpha(if (sel) 1.0f else 0.45f)
                .scaleX(if (sel) 1.0f else 0.88f)
                .scaleY(if (sel) 1.0f else 0.88f)
                .setDuration(if (animate) 220 else 0)
                .start()
            btn.isSelected = sel
        }

        val pad = 8 * dp
        val tL = targetView.left.toFloat() + pad
        val tR = targetView.right.toFloat() - pad

        if (animate) {
            liquidPill.animateToBounds(tL, tR, 340)
        } else {
            liquidPill.leftBound = tL
            liquidPill.rightBound = tR
            liquidPill.invalidate()
        }
    }

    private var isDragging = false
    private var dsL = 0f; private var dsR = 0f; private var dsX = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val buf = 28 * resources.displayMetrics.density
            if (ev.x >= liquidPill.leftBound - buf && ev.x <= liquidPill.rightBound + buf) {
                isDragging = true
                dsL = liquidPill.leftBound; dsR = liquidPill.rightBound; dsX = ev.rawX
                liquidPill.animateGlow(1.0f, 100)
                liquidPill.animateExpansion(5f, 250)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isDragging) return super.onTouchEvent(ev)
        val dp = resources.displayMetrics.density

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val delta = ev.rawX - dsX
                val stretch = (abs(delta) * 0.08f).coerceAtMost(18 * dp)
                val cw = glassBlurView.width
                val pad = 8 * dp
                if (delta > 0) {
                    liquidPill.leftBound = (dsL + delta).coerceAtLeast(pad)
                    liquidPill.rightBound = (dsR + delta + stretch).coerceAtMost(cw - pad)
                } else {
                    liquidPill.leftBound = (dsL + delta - stretch).coerceAtLeast(pad)
                    liquidPill.rightBound = (dsR + delta).coerceAtMost(cw - pad)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                val center = (liquidPill.leftBound + liquidPill.rightBound) / 2f
                val tabW = glassBlurView.width / 3f
                val newTab = when { center < tabW -> 0; center < tabW * 2 -> 1; else -> 2 }
                liquidPill.animateGlow(0.0f, 200)
                liquidPill.animateExpansion(0f, 300)
                selectTab(newTab, true)
                return true
            }
        }
        return true
    }
}
