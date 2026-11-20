package top.ltfan.backdrop.highlight

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.skiaImageFilter
import org.jetbrains.skia.IRect
import org.jetbrains.skia.ImageFilter
import top.ltfan.backdrop.BackdropImageInput
import top.ltfan.backdrop.RuntimeShader

internal actual val supportsMergedHighlightTiles: Boolean = true

internal actual fun mergeHighlightTiles(
    environment: BackdropImageInput,
    shader: RuntimeShader,
    tiles: HighlightTiles,
): RenderEffect? {
    shader.setFloatUniform("origin", 0f, 0f)
    val effect = environment.runtimeShaderEffect(shader, "environment").skiaImageFilter
    val branches = arrayOfNulls<ImageFilter>(tiles.count)
    try {
        for (index in branches.indices) {
            val tile = tiles[index]
            val crop = IRect.makeLTRB(tile.left, tile.top, tile.right, tile.bottom)
            branches[index] = ImageFilter.makeOffset(0f, 0f, effect, crop)
        }
        return ImageFilter.makeMerge(branches, null).asComposeRenderEffect()
    } finally {
        branches.forEach { it?.close() }
        effect.close()
    }
}
