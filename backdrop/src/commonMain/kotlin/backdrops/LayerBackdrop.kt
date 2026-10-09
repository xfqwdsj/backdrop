package top.ltfan.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropDrawScope
import top.ltfan.backdrop.LocalBackdropRenderEpoch

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * Creates a [LayerBackdrop] that records its content into a [GraphicsLayer].
 *
 * The default layer is owned by Backdrop and is recreated whenever Backdrop refreshes its rendering
 * resources, which is what `BackdropHdrScope` triggers. The returned [LayerBackdrop] keeps its
 * identity across those refreshes. A [graphicsLayer] supplied by the caller is owned by the caller
 * and is not recreated by Backdrop.
 *
 * [tileMode] defines reads outside the recorded source rectangle before effects run.
 * [TileMode.Clamp] repeats boundary texels, including their alpha. [TileMode.Decal] supplies
 * transparent exterior pixels. Exported surfaces declare their drawn region as the source
 * rectangle; effect sampling padding is storage rather than source content. Edge extension requires
 * runtime shader support.
 */
@Composable
public fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberBackdropGraphicsLayer(),
    tileMode: TileMode = TileMode.Clamp,
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw,
): LayerBackdrop {
    val backdrop = remember(onDraw, tileMode) { LayerBackdrop(graphicsLayer, onDraw, tileMode) }
    SideEffect { backdrop.graphicsLayer = graphicsLayer }
    return backdrop
}

@Composable
private fun rememberBackdropGraphicsLayer(): GraphicsLayer =
    key(LocalBackdropRenderEpoch.current) { rememberGraphicsLayer() }

@Stable
public class LayerBackdrop
internal constructor(
    graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit,
    public val tileMode: TileMode = TileMode.Clamp,
) : Backdrop {

    public var graphicsLayer: GraphicsLayer by mutableStateOf(graphicsLayer)
        internal set

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * Where the surface's origin sits inside [graphicsLayer], which covers the surface plus the
     * insets it resolved. Sampling adds it so a consumer's coordinates meet the layer's pixels.
     */
    internal var layerOffset: Offset by mutableStateOf(Offset.Zero, neverEqualPolicy())

    /** Recorded source domain in layer pixels, independent of unused sampling padding. */
    internal var sourceBounds: Rect? by mutableStateOf(null, neverEqualPolicy())

    override fun BackdropDrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (!coordinates.isAttached || !layerCoordinates.isAttached) return

        val transform = Matrix()
        try {
            // The relative transform includes ancestor and modifier layer geometry, including its
            // pivot.
            coordinates.transformFrom(layerCoordinates, transform)
        } catch (_: IllegalArgumentException) {
            transformFromWindow(layerCoordinates, coordinates, transform)
        } catch (_: UnsupportedOperationException) {
            transformFromWindow(layerCoordinates, coordinates, transform)
        }
        require(transform.values.all { it.isFinite() }) {
            "Coordinate transform must be finite"
        }
        val homogeneousScale = transform[3, 3]
        require(homogeneousScale.isFinite() && homogeneousScale != 0f) {
            "Coordinate transform must have a finite, non-zero homogeneous scale"
        }
        // Normalize the z=0 plane mapping so floating-point inverse noise in unused z terms does
        // not change its two-dimensional coordinates. Preserve x/y perspective for validation.
        val normalizedTransform =
            Matrix().apply {
                this[0, 0] = transform[0, 0] / homogeneousScale
                this[0, 1] = transform[0, 1] / homogeneousScale
                this[0, 3] = transform[0, 3] / homogeneousScale
                this[1, 0] = transform[1, 0] / homogeneousScale
                this[1, 1] = transform[1, 1] / homogeneousScale
                this[1, 3] = transform[1, 3] / homogeneousScale
                this[3, 0] = transform[3, 0] / homogeneousScale
                this[3, 1] = transform[3, 1] / homogeneousScale
            }
        // Recorded pixels begin at the surface's expanded-layer offset, before coordinate mapping.
        normalizedTransform.translate(-layerOffset.x, -layerOffset.y)
        val sourceSize = graphicsLayer.size
        if (sourceSize.width <= 0 || sourceSize.height <= 0) return
        val bounds =
            sourceBounds ?: Rect(0f, 0f, sourceSize.width.toFloat(), sourceSize.height.toFloat())
        if (bounds.isEmpty) return
        drawSource(
            bounds,
            tileMode,
            normalizedTransform,
        ) {
            drawLayer(graphicsLayer)
        }
    }

    private fun transformFromWindow(
        source: LayoutCoordinates,
        target: LayoutCoordinates,
        matrix: Matrix,
    ) {
        val origin = target.windowToLocal(source.localToWindow(Offset.Zero))
        val x = target.windowToLocal(source.localToWindow(Offset(1f, 0f)))
        val y = target.windowToLocal(source.localToWindow(Offset(0f, 1f)))
        matrix.reset()
        matrix[0, 0] = x.x - origin.x
        matrix[0, 1] = x.y - origin.y
        matrix[1, 0] = y.x - origin.x
        matrix[1, 1] = y.y - origin.y
        matrix[3, 0] = origin.x
        matrix[3, 1] = origin.y
    }
}
