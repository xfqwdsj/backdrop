package top.ltfan.backdrop.internal

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.asSkikoRuntimeShader

internal actual fun Paint.blur(radius: Float) {
    this.skiaPaint.maskFilter =
        if (radius > 0f) MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius) else null
}

internal actual fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.skiaPaint.shader = runtimeShader?.asSkikoRuntimeShader()?.makeShader()
}

internal actual fun Paint.setHdrColor(color: androidx.compose.ui.graphics.Color) {
    val rgb = colorUniformComponents(color)
    skiaPaint.setColor4f(
        org.jetbrains.skia.Color4f(rgb[0], rgb[1], rgb[2], color.alpha),
        org.jetbrains.skia.ColorSpace.sRGB,
    )
}
