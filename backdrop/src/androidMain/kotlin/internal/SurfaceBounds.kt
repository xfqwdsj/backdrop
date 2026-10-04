package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.Density

internal actual fun surfaceRenderEffect(
    effect: RenderEffect?,
    size: Size,
    outsets: LayerOutsets,
    density: Density,
): RenderEffect? = effect
