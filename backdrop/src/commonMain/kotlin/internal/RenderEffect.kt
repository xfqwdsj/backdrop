package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import top.ltfan.backdrop.RuntimeShader

internal expect fun RenderEffect?.chain(other: RenderEffect): RenderEffect

internal expect fun RuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String,
    inputBounds: Rect? = null,
): RenderEffect

internal expect fun ColorFilterEffect(
    renderEffect: RenderEffect? = null,
    colorFilter: ColorFilter,
): RenderEffect
