package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asSkiaColorFilter
import androidx.compose.ui.graphics.skiaImageFilter
import kotlin.math.ceil
import kotlin.math.floor
import org.jetbrains.skia.ImageFilter
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.asSkikoRuntimeShader

internal actual fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this != null) {
        ImageFilter.makeCompose(other.skiaImageFilter, this.skiaImageFilter).asComposeRenderEffect()
    } else {
        other
    }
}

internal actual fun RuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String,
    inputBounds: Rect?,
): RenderEffect {
    val input = inputBounds?.let { bounds ->
        val rect =
            org.jetbrains.skia.Rect(
                floor(bounds.left),
                floor(bounds.top),
                ceil(bounds.right),
                ceil(bounds.bottom),
            )
        // Pixel-aligned tile bounds retain every declared input texel without fractional crop
        // alpha.
        ImageFilter.makeTile(rect, rect, null)
    }
    return ImageFilter.makeRuntimeShader(
            runtimeShader.asSkikoRuntimeShader(),
            uniformShaderName,
            input,
        )
        .asComposeRenderEffect()
}

internal actual fun ColorFilterEffect(
    renderEffect: RenderEffect?,
    colorFilter: ColorFilter,
): RenderEffect {
    return ImageFilter.makeColorFilter(
            colorFilter.asSkiaColorFilter(),
            renderEffect?.skiaImageFilter,
            null,
        )
        .asComposeRenderEffect()
}
