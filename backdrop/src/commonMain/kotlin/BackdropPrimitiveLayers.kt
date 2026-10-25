package top.ltfan.backdrop

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/**
 * Records an owned [layer] in surface-local coordinates using the resolved graph storage. The
 * layer's placement and recording origin include [BackdropPrimitiveContext.extension]; draw-scope
 * and brush sizes remain the surface size. Draw explicitly across the extension when the operation
 * needs those pixels. Configure layer effects, alpha and blending separately.
 *
 * Call during primitive drawing. Borrowed inputs stay valid only for that invocation. This helper
 * records commands and leaves sampling declarations and layer ownership with their existing owners.
 */
public inline fun BackdropPrimitiveDrawContext.recordLayer(
    layer: GraphicsLayer,
    crossinline block: DrawScope.() -> Unit,
) {
    checkActivePrimitiveDraw()
    val surfaceSize = size
    val room = extension
    val width = ceil(surfaceSize.width + room.left + room.right)
    val height = ceil(surfaceSize.height + room.top + room.bottom)
    require(
        width.isFinite() &&
            height.isFinite() &&
            width >= 0f &&
            height >= 0f &&
            width.toDouble() <= Int.MAX_VALUE &&
            height.toDouble() <= Int.MAX_VALUE,
    ) {
        "Primitive layer storage must fit non-negative pixel dimensions"
    }
    layer.topLeft = IntOffset(-room.left.toInt(), -room.top.toInt())
    layer.record(density, layoutDirection, IntSize(width.toInt(), height.toInt())) {
        val previous = drawContext.size
        drawContext.size = surfaceSize
        try {
            translate(room.left, room.top) { block() }
        } finally {
            drawContext.size = previous
        }
    }
}

@PublishedApi
internal fun BackdropPrimitiveDrawContext.checkActivePrimitiveDraw() {
    check((this as PrimitiveContext).drawing) {
        "Primitive layers can be recorded only during drawing"
    }
}
