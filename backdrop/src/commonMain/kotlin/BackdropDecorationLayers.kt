package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Records an explicitly owned layer in surface coordinates. Storage rounds [bounds] outward to
 * pixels; drawing and brush sizes remain the measured surface size. Call during decoration drawing
 * and configure layer effects, alpha and blending separately.
 */
public inline fun BackdropDecorationDrawContext.recordLayer(
    layer: GraphicsLayer,
    bounds: Rect = outputBounds,
    crossinline block: DrawScope.() -> Unit,
) {
    checkActiveDecorationDraw()
    val left = floor(bounds.left)
    val top = floor(bounds.top)
    val right = ceil(bounds.right)
    val bottom = ceil(bounds.bottom)
    require(
        left.isFinite() &&
            top.isFinite() &&
            right.isFinite() &&
            bottom.isFinite() &&
            left <= right &&
            top <= bottom &&
            left.toDouble() >= Int.MIN_VALUE &&
            top.toDouble() >= Int.MIN_VALUE &&
            right.toDouble() <= Int.MAX_VALUE &&
            bottom.toDouble() <= Int.MAX_VALUE &&
            right.toDouble() - left.toDouble() <= Int.MAX_VALUE &&
            bottom.toDouble() - top.toDouble() <= Int.MAX_VALUE,
    ) {
        "Decoration layer bounds must fit ordered finite pixel storage"
    }
    val surfaceSize = size
    layer.topLeft = IntOffset(left.toInt(), top.toInt())
    layer.record(
        density,
        layoutDirection,
        IntSize((right - left).toInt(), (bottom - top).toInt()),
    ) {
        val previous = drawContext.size
        drawContext.size = surfaceSize
        try {
            translate(-left, -top) { block() }
        } finally {
            drawContext.size = previous
        }
    }
}

@PublishedApi
internal fun BackdropDecorationDrawContext.checkActiveDecorationDraw() {
    check((this as DecorationContext).drawing) {
        "Decoration layers can be recorded only during drawing"
    }
}
