package top.ltfan.backdrop.highlight

import androidx.compose.ui.graphics.RenderEffect
import top.ltfan.backdrop.BackdropImageInput
import top.ltfan.backdrop.RuntimeShader

internal actual val supportsMergedHighlightTiles: Boolean = false

internal actual fun mergeHighlightTiles(
    environment: BackdropImageInput,
    shader: RuntimeShader,
    tiles: HighlightTiles,
): RenderEffect? = null
