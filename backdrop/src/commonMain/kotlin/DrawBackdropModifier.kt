package top.ltfan.backdrop

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightElement
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.internal.SurfaceBounds
import top.ltfan.backdrop.internal.clipToExtendedShape
import top.ltfan.backdrop.internal.recordLayer
import top.ltfan.backdrop.internal.surfaceRenderEffect
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.InnerShadowElement
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.ShadowElement

/** Invalidates Backdrop-owned rendering resources without replacing composition. */
internal val LocalBackdropRenderEpoch: ProvidableCompositionLocal<Int> = compositionLocalOf { 0 }

/**
 * Draws into a surface, in the surface's own coordinate space, and reports how far its effects
 * reach past it. Translate by [BackdropExtension.originInNode] to cover all of that area.
 */
public typealias BackdropDrawCallback = DrawScope.(BackdropRoom) -> Unit

/** Draws the backdrop itself, through the [drawBackdrop] lambda it is handed. */
public typealias BackdropEffectDrawCallback =
    DrawScope.(drawBackdrop: DrawScope.() -> Unit, room: BackdropRoom) -> Unit

private val DefaultOnDrawBackdrop: BackdropEffectDrawCallback = { drawBackdrop, _ ->
    drawBackdrop()
}

/**
 * Draws the backdrop through [effects], inheriting [LocalBackdropStyle]'s effect chain when null.
 * [BackdropStyle.NoEffects] explicitly selects an empty chain. Decorative styles are drawn by
 * [drawBackdrop].
 */
public fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: (BackdropEffectScope.() -> Unit)? = null,
    insets: (() -> BackdropInsets)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: BackdropDrawCallback? = null,
    onDrawBackdrop: BackdropEffectDrawCallback = DefaultOnDrawBackdrop,
    onDrawSurface: BackdropDrawCallback? = null,
    onDrawFront: BackdropDrawCallback? = null,
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    val surfaceBounds = SurfaceBounds()
    return this.graphicsLayer {
            layerBlock?.invoke(this)
            compositingStrategy = CompositingStrategy.Offscreen
            outsets = surfaceBounds.outsets(outsets, this)
            renderEffect = surfaceRenderEffect(renderEffect, size, outsets, this)
        }
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                surfaceBounds = surfaceBounds,
                effects = effects,
                insets = insets,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
            )
        )
}

/**
 * Draws a backdrop with visual defaults from [LocalBackdropStyle] at this modifier's UI position.
 * Null producers inherit their corresponding default. Explicit producers replace that default;
 * return [Highlight.None], [Shadow.None], or [InnerShadow.None] to disable a decoration, and use
 * [BackdropStyle.NoEffects] for an empty effect chain. Producers read snapshot state during drawing
 * or effect observation.
 */
public fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: (BackdropEffectScope.() -> Unit)? = null,
    insets: (() -> BackdropInsets)? = null,
    highlight: (() -> Highlight)? = null,
    shadow: (() -> Shadow)? = null,
    innerShadow: (() -> InnerShadow)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: BackdropDrawCallback? = null,
    onDrawBackdrop: BackdropEffectDrawCallback = DefaultOnDrawBackdrop,
    onDrawSurface: BackdropDrawCallback? = null,
    onDrawFront: BackdropDrawCallback? = null,
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    val surfaceBounds = SurfaceBounds()
    return this.graphicsLayer {
            layerBlock?.invoke(this)
            compositingStrategy = CompositingStrategy.Offscreen
            outsets = surfaceBounds.outsets(outsets, this)
            renderEffect = surfaceRenderEffect(renderEffect, size, outsets, this)
        }
        .then(InnerShadowElement(shapeProvider = shapeProvider, shadow = innerShadow))
        .then(
            ShadowElement(
                shapeProvider = shapeProvider,
                shadow = shadow,
                surfaceBounds = surfaceBounds,
            )
        )
        .then(HighlightElement(shapeProvider = shapeProvider, highlight = highlight))
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                surfaceBounds = surfaceBounds,
                effects = effects,
                insets = insets,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val surfaceBounds: SurfaceBounds,
    val effects: (BackdropEffectScope.() -> Unit)?,
    val insets: (() -> BackdropInsets)?,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: BackdropDrawCallback?,
    val onDrawBackdrop: BackdropEffectDrawCallback,
    val onDrawSurface: BackdropDrawCallback?,
    val onDrawFront: BackdropDrawCallback?,
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            surfaceBounds = surfaceBounds,
            effects = effects,
            insets = insets,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.surfaceBounds = surfaceBounds
        node.effects = effects
        node.insets = insets
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["insets"] = insets
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (insets != other.insets) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + insets.hashCode()
        result = 31 * result + layerBlock.hashCode()
        result = 31 * result + exportedBackdrop.hashCode()
        result = 31 * result + onDrawBehind.hashCode()
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + onDrawSurface.hashCode()
        result = 31 * result + onDrawFront.hashCode()
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var surfaceBounds: SurfaceBounds,
    var effects: (BackdropEffectScope.() -> Unit)?,
    var insets: (() -> BackdropInsets)?,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: BackdropDrawCallback?,
    var onDrawBackdrop: BackdropEffectDrawCallback,
    var onDrawSurface: BackdropDrawCallback?,
    var onDrawFront: BackdropDrawCallback?,
) :
    LayoutModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode,
    Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape
                get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    /** Reused across draws for rounded surface shapes, so clipping allocates nothing. */
    private val surfaceClipPath = Path()

    private var renderEpoch = 0

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    /**
     * What the caller asked to cover beyond the surface, in pixels. It is the design intent, so it
     * decides what the surface's own drawing may cover.
     */
    private var visible by mutableStateOf(BackdropExtension.None, neverEqualPolicy())

    /**
     * What the effect layer gets, in pixels: [visible] plus the room the effect chain needs, such
     * as a blur reading the pixels it blends. That extra room is sampling headroom, so it sits
     * outside the area the caller asked for and never widens what is drawn.
     */
    private var extension by mutableStateOf(BackdropExtension.None, neverEqualPolicy())

    /**
     * Draws the backdrop into the effect layer. The layer starts where the extension does, so the
     * drawing is shifted to the surface's own origin, which is the space callbacks get.
     */
    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val extension = extension
        val room = BackdropRoom(effectScope.size, extension)

        canvas.save()
        if (!extension.isZero) {
            canvas.translate(extension.left, extension.top)
        }
        onDrawBackdrop(
            {
                with(backdrop) {
                    drawBackdrop(
                        density = effectScope,
                        coordinates = layoutCoordinates,
                        layerBlock = layerBlock,
                    )
                }
            },
            room,
        )
        canvas.restore()
    }

    /**
     * Records and draws the effect layer, which covers the surface and its extension. [topLeft] is
     * where the layer's own origin lands: before the surface when the surface draws itself, and at
     * the origin when the layer is recorded into an exported backdrop, whose space starts where the
     * extension does.
     */
    private fun DrawScope.drawBackdropLayer(topLeft: IntOffset) {
        val layer = graphicsLayer ?: return
        val extension = extension

        recordLayer(layer, size = layerSize(extension), block = recordBackdropBlock)

        layer.topLeft = topLeft
        drawLayer(layer)
    }

    /** Runs [block] with the canvas at the surface's origin, whatever extension surrounds it. */
    private inline fun DrawScope.inNodeSpace(block: DrawScope.() -> Unit) {
        val extension = extension
        if (extension.isZero) {
            block()
            return
        }

        val canvas = drawContext.canvas
        canvas.save()
        canvas.translate(extension.left, extension.top)
        block()
        canvas.restore()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(IntOffset.Zero) }
    }

    private fun DrawScope.clipToSurfaceShape(nodeSize: Size, block: DrawScope.() -> Unit) {
        clipToExtendedShape(
            shapeProvider.shape,
            nodeSize,
            BackdropExtension.None,
            surfaceClipPath,
            block,
        )
    }

    private fun DrawScope.clipToSurfaceRegion(
        extension: BackdropExtension,
        block: DrawScope.() -> Unit,
    ) {
        clipToExtendedShape(
            shapeProvider.shape,
            effectScope.size,
            extension,
            surfaceClipPath,
            block,
        )
    }

    override fun ContentDrawScope.draw() {
        if (effectScope.update(this)) {
            observeEffects()
        }

        val extension = extension
        val visible = visible
        val layerTopLeft = IntOffset(-extension.left.toInt(), -extension.top.toInt())

        // The canvas already sits at the surface's origin here, which is the space callbacks get.
        val room = BackdropRoom(effectScope.size, extension)
        clipToSurfaceRegion(visible) {
            onDrawBehind?.invoke(this, room)
            drawBackdropLayer(layerTopLeft)
            onDrawSurface?.invoke(this, room)
        }
        clipToSurfaceShape(effectScope.size) { this@draw.drawContent() }
        clipToSurfaceRegion(visible) { onDrawFront?.invoke(this, room) }

        exportedBackdrop?.let { exported ->
            exported.layerOffset = Offset(extension.left, extension.top)
            recordLayer(exported.graphicsLayer, size = layerSize(extension)) {
                // The layer starts where the extension does, so draw in the surface's own space.
                inNodeSpace {
                    clipToSurfaceRegion(visible) {
                        onDrawBehind?.invoke(this, room)
                        drawBackdropLayer(layerTopLeft)
                        onDrawSurface?.invoke(this, room)
                        onDrawFront?.invoke(this, room)
                    }
                }
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            val density = requireDensity()
            effectScope.density = density.density
            effectScope.fontScale = density.fontScale
            effectScope.layoutDirection = requireLayoutDirection()
            effectScope.size =
                Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            observeEffects()
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads {
            val epoch = currentValueOf(LocalBackdropRenderEpoch)
            if (epoch != renderEpoch) {
                renderEpoch = epoch
                replaceRenderingLayers()
            }
            updateEffects()
        }
    }

    private fun updateEffects() {
        if (!effectScope.size.width.isFinite() || !effectScope.size.height.isFinite()) return
        val style = currentValueOf(LocalBackdropStyle)
        val requested = (insets ?: style.insets)()

        effectScope.resolveEffects(effects ?: style.effects, requested)
        resolveExtension(requested)
        graphicsLayer?.renderEffect = effectScope.renderEffect
    }

    private fun resolveExtension(insets: BackdropInsets) {
        val cover = effectScope.cover
        val requested =
            BackdropExtension(
                left = sideOf(insets.left) + sideOf(cover.left),
                top = sideOf(insets.top) + sideOf(cover.top),
                right = sideOf(insets.right) + sideOf(cover.right),
                bottom = sideOf(insets.bottom) + sideOf(cover.bottom),
            )
        visible = requested
        extension = effectScope.extension
        surfaceBounds.effects = effectScope.extension
    }

    private fun sideOf(inset: Dp): Float = with(effectScope) { inset.toPx() }

    private fun layerSize(extension: BackdropExtension): IntSize {
        val size = effectScope.size
        return IntSize(
            ceil(size.width + extension.left + extension.right).toInt(),
            ceil(size.height + extension.top + extension.bottom).toInt(),
        )
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()
        renderEpoch = currentValueOf(LocalBackdropRenderEpoch)
        observeEffects()
    }

    /**
     * Replaces the rendering layers Backdrop owns. Must run outside the measure phase, since it
     * requests a re-measure and a redraw.
     */
    private fun replaceRenderingLayers() {
        val context = requireGraphicsContext()
        val oldEffect = graphicsLayer
        graphicsLayer = context.createGraphicsLayer()
        oldEffect?.let(context::releaseGraphicsLayer)
        invalidateDraw()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        effectScope.reset()
        visible = BackdropExtension.None
        extension = BackdropExtension.None
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
        exportedBackdrop?.layerOffset = Offset.Zero
    }
}
