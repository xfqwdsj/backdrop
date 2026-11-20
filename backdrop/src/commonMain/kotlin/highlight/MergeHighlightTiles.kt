package top.ltfan.backdrop.highlight

import androidx.compose.ui.graphics.RenderEffect
import top.ltfan.backdrop.BackdropImageInput
import top.ltfan.backdrop.RuntimeShader

internal expect val supportsMergedHighlightTiles: Boolean

/** Returns a shared environment filter on platforms that support merging cropped branches. */
internal expect fun mergeHighlightTiles(
    environment: BackdropImageInput,
    shader: RuntimeShader,
    tiles: HighlightTiles,
): RenderEffect?
