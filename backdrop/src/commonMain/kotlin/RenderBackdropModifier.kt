package top.ltfan.backdrop

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.floor
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.interaction.LocalInteractionRegistry
import top.ltfan.backdrop.internal.BackdropGraphRenderer
import top.ltfan.backdrop.internal.BackdropSourceRenderer
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.internal.SurfaceBounds
import top.ltfan.backdrop.internal.SurfaceIsolationElement
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.recordLayer

/**
 * Draws an explicitly ordered background graph, then child content, then an optional foreground.
 * [backgroundAlpha] applies once to the whole background and leaves children and foreground alone.
 * At zero, background recording is skipped; an exported result is recorded transparent. Null
 * [render] and [insets] inherit producers at this modifier's use position. [exportedBackdrop]
 * receives the selected graph image as a finite source. Its visible bounds are rounded outward in
 * layer-storage coordinates to include every touched pixel.
 */
public fun Modifier.renderBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    backgroundAlpha: () -> Float = { 1f },
    insets: (() -> BackdropInsets)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    render: (BackdropRenderScope.() -> Unit)? = null,
): Modifier {
    val bounds = SurfaceBounds()
    return this.graphicsLayer {
            layerBlock?.invoke(this)
            alpha = if (alpha == 0f) 0f else 1f
            compositingStrategy = CompositingStrategy.Auto
            renderEffect = null
            colorFilter = null
            shadowElevation = 0f
            outsets = bounds.outsets(outsets, this)
        }
        .then(SurfaceIsolationElement(bounds, layerBlock))
        .then(
            RenderBackdropElement(
                backdrop,
                ShapeProvider(shape),
                render,
                backgroundAlpha,
                insets,
                layerBlock,
                exportedBackdrop,
                bounds,
            ),
        )
}

private data class RenderBackdropElement(
    val backdrop: Backdrop,
    val shape: ShapeProvider,
    val render: (BackdropRenderScope.() -> Unit)?,
    val alpha: () -> Float,
    val insets: (() -> BackdropInsets)?,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exported: LayerBackdrop?,
    val bounds: SurfaceBounds,
) : ModifierNodeElement<RenderBackdropNode>() {
    override fun create(): RenderBackdropNode = RenderBackdropNode(this)

    override fun update(node: RenderBackdropNode) {
        node.update(this)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "renderBackdrop"
        properties["render"] = render
        properties["backgroundAlpha"] = alpha
    }
}

private class RenderBackdropNode(var parameters: RenderBackdropElement) :
    Modifier.Node(),
    DrawModifierNode,
    LayoutModifierNode,
    GlobalPositionAwareModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {
    private val renderer = BackdropGraphRenderer { requireGraphicsContext() }
    private val sourceRenderer = BackdropSourceRenderer { requireGraphicsContext() }
    private var coordinates: LayoutCoordinates? = null
    private var renderScope: BackdropRenderScope? = null
    private var geometry: Geometry? = null

    private data class Geometry(
        val size: Size,
        val density: Float,
        val fontScale: Float,
        val direction: LayoutDirection,
    )

    private var epoch = -1
    private val contentPath = Path()
    private val presentationLayers = mutableMapOf<Int, GraphicsLayer>()

    fun update(parameters: RenderBackdropElement) {
        if (this.parameters.exported !== parameters.exported)
            this.parameters.exported?.layerCoordinates = null
        this.parameters = parameters
        geometry?.let(::resolve)
        invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun onAttach() {
        epoch = currentValueOf(LocalBackdropRenderEpoch)
    }

    override fun onObservedReadsChanged() {
        geometry?.let(::resolve)
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        val density = requireDensity()
        val next =
            Geometry(
                Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                density.density,
                density.fontScale,
                requireLayoutDirection(),
            )
        if (geometry != next || renderScope == null) {
            geometry = next
            resolve(next)
        }
        parameters.exported?.layerCoordinates = coordinates
        invalidateDraw()
    }

    private fun resolve(draw: Geometry) {
        renderScope = null
        observeReads {
            val scope =
                BackdropRenderScope(
                    draw.density,
                    draw.fontScale,
                    draw.size,
                    parameters.shape.innerShape,
                    draw.direction,
                    resolveStyle = { currentValueOf(LocalBackdropStyle) },
                )
            val producer = parameters.render ?: currentValueOf(LocalBackdropRenderStyle).render
            scope.producer()
            val insets =
                (parameters.insets
                    ?: scope.styleInsets
                    ?: currentValueOf(LocalBackdropRenderStyle).insets)()
            val request =
                with(scope.density) {
                    Rect(
                        -insets.left.toPx(),
                        -insets.top.toPx(),
                        scope.size.width + insets.right.toPx(),
                        scope.size.height + insets.bottom.toPx(),
                    )
                }
            renderer.resolve(scope, request)
            renderScope = scope
            val selected = setOf(scope.current.index, scope.exportedImage?.index)
            presentationLayers.keys
                .toList()
                .filter { it !in selected }
                .forEach {
                    requireGraphicsContext().releaseGraphicsLayer(presentationLayers.remove(it)!!)
                }
            parameters.bounds.effects = renderer.extension
        }
    }

    override fun ContentDrawScope.draw() {
        val newEpoch = currentValueOf(LocalBackdropRenderEpoch)
        if (epoch != newEpoch) {
            renderer.refresh()
            presentationLayers.values.forEach(requireGraphicsContext()::releaseGraphicsLayer)
            presentationLayers.clear()
            sourceRenderer.release()
            epoch = newEpoch
        }
        val geometry = Geometry(size, density, fontScale, layoutDirection)
        if (this@RenderBackdropNode.geometry != geometry) {
            this@RenderBackdropNode.geometry = geometry
            renderScope = null
        }
        val alpha = parameters.alpha()
        requireAlpha(alpha)
        // Resolve on demand; producer observation covers updates while the graph is invisible.
        if (renderScope == null) resolve(geometry)
        val scope = renderScope!!
        val window = currentValueOf(LocalWindowInfo)
        val entries =
            currentValueOf(LocalInteractionRegistry)
                ?.lights
                ?.filter { it.window === window }
                .orEmpty()
        renderer.interactionFrame.publishInteraction(entries, window, coordinates)
        renderer.beginFrame()
        sourceRenderer.beginFrame()
        try {
            val drawSource: DrawScope.(Rect) -> Unit = { request ->
                sourceRenderer.draw(this, scope.size, request) {
                    with(parameters.backdrop) {
                        drawBackdrop(scope.density, coordinates, parameters.layerBlock)
                    }
                }
            }
            val recordedPresentations = mutableSetOf<Int>()
            fun DrawScope.drawBackground(index: Int = scope.current.index) {
                if (alpha == 0f) return
                renderer.prepare(this, index, drawSource)
                val extension = renderer.extension
                val layer =
                    presentationLayers.getOrPut(index) {
                        requireGraphicsContext().createGraphicsLayer()
                    }
                layer.topLeft = IntOffset(-extension.left.toInt(), -extension.top.toInt())
                layer.alpha = alpha
                if (recordedPresentations.add(index))
                    layer.record(
                        Density(density, fontScale),
                        layoutDirection,
                        IntSize(
                            ceil(scope.size.width + extension.left + extension.right).toInt(),
                            ceil(scope.size.height + extension.top + extension.bottom).toInt(),
                        ),
                    ) {
                        drawContext.canvas.save()
                        drawContext.canvas.translate(extension.left, extension.top)
                        renderer.draw(this, index, drawSource)
                        drawContext.canvas.restore()
                    }
                drawLayer(layer)
            }

            drawBackground()
            val outline = parameters.shape.shape.createOutline(size, layoutDirection, this)
            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, contentPath)
            drawContent()
            canvas.restore()
            scope.foregroundImage?.let { renderer.draw(this, it.index, drawSource) }
            parameters.exported?.let { exported ->
                val extension = renderer.extension
                val visible =
                    renderer.outputBounds(scope.exportedImage?.index ?: scope.current.index)
                val offset = Offset(extension.left, extension.top)
                exported.layerOffset = offset
                // Exported content becomes a finite pixel source for the next consumer. Round
                // outward so fractional coverage retains every touched edge pixel.
                val bounds = visible.translate(offset)
                exported.sourceBounds =
                    if (bounds.isEmpty) Rect.Zero
                    else
                        Rect(
                            floor(bounds.left),
                            floor(bounds.top),
                            ceil(bounds.right),
                            ceil(bounds.bottom),
                        )
                recordLayer(
                    exported.graphicsLayer,
                    IntSize(
                        ceil(size.width + extension.left + extension.right).toInt(),
                        ceil(size.height + extension.top + extension.bottom).toInt(),
                    ),
                ) {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(extension.left, extension.top)
                    drawBackground(scope.exportedImage?.index ?: scope.current.index)
                    drawContext.canvas.restore()
                }
            }
        } finally {
            sourceRenderer.endFrame()
        }
    }

    override fun onDetach() {
        presentationLayers.values.forEach(requireGraphicsContext()::releaseGraphicsLayer)
        presentationLayers.clear()
        renderer.release()
        sourceRenderer.release()
        parameters.exported?.layerCoordinates = null
        parameters.exported?.sourceBounds = null
        coordinates = null
        renderScope = null
        geometry = null
    }
}
