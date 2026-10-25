package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import top.ltfan.backdrop.BackdropExtension

/** Retains rounded path geometry while callers evaluate the current outline on every draw. */
internal class SurfaceClipPath {
    private var path: Path? = null
    private var roundedShape: RoundRect? = null

    fun rounded(rect: RoundRect): Path {
        val previous = path
        if (previous != null && roundedShape == rect) return previous
        return reset(PathFillType.NonZero).also {
            it.addRoundRect(rect)
            roundedShape = rect
        }
    }

    fun reset(fillType: PathFillType): Path {
        roundedShape = null
        val selected = path ?: Path().also { path = it }
        selected.rewind()
        selected.fillType = fillType
        return selected
    }
}

/** Shared outline geometry for sampled surfaces and direct-background rendering. */
internal fun DrawScope.clipToExtendedShape(
    shape: Shape,
    surfaceSize: Size,
    extension: BackdropExtension,
    reusablePath: SurfaceClipPath?,
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
            val grown =
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
            val path = reusablePath?.rounded(grown) ?: Path().apply { addRoundRect(grown) }
            clipPath(path, block = block)
        }
        is Outline.Generic -> {
            if (extension.isZero) {
                clipPath(outline.path, block = block)
            } else {
                val grown = shape.createOutline(extendedSize, layoutDirection, this)
                // Added contours use the destination path's fill rule.
                val fillType =
                    if (grown is Outline.Generic) grown.path.fillType else PathFillType.NonZero
                val path =
                    reusablePath?.reset(fillType) ?: Path().apply { this.fillType = fillType }
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
                            },
                        )
                }
                clipPath(path, block = block)
            }
        }
    }
}

/** Bounds the same extended outline used for drawing, before antialiasing coverage. */
internal fun extendedSurfaceClipBounds(
    shape: Shape,
    surfaceSize: Size,
    extension: BackdropExtension,
    density: Density,
    layoutDirection: LayoutDirection,
): Rect {
    val extendedSize =
        Size(
            surfaceSize.width + extension.left + extension.right,
            surfaceSize.height + extension.top + extension.bottom,
        )
    if (extendedSize.width <= 0f || extendedSize.height <= 0f) return Rect.Zero
    return when (val outline = shape.createOutline(surfaceSize, layoutDirection, density)) {
        is Outline.Rectangle ->
            Rect(
                outline.rect.left - extension.left,
                outline.rect.top - extension.top,
                outline.rect.right + extension.right,
                outline.rect.bottom + extension.bottom,
            )
        is Outline.Rounded ->
            Rect(
                outline.roundRect.left - extension.left,
                outline.roundRect.top - extension.top,
                outline.roundRect.right + extension.right,
                outline.roundRect.bottom + extension.bottom,
            )
        is Outline.Generic ->
            if (extension.isZero) outline.bounds
            else
                shape
                    .createOutline(extendedSize, layoutDirection, density)
                    .bounds
                    .translate(-extension.left, -extension.top)
    }
}
