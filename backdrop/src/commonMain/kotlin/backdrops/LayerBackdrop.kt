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
 * transparent exterior pixels. Exported surfaces use their visible output bounds, translated into
 * layer pixels and rounded outward, as the source rectangle. Unused effect sampling padding is
 * storage rather than source content. Edge extension requires runtime shader support.
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
     * Surface origin in [graphicsLayer] storage coordinates. The offset includes recording margins,
     * so source sampling maps surface coordinates to the corresponding stored pixels.
     */
    internal var layerOffset: Offset by mutableStateOf(Offset.Zero, neverEqualPolicy())

    /** Pixel-aligned source domain in layer storage; null uses the recorded layer size. */
    internal var sourceBounds: Rect? by mutableStateOf(null, neverEqualPolicy())

    override fun BackdropDrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        // Read the recorded state before the early returns below. A surface that samples a layer
        // which is detached or empty has to observe the next recording, so that it draws the
        // content as soon as the layer has any: the recorded size of a graphics layer is not
        // observable, so a surface that returns before reading these stays empty until something
        // else invalidates it.
        val recorded = sourceBounds
        val offset = layerOffset
        val recordedLayer = graphicsLayer
        val recordedCoordinates = layerCoordinates

        val coordinates = coordinates ?: return
        val layerCoordinates = recordedCoordinates ?: return
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
        val m00 = transform[0, 0] / homogeneousScale
        val m01 = transform[0, 1] / homogeneousScale
        val m03 = transform[0, 3] / homogeneousScale
        val m10 = transform[1, 0] / homogeneousScale
        val m11 = transform[1, 1] / homogeneousScale
        val m13 = transform[1, 3] / homogeneousScale
        val m30 = transform[3, 0] / homogeneousScale
        val m31 = transform[3, 1] / homogeneousScale
        transform.reset()
        transform[0, 0] = m00
        transform[0, 1] = m01
        transform[0, 3] = m03
        transform[1, 0] = m10
        transform[1, 1] = m11
        transform[1, 3] = m13
        transform[3, 0] = m30
        transform[3, 1] = m31
        // Recorded pixels begin at the surface's expanded-layer offset, before coordinate mapping.
        transform.translate(-offset.x, -offset.y)
        val sourceSize = recordedLayer.size
        // Retained bounds let consumers reference a replacement layer before its producer records
        // it.
        val bounds =
            recorded ?: Rect(0f, 0f, sourceSize.width.toFloat(), sourceSize.height.toFloat())
        if (bounds.isEmpty) return
        drawSource(
            bounds,
            tileMode,
            transform,
        ) {
            drawLayer(recordedLayer)
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
