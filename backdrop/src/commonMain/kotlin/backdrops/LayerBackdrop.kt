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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropDrawScope
import top.ltfan.backdrop.LocalBackdropRenderEpoch
import top.ltfan.backdrop.internal.InverseLayerScope

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

    private var inverseLayerScope: InverseLayerScope? = null

    override fun BackdropDrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        val offset =
            try {
                layerCoordinates.localPositionOf(coordinates)
            } catch (_: Exception) {
                // TODO: outer transformations lead to wrong position calculation
                coordinates.positionInWindow() - layerCoordinates.positionInWindow()
            }
        val transform =
            if (layerBlock != null)
                obtainInverseLayerScope().inverseTransform(density, size, layerBlock)
            else Matrix()
        transform.translate(-offset.x - layerOffset.x, -offset.y - layerOffset.y)
        val sourceSize = graphicsLayer.size
        if (sourceSize.width <= 0 || sourceSize.height <= 0) return
        val bounds =
            sourceBounds ?: Rect(0f, 0f, sourceSize.width.toFloat(), sourceSize.height.toFloat())
        if (bounds.isEmpty) return
        drawSource(
            bounds,
            tileMode,
            transform,
        ) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
