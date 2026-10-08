package top.ltfan.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropDrawScope

/**
 * Creates a procedural source. Draw over [BackdropDrawScope.samplingBounds] to cover the complete
 * effect request, or use [BackdropDrawScope.drawSource] to declare a finite pixel domain and its
 * boundary behavior. Use [BackdropDrawScope.withTransform] when transforming source coordinates so
 * the sampling request follows the transform.
 */
@Composable
public fun rememberCanvasBackdrop(onDraw: BackdropDrawScope.() -> Unit): Backdrop {
    return remember(onDraw) {
        CanvasBackdrop(onDraw)
    }
}

@Immutable
private class CanvasBackdrop(val onDraw: BackdropDrawScope.() -> Unit) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun BackdropDrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        onDraw()
    }
}
