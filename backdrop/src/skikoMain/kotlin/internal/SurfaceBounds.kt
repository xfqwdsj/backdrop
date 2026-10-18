package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.skiaImageFilter
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Rect

internal actual fun surfaceRenderEffect(
    effect: RenderEffect?,
    size: Size,
    outsets: LayerOutsets,
    density: Density,
): RenderEffect? =
    with(density) {
        if (size.width <= 0f || size.height <= 0f) return effect
        val bounds =
            Rect(
                0f,
                0f,
                outsets.left.toPx() + size.width + outsets.right.toPx(),
                outsets.top.toPx() + size.height + outsets.bottom.toPx(),
            )
        // Skiko records the surface at (left, top) inside the expanded layer buffer. Tile bounds
        // use that buffer's coordinates, including both margins.
        // Runtime shaders can read away from the output pixel. Request the complete surface input
        // so a partially overlapping consumer cannot shrink that input to its own sampling bounds.
        ImageFilter.makeTile(bounds, bounds, effect?.skiaImageFilter).asComposeRenderEffect()
    }
