package top.ltfan.backdrop.internal

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

@Immutable
internal class ShapeProvider(val shapeBlock: () -> Shape) {

    private var _outline: State<Outline>? = null
    private var _size: Size = Size.Unspecified
    private var _layoutDirection: LayoutDirection? = null
    private var _density: Float? = null
    private var _fontScale: Float? = null

    val innerShape
        get() = shapeBlock()

    val shape =
        object : Shape {

            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                if (
                    _outline == null ||
                        _size != size ||
                        _layoutDirection != layoutDirection ||
                        _density != density.density ||
                        _fontScale != density.fontScale
                ) {
                    _size = size
                    _layoutDirection = layoutDirection
                    _density = density.density
                    _fontScale = density.fontScale
                    val fixedDensity = Density(density.density, density.fontScale)
                    _outline = derivedStateOf {
                        shapeBlock().createOutline(size, layoutDirection, fixedDensity)
                    }
                }

                return _outline!!.value
            }
        }
}
