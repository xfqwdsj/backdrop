package top.ltfan.backdrop.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.max
import top.ltfan.backdrop.BackdropExtension

/** Pixel bounds shared by the surface's effect and decoration nodes. */
internal class SurfaceBounds {
    var effects: BackdropExtension by mutableStateOf(BackdropExtension.None)
    var shadow: BackdropExtension by mutableStateOf(BackdropExtension.None)

    fun outsets(requested: LayerOutsets, density: Density): LayerOutsets =
        with(density) {
            LayerOutsets(
                max(requested.left.toPx(), ceil(max(effects.left, shadow.left))).toDp(),
                max(requested.top.toPx(), ceil(max(effects.top, shadow.top))).toDp(),
                max(requested.right.toPx(), ceil(max(effects.right, shadow.right))).toDp(),
                max(requested.bottom.toPx(), ceil(max(effects.bottom, shadow.bottom))).toDp(),
            )
        }
}

internal expect fun surfaceRenderEffect(
    effect: RenderEffect?,
    size: Size,
    outsets: LayerOutsets,
    density: Density,
): RenderEffect?
