package top.ltfan.backdrop

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
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
import androidx.compose.ui.node.DelegatingNode
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
import top.ltfan.backdrop.interaction.InteractionRegistry
import top.ltfan.backdrop.interaction.LocalInteractionRegistry
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
internal fun Modifier.backdropDeclaration(
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
        name = "drawBackdrop"
        properties["render"] = render
        properties["backgroundAlpha"] = alpha
    }
}

private class RenderBackdropNode(var parameters: RenderBackdropElement) :
    DelegatingNode(),
    DrawModifierNode,
    LayoutModifierNode,
    GlobalPositionAwareModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {
    private val renderer = BackdropRenderer { requireGraphicsContext() }
    private val sourceRenderer = BackdropSourceRenderer { requireGraphicsContext() }
    private var coordinates: LayoutCoordinates? = null
    private var renderScope: BackdropRenderScope? = null
    private var lightRegistry: InteractionRegistry? = null
    private var lightWindow: Any? = null
    private var lightsPresent: State<Boolean>? = null
    private var geometry: Geometry? = null
    private var graphDirty = true
    private var graphFrame = 0L
    private var backgroundVisible = false
    private val backgroundReads = delegate(GraphReads())
    private val foregroundReads = delegate(GraphReads())
    private val exportReads = delegate(GraphReads())

    private val resolutionReads = delegate(ResolutionReads())

    private inner class ResolutionReads : Modifier.Node(), ObserverModifierNode {
        override fun onObservedReadsChanged() {
            renderScope?.let(::resolveImages)
            this@RenderBackdropNode.invalidateDraw()
        }

        fun observe(block: () -> Unit) {
            observeReads(block)
        }
    }

    private inner class GraphReads : Modifier.Node(), ObserverModifierNode {
        private var observing = false

        override fun onObservedReadsChanged() {
            graphDirty = true
            this@RenderBackdropNode.invalidateDraw()
        }

        fun observe(block: () -> Unit) {
            observing = true
            observeReads(block)
        }

        fun clear() {
            if (!observing) return
            observeReads {}
            observing = false
        }
    }

    private class PresentationLayer(val layer: GraphicsLayer, var recordedFrame: Long = -1L)

    private data class Geometry(
        val size: Size,
        val density: Float,
        val fontScale: Float,
        val direction: LayoutDirection,
    )

    private var epoch = -1
    private val contentPath = Path()
    private val presentationLayers = mutableMapOf<Int, PresentationLayer>()

    fun update(parameters: RenderBackdropElement) {
        if (this.parameters.exported !== parameters.exported)
            this.parameters.exported?.layerCoordinates = null
        this.parameters = parameters
        graphDirty = true
        geometry?.let(::resolve)
        invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        if (!isLookingAhead) {
            updateGeometry(
                Geometry(
                    Size(placeable.width.toFloat(), placeable.height.toFloat()),
                    density,
                    fontScale,
                    layoutDirection,
                ),
            )
        }
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun onAttach() {
        epoch = currentValueOf(LocalBackdropRenderEpoch)
        graphDirty = true
    }

    override fun onObservedReadsChanged() {
        geometry?.let(::resolve)
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        graphDirty = true
        val density = requireDensity()
        val next =
            Geometry(
                Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
                density.density,
                density.fontScale,
                requireLayoutDirection(),
            )
        updateGeometry(next)
        parameters.exported?.layerCoordinates = coordinates
        invalidateDraw()
    }

    private fun updateGeometry(next: Geometry) {
        if (geometry == next && renderScope != null) return
        val previous = geometry
        val scope = renderScope
        geometry = next
        if (scope != null && !scope.readsSize && previous?.copy(size = next.size) == next) {
            scope.updateSize(next.size)
            resolveImages(scope)
        } else {
            resolve(next)
        }
    }

    private fun resolve(draw: Geometry) {
        renderScope = null
        graphDirty = true
        observeReads {
            val scope =
                BackdropRenderScope(
                    draw.density,
                    draw.fontScale,
                    draw.size,
                    parameters.shape.innerShape,
                    draw.direction,
                    resolveStyle = { currentValueOf(LocalBackdropStyle) },
                    interactionLightsPresent = ::hasInteractionLights,
                )
            val producer = parameters.render ?: currentValueOf(LocalBackdropRenderStyle).render
            scope.producer()
            scope.finishDeclaration()
            renderScope = scope
        }
        resolveImages(checkNotNull(renderScope))
    }

    private fun resolveImages(scope: BackdropRenderScope) {
        graphDirty = true
        resolutionReads.observe {
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
            val selected = setOf(scope.current.index, scope.exportedImage?.index)
            presentationLayers.keys
                .toList()
                .filter { it !in selected }
                .forEach {
                    requireGraphicsContext()
                        .releaseGraphicsLayer(presentationLayers.remove(it)!!.layer)
                }
            parameters.bounds.effects = renderer.extension
        }
    }

    private fun hasInteractionLights(): Boolean {
        val registry = currentValueOf(LocalInteractionRegistry)
        val window = currentValueOf(LocalWindowInfo)
        if (lightsPresent == null || registry !== lightRegistry || window !== lightWindow) {
            lightRegistry = registry
            lightWindow = window
            lightsPresent =
                derivedStateOf(structuralEqualityPolicy()) {
                    registry?.lights?.any { it.window === window } == true
                }
        }
        return lightsPresent!!.value
    }

    override fun ContentDrawScope.draw() {
        val newEpoch = currentValueOf(LocalBackdropRenderEpoch)
        if (epoch != newEpoch) {
            renderer.refresh()
            releasePresentationLayers()
            sourceRenderer.release()
            epoch = newEpoch
            graphDirty = true
        }
        val geometry = Geometry(size, density, fontScale, layoutDirection)
        if (this@RenderBackdropNode.geometry != geometry) {
            updateGeometry(geometry)
        }
        val alpha = parameters.alpha()
        requireAlpha(alpha)
        if (backgroundVisible != (alpha > 0f)) {
            backgroundVisible = alpha > 0f
            graphDirty = true
        }
        // Resolve on demand; producer observation covers updates while the graph is invisible.
        if (renderScope == null) resolve(geometry)
        val scope = renderScope!!
        if (alpha == 1f) releasePresentationLayers()
        val window = currentValueOf(LocalWindowInfo)
        val entries =
            currentValueOf(LocalInteractionRegistry)
                ?.lights
                ?.filter { it.window === window }
                .orEmpty()
        val interaction = renderer.interactionFrame
        if (
            interaction.receiverWindow !== window ||
                interaction.receiverCoordinates !== coordinates ||
                interaction.interactionLights != entries
        )
            graphDirty = true
        interaction.publishInteraction(entries, window, coordinates)
        val recordGraph = graphDirty
        if (recordGraph) {
            graphDirty = false
            graphFrame++
            renderer.beginFrame()
            sourceRenderer.beginFrame()
        }
        try {
            val drawSource: DrawScope.(Rect) -> Unit = { request ->
                sourceRenderer.draw(this, scope.size, request) {
                    with(parameters.backdrop) {
                        drawBackdrop(scope.density, coordinates, parameters.layerBlock)
                    }
                }
            }
            fun DrawScope.prepareBackground(index: Int = scope.current.index) {
                if (alpha == 0f) return
                if (alpha == 1f) return
                val extension = renderer.extension
                val presentation =
                    presentationLayers.getOrPut(index) {
                        PresentationLayer(requireGraphicsContext().createGraphicsLayer())
                    }
                val layer = presentation.layer
                layer.topLeft = IntOffset(-extension.left.toInt(), -extension.top.toInt())
                layer.alpha = alpha
                if (presentation.recordedFrame != graphFrame) {
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
                    presentation.recordedFrame = graphFrame
                }
            }

            fun DrawScope.drawBackground(index: Int = scope.current.index) {
                if (alpha == 0f) return
                if (alpha == 1f) renderer.draw(this, index, drawSource)
                else drawLayer(presentationLayers.getValue(index).layer)
            }

            // Record selected graph images before entering presentation or export recording.
            if (recordGraph) {
                if (alpha > 0f)
                    backgroundReads.observe { renderer.prepare(this, source = drawSource) }
                else backgroundReads.clear()
            }
            prepareBackground()
            drawBackground()
            val outline = parameters.shape.shape.createOutline(size, layoutDirection, this)
            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, contentPath)
            drawContent()
            canvas.restore()
            if (recordGraph) {
                val foreground = scope.foregroundImage
                if (foreground != null)
                    foregroundReads.observe { renderer.prepare(this, foreground.index, drawSource) }
                else foregroundReads.clear()
            }
            scope.foregroundImage?.let { renderer.draw(this, it.index, drawSource) }
            if (recordGraph) {
                if (parameters.exported != null && alpha > 0f)
                    exportReads.observe {
                        renderer.prepare(
                            this,
                            scope.exportedImage?.index ?: scope.current.index,
                            drawSource,
                        )
                    }
                else exportReads.clear()
            }
            parameters.exported?.let { exported ->
                val image = scope.exportedImage?.index ?: scope.current.index
                prepareBackground(image)
                val extension = renderer.extension
                val visible = renderer.outputBounds(image)
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
                    drawBackground(image)
                    drawContext.canvas.restore()
                }
            }
        } catch (failure: Throwable) {
            graphDirty = true
            throw failure
        } finally {
            if (recordGraph) sourceRenderer.endFrame()
        }
    }

    private fun releasePresentationLayers() {
        if (presentationLayers.isEmpty()) return
        val context = requireGraphicsContext()
        presentationLayers.values.forEach { context.releaseGraphicsLayer(it.layer) }
        presentationLayers.clear()
    }

    override fun onDetach() {
        releasePresentationLayers()
        renderer.close()
        sourceRenderer.release()
        parameters.exported?.layerCoordinates = null
        parameters.exported?.sourceBounds = null
        coordinates = null
        renderScope = null
        geometry = null
        lightsPresent = null
        lightRegistry = null
        lightWindow = null
    }
}
