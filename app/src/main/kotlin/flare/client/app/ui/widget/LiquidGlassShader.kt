package flare.client.app.ui.widget

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import flare.client.app.R
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LiquidGlassShader(private val view: View) {

    private val shader: RuntimeShader
    private var effect: RenderEffect

    init {
        val code = view.context.resources.openRawResource(R.raw.liquid_glass_shader).bufferedReader().readText()
        shader = RuntimeShader(code)
        effect = RenderEffect.createRuntimeShaderEffect(shader, "img")
        updateEffect()
    }

    private var resolutionX = 0f
    private var resolutionY = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var sizeX = 0f
    private var sizeY = 0f
    private var radiusLeftTop = 0f
    private var radiusRightTop = 0f
    private var radiusRightBottom = 0f
    private var radiusLeftBottom = 0f
    private var thickness = 0f
    private var intensity = 0f
    private var index = 0f
    private var foregroundColor = Color.TRANSPARENT

    fun update(
        left: Float, top: Float, right: Float, bottom: Float,
        radiusLeftTop: Float, radiusRightTop: Float, radiusRightBottom: Float, radiusLeftBottom: Float,
        thickness: Float = 3f,
        intensity: Float = 1.0f,
        index: Float = 1.5f,
        foregroundColor: Int = Color.argb(40, 255, 255, 255)
    ) {
        val resX = view.width.toFloat()
        val resY = view.height.toFloat()
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val width = right - left
        val height = bottom - top
        val sX = width / 2f
        val sY = height / 2f

        var rLT = radiusLeftTop
        var rLB = radiusLeftBottom
        var rRT = radiusRightTop
        var rRB = radiusRightBottom

        if (rLT + rLB > height && height > 0) {
            val a = rLT / (rLT + rLB)
            rLT = height * a
            rLB = height * (1.0f - a)
        }
        if (rRT + rRB > height && height > 0) {
            val a = rRT / (rRT + rRB)
            rRT = height * a
            rRB = height * (1.0f - a)
        }

        if (
            abs(resolutionX - resX) > 0.1f || abs(resolutionY - resY) > 0.1f ||
            abs(centerX - cx) > 0.1f || abs(centerY - cy) > 0.1f ||
            abs(sizeX - sX) > 0.1f || abs(sizeY - sY) > 0.1f ||
            abs(this.radiusLeftTop - rLT) > 0.1f || abs(this.radiusRightTop - rRT) > 0.1f ||
            abs(this.radiusRightBottom - rRB) > 0.1f || abs(this.radiusLeftBottom - rLB) > 0.1f ||
            abs(this.thickness - thickness) > 0.1f ||
            abs(this.intensity - intensity) > 0.1f ||
            abs(this.index - index) > 0.1f ||
            this.foregroundColor != foregroundColor
        ) {
            this.foregroundColor = foregroundColor
            resolutionX = resX
            resolutionY = resY
            centerX = cx
            centerY = cy
            sizeX = sX
            sizeY = sY
            this.radiusLeftTop = rLT
            this.radiusRightTop = rRT
            this.radiusRightBottom = rRB
            this.radiusLeftBottom = rLB
            this.thickness = thickness
            this.intensity = intensity
            this.index = index

            val a = Color.alpha(foregroundColor) / 255f
            val r = Color.red(foregroundColor) / 255f * a
            val g = Color.green(foregroundColor) / 255f * a
            val b = Color.blue(foregroundColor) / 255f * a

            shader.setFloatUniform("resolution", resolutionX, resolutionY)
            shader.setFloatUniform("center", centerX, centerY)
            shader.setFloatUniform("size", sizeX, sizeY)
            shader.setFloatUniform("radius", radiusRightBottom, radiusRightTop, radiusLeftBottom, radiusLeftTop)
            shader.setFloatUniform("thickness", thickness)
            shader.setFloatUniform("refract_intensity", intensity)
            shader.setFloatUniform("refract_index", index)
            shader.setFloatUniform("saturation", 1.45f)
            shader.setFloatUniform("foreground_color_premultiplied", r, g, b, a)

            updateEffect()
        }
    }

    private fun updateEffect() {
        val newEffect = RenderEffect.createRuntimeShaderEffect(shader, "img")
        view.setRenderEffect(newEffect)
    }
}
