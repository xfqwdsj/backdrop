package top.ltfan.backdrop.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.setOutline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.ceil
import top.ltfan.backdrop.LocalBackdropRenderEpoch

internal class SurfaceIsolationElement(
    private val surfaceBounds: SurfaceBounds,
    private val layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : ModifierNodeElement<SurfaceIsolationNode>() {
    override fun create(): SurfaceIsolationNode = SurfaceIsolationNode(surfaceBounds, layerBlock)

    override fun update(node: SurfaceIsolationNode) {
        node.surfaceBounds = surfaceBounds
        node.layerBlock = layerBlock
        node.invalidateMeasurement()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "backdropSurfaceIsolation"
        properties["layerBlock"] = layerBlock
    }

    override fun equals(other: Any?): Boolean =
        other is SurfaceIsolationElement &&
            surfaceBounds === other.surfaceBounds &&
            layerBlock === other.layerBlock

    override fun hashCode(): Int = 31 * surfaceBounds.hashCode() + layerBlock.hashCode()
}

internal class SurfaceIsolationNode(
    surfaceBounds: SurfaceBounds,
    layerBlock: (GraphicsLayerScope.() -> Unit)?,
) :
    Modifier.Node(),
    LayoutModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {
    var surfaceBounds: SurfaceBounds by mutableStateOf(surfaceBounds, neverEqualPolicy())
    var layerBlock: (GraphicsLayerScope.() -> Unit)? by
        mutableStateOf(layerBlock, neverEqualPolicy())

    private val layerOwner = EpochResourceOwner<GraphicsLayer>()

    override fun onAttach() {
        val context = requireGraphicsContext()
        layerOwner.attach(currentValueOf(LocalBackdropRenderEpoch)) {
            context.createGraphicsLayer()
        }
    }

    override fun onDetach() {
        val context = requireGraphicsContext()
        layerOwner.release(context::releaseGraphicsLayer)
    }

    override fun onObservedReadsChanged() {
        invalidateMeasurement()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val nodeSize = Size(placeable.width.toFloat(), placeable.height.toFloat())

        observeReads {
            val epoch = currentValueOf(LocalBackdropRenderEpoch)
            val context = requireGraphicsContext()
            val currentLayer =
                layerOwner.resourceFor(
                    epoch,
                    context::createGraphicsLayer,
                    context::releaseGraphicsLayer,
                )
            configureLayer(currentLayer, nodeSize, this, layoutDirection)
        }
        val epoch = currentValueOf(LocalBackdropRenderEpoch)
        val context = requireGraphicsContext()
        val placementLayer =
            layerOwner.resourceFor(
                epoch,
                context::createGraphicsLayer,
                context::releaseGraphicsLayer,
            )

        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, placementLayer)
        }
    }

    private fun configureLayer(
        layer: GraphicsLayer,
        size: Size,
        density: androidx.compose.ui.unit.Density,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    ) {
        val scope = InverseLayerScope()
        scope.reset()
        scope.size = size
        scope.density = density.density
        scope.fontScale = density.fontScale
        layerBlock?.invoke(scope)

        val outsets = surfaceBounds.outsets(scope.outsets, density)
        with(density) {
            layer.setOutsets(
                ceil(outsets.left.toPx()).toInt(),
                ceil(outsets.top.toPx()).toInt(),
                ceil(outsets.right.toPx()).toInt(),
                ceil(outsets.bottom.toPx()).toInt(),
            )
        }
        layer.compositingStrategy = CompositingStrategy.Offscreen
        layer.alpha = scope.alpha
        layer.shadowElevation = scope.shadowElevation
        layer.ambientShadowColor = scope.ambientShadowColor
        layer.spotShadowColor = scope.spotShadowColor
        layer.blendMode = scope.blendMode
        layer.colorFilter = scope.colorFilter
        layer.renderEffect = surfaceRenderEffect(scope.renderEffect, size, outsets, density)
        layer.setOutline(scope.shape.createOutline(size, layoutDirection, density))
        layer.clip = false
    }
}
