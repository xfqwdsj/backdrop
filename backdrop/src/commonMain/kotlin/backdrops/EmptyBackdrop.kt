package top.ltfan.backdrop.backdrops

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropDrawScope

@Stable public fun emptyBackdrop(): Backdrop = EmptyBackdrop

@Immutable
private object EmptyBackdrop : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun BackdropDrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {}
}
