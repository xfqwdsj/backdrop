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
import org.jetbrains.skia.Rect as SkiaRect
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.asSkikoRuntimeShader

internal actual val requiresSampledEffectInputImage: Boolean = false

internal actual fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this != null) {
        ImageFilter.makeCompose(other.skiaImageFilter, this.skiaImageFilter).asComposeRenderEffect()
    } else {
        other
    }
}

internal actual fun createRuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String,
    inputBounds: Rect?,
    outputBounds: Rect?,
): RenderEffect {
    val input = inputBounds?.let { bounds ->
        val rect =
            SkiaRect(
                floor(bounds.left),
                floor(bounds.top),
                ceil(bounds.right),
                ceil(bounds.bottom),
            )
        // Pixel-aligned source bounds retain complete input texels. The destination also covers
        // the output domain so a remapping can produce pixels outside its sampled input region.
        val output =
            outputBounds?.let {
                SkiaRect(
                    floor(minOf(bounds.left, it.left)),
                    floor(minOf(bounds.top, it.top)),
                    ceil(maxOf(bounds.right, it.right)),
                    ceil(maxOf(bounds.bottom, it.bottom)),
                )
            } ?: rect
        ImageFilter.makeTile(rect, output, null)
    }
    return ImageFilter.makeRuntimeShader(
            runtimeShader.asSkikoRuntimeShader(),
            uniformShaderName,
            input,
        )
        .asComposeRenderEffect()
}

internal actual fun createColorFilterEffect(
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
