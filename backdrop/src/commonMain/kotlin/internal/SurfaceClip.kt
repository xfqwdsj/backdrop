package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import top.ltfan.backdrop.BackdropExtension

/** Shared outline geometry for sampled surfaces and direct-background rendering. */
internal fun DrawScope.clipToExtendedShape(
    shape: Shape,
    surfaceSize: Size,
    extension: BackdropExtension,
    reusablePath: Path?,
    block: DrawScope.() -> Unit,
) {
    val left = extension.left
    val top = extension.top
    val right = extension.right
    val bottom = extension.bottom
    val extendedSize = Size(surfaceSize.width + left + right, surfaceSize.height + top + bottom)
    if (extendedSize.width <= 0f || extendedSize.height <= 0f) return
    when (val outline = shape.createOutline(surfaceSize, layoutDirection, this)) {
        is Outline.Rectangle ->
            clipRect(
                outline.rect.left - left,
                outline.rect.top - top,
                outline.rect.right + right,
                outline.rect.bottom + bottom,
                block = block,
            )
        is Outline.Rounded -> {
            val rect = outline.roundRect
            val path = reusablePath ?: Path()
            path.rewind()
            path.addRoundRect(
                RoundRect(
                    rect.left - left,
                    rect.top - top,
                    rect.right + right,
                    rect.bottom + bottom,
                    rect.topLeftCornerRadius,
                    rect.topRightCornerRadius,
                    rect.bottomRightCornerRadius,
                    rect.bottomLeftCornerRadius,
                )
            )
            clipPath(path, block = block)
        }
        is Outline.Generic -> {
            if (extension.isZero) {
                clipPath(outline.path, block = block)
            } else {
                val grown = shape.createOutline(extendedSize, layoutDirection, this)
                val path = reusablePath ?: Path()
                path.rewind()
                when (grown) {
                    is Outline.Generic -> path.addPath(grown.path, Offset(-left, -top))
                    is Outline.Rectangle -> path.addRect(grown.rect.translate(-left, -top))
                    is Outline.Rounded ->
                        path.addRoundRect(
                            grown.roundRect.let { rect ->
                                RoundRect(
                                    rect.left - left,
                                    rect.top - top,
                                    rect.right - left,
                                    rect.bottom - top,
                                    rect.topLeftCornerRadius,
                                    rect.topRightCornerRadius,
                                    rect.bottomRightCornerRadius,
                                    rect.bottomLeftCornerRadius,
                                )
                            }
                        )
                }
                clipPath(path, block = block)
            }
        }
    }
}
