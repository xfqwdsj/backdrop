package top.ltfan.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropDrawScope

@Composable
public fun rememberBackdrop(
    backdrop: Backdrop,
    onDraw: BackdropDrawScope.(drawBackdrop: BackdropDrawScope.() -> Unit) -> Unit,
): Backdrop {
    return remember(backdrop, onDraw) {
        Backdrop(backdrop, onDraw)
    }
}

@Immutable
private class Backdrop(
    val backdrop: Backdrop,
    val onDraw: BackdropDrawScope.(drawBackdrop: BackdropDrawScope.() -> Unit) -> Unit,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = backdrop.isCoordinatesDependent

    override fun BackdropDrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        onDraw { with(backdrop) { drawBackdrop(density, coordinates, layerBlock) } }
    }
}
