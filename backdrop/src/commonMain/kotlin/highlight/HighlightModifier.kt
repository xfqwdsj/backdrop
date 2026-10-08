package top.ltfan.backdrop.highlight

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceAtMost
import kotlin.math.ceil
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropExtension
import top.ltfan.backdrop.LocalBackdropRenderEpoch
import top.ltfan.backdrop.LocalBackdropStyle
import top.ltfan.backdrop.RuntimeShaderCacheImpl
import top.ltfan.backdrop.internal.BackdropSourceRenderer
import top.ltfan.backdrop.internal.EnvironmentHighlightShaderString
import top.ltfan.backdrop.internal.GenericEnvironmentGeometry
import top.ltfan.backdrop.internal.RuntimeShaderEffect
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.internal.SurfaceBounds
import top.ltfan.backdrop.internal.blur
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.environmentHighlightSamplingOutset
import top.ltfan.backdrop.internal.genericEnvironmentHighlightShader
import top.ltfan.backdrop.internal.setHdrColor
import top.ltfan.backdrop.internal.setRuntimeShader
import top.ltfan.backdrop.isRuntimeShaderSupported

internal class HighlightElement(
    val shapeProvider: ShapeProvider,
    val highlight: (() -> Highlight)?,
    val backdrop: Backdrop,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val surfaceBounds: SurfaceBounds,
) : ModifierNodeElement<HighlightNode>() {

    override fun create(): HighlightNode {
        return HighlightNode(shapeProvider, highlight, backdrop, layerBlock, surfaceBounds)
    }

    override fun update(node: HighlightNode) {
        node.shapeProvider = shapeProvider
        node.highlight = highlight
        node.backdrop = backdrop
        node.layerBlock = layerBlock
        node.surfaceBounds = surfaceBounds
        node.onObservedReadsChanged()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "highlight"
        properties["shapeProvider"] = shapeProvider
        properties["highlight"] = highlight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HighlightElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (highlight != other.highlight) return false
        if (backdrop != other.backdrop) return false
        if (layerBlock != other.layerBlock) return false
        if (surfaceBounds != other.surfaceBounds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + highlight.hashCode()
        result = 31 * result + backdrop.hashCode()
        result = 31 * result + layerBlock.hashCode()
        result = 31 * result + surfaceBounds.hashCode()
        return result
    }
}

internal class HighlightNode(
    var shapeProvider: ShapeProvider,
    var highlight: (() -> Highlight)?,
    var backdrop: Backdrop,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var surfaceBounds: SurfaceBounds,
) :
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    GlobalPositionAwareModifierNode,
    Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var highlightLayer: GraphicsLayer? = null
    private val sourceRenderer = BackdropSourceRenderer { requireGraphicsContext() }
    private var environmentLayer: GraphicsLayer? = null
    private var environmentOutline: Outline? = null
    private var environmentGeometry: GenericEnvironmentGeometry? = null
    private val environmentClipPath = Path()
    private val environmentShaderCache = RuntimeShaderCacheImpl()
    private var coordinates: LayoutCoordinates? = null
    private var surfaceSize = Size.Zero
    private var renderEpoch = 0

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun ContentDrawScope.draw() {
        val highlight = (highlight ?: currentValueOf(LocalBackdropStyle).highlight)()
        if (highlight !is Highlight.Config) {
            releaseDrawingLayers()
            return drawContent()
        }
        if (highlight.width.value <= 0f || size.minDimension <= 0f) {
            releaseDrawingLayers()
            return drawContent()
        }
        if (surfaceSize != size) {
            surfaceSize = size
            onObservedReadsChanged()
        }
        val epoch = currentValueOf(LocalBackdropRenderEpoch)
        if (epoch != renderEpoch) {
            renderEpoch = epoch
            releaseDrawingLayers()
        }
        drawContent()
        val outline = shapeProvider.shape.createOutline(size, layoutDirection, this)
        if (highlight.style == HighlightStyle.None) {
            releaseStaticLayer()
        } else {
            drawStatic(highlight, outline)
        }
        drawEnvironment(highlight, outline)
    }

    private fun DrawScope.drawStatic(highlight: Highlight.Config, outline: Outline) {
        val layer =
            highlightLayer
                ?: requireGraphicsContext().createGraphicsLayer().also {
                    highlightLayer = it
                }
        val safeSize = IntSize(ceil(size.width).toInt() + 2, ceil(size.height).toInt() + 2)
        val path =
            if (outline is Outline.Rounded) {
                clipPath ?: Path().also { clipPath = it }
            } else null
        configurePaint(highlight)
        layer.alpha = highlight.alpha
        layer.blendMode = highlight.style.blendMode
        layer.record(safeSize) {
            translate(1f, 1f) {
                val canvas = drawContext.canvas
                canvas.save()
                canvas.clipOutline(outline, path)
                canvas.drawOutline(outline, paint)
                canvas.restore()
            }
        }
        translate(-1f, -1f) { drawLayer(layer) }
    }

    override fun onAttach() {
        renderEpoch = currentValueOf(LocalBackdropRenderEpoch)
        onObservedReadsChanged()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        this.coordinates = coordinates
        surfaceSize = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        onObservedReadsChanged()
    }

    override fun onDensityChange() {
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads {
            val config = (highlight ?: currentValueOf(LocalBackdropStyle).highlight)()
            val environment = (config as? Highlight.Config)?.environment
            val room =
                with(requireDensity()) {
                    if (
                        config is Highlight.Config &&
                            environment != null &&
                            environment.strength > 0f &&
                            config.width.value > 0f &&
                            surfaceSize.minDimension > 0f &&
                            isRuntimeShaderSupported()
                    ) {
                        environmentHighlightSamplingOutset(environment.sampleDistance.toPx())
                    } else 0f
                }
            surfaceBounds.environment = BackdropExtension(room, room, room, room)
        }
        invalidateDraw()
    }

    override fun onDetach() {
        releaseDrawingLayers()
        coordinates = null
    }

    private fun releaseEnvironmentLayer() {
        sourceRenderer.release()
        environmentLayer?.let {
            it.renderEffect = null
            requireGraphicsContext().releaseGraphicsLayer(it)
        }
        environmentLayer = null
        environmentShaderCache.clear()
        environmentOutline = null
        environmentGeometry = null
        environmentClipPath.rewind()
    }

    private fun releaseStaticLayer() {
        highlightLayer?.let {
            requireGraphicsContext().releaseGraphicsLayer(it)
            paint.setRuntimeShader(null)
            paint.blur(0f)
        }
        highlightLayer = null
        runtimeShaderCache.clear()
        clipPath = null
    }

    private fun releaseDrawingLayers() {
        releaseStaticLayer()
        releaseEnvironmentLayer()
    }

    private fun DrawScope.drawEnvironment(highlight: Highlight.Config, outline: Outline) {
        val environment = highlight.environment
        if (environment == null || environment.strength <= 0f || !isRuntimeShaderSupported()) {
            releaseEnvironmentLayer()
            return
        }
        val bounds = outline.bounds
        val rounded = (outline as? Outline.Rounded)?.roundRect
        val corners = rounded?.let {
            listOf(
                it.topLeftCornerRadius,
                it.topRightCornerRadius,
                it.bottomRightCornerRadius,
                it.bottomLeftCornerRadius,
            )
        }
        val radii = corners?.let { FloatArray(4) { index -> it[index].x } }
        val analytic =
            outline is Outline.Rectangle ||
                (corners != null &&
                    corners.all { it.x == it.y && it.x.isFinite() && it.x >= 0f } &&
                    radii!![0] + radii[1] <= bounds.width &&
                    radii[3] + radii[2] <= bounds.width &&
                    radii[0] + radii[3] <= bounds.height &&
                    radii[1] + radii[2] <= bounds.height)
        val geometry =
            if (analytic) null
            else {
                if (environmentOutline !== outline) {
                    val path =
                        when (outline) {
                            is Outline.Generic -> outline.path
                            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                            is Outline.Rectangle ->
                                error("Rectangle uses analytic boundary geometry")
                        }
                    val nextGeometry = GenericEnvironmentGeometry(path)
                    if (nextGeometry.segmentCount != environmentGeometry?.segmentCount) {
                        environmentShaderCache.clear()
                    }
                    environmentGeometry = nextGeometry
                    environmentOutline = outline
                }
                environmentGeometry!!
            }
        if (bounds.isEmpty || geometry?.segmentCount == 0) {
            releaseEnvironmentLayer()
            return
        }
        val width = ceil(highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f))
        val distance = environment.sampleDistance.toPx()
        val room = environmentHighlightSamplingOutset(distance)
        val layerSize =
            IntSize(ceil(size.width + room * 2f).toInt(), ceil(size.height + room * 2f).toInt())
        val shader =
            if (geometry == null) {
                environmentShaderCache
                    .obtainRuntimeShader(
                        "EnvironmentHighlight",
                        EnvironmentHighlightShaderString,
                    )
                    .also {
                        it.setFloatUniform("size", bounds.width, bounds.height)
                        it.setFloatUniform("origin", room + bounds.left, room + bounds.top)
                        it.setFloatUniform("cornerRadii", radii ?: floatArrayOf(0f, 0f, 0f, 0f))
                    }
            } else {
                environmentShaderCache
                    .obtainRuntimeShader(
                        "GenericEnvironmentHighlight",
                        genericEnvironmentHighlightShader(geometry.segmentCount),
                    )
                    .also {
                        it.setFloatUniform("origin", room, room)
                        it.setFloatUniform("segments", geometry.segments)
                        it.setFloatUniform("geometryScale", geometry.coordinateScale)
                    }
            }
        shader.setFloatUniform("width", width)
        shader.setFloatUniform("sampleDistance", distance)
        shader.setFloatUniform("strength", environment.strength)
        shader.setFloatUniform("threshold", environment.threshold)
        val layer =
            environmentLayer
                ?: requireGraphicsContext()
                    .createGraphicsLayer()
                    .apply {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .also { environmentLayer = it }
        layer.alpha = highlight.alpha
        layer.blendMode = environment.blendMode
        layer.renderEffect =
            RuntimeShaderEffect(
                shader,
                "content",
                Rect(0f, 0f, layerSize.width.toFloat(), layerSize.height.toFloat()),
            )
        val density: Density = this
        val sourceCoordinates = if (backdrop.isCoordinatesDependent) coordinates else null
        val sourceSize = size
        sourceRenderer.beginFrame()
        try {
            layer.record(layerSize) {
                translate(room, room) {
                    sourceRenderer.draw(
                        this,
                        sourceSize,
                        Rect(-room, -room, sourceSize.width + room, sourceSize.height + room),
                    ) {
                        with(backdrop) { drawBackdrop(density, sourceCoordinates, layerBlock) }
                    }
                }
            }
        } finally {
            sourceRenderer.endFrame()
        }
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipOutline(outline, environmentClipPath)
        translate(-room, -room) { drawLayer(layer) }
        canvas.restore()
    }

    private fun DrawScope.configurePaint(highlight: Highlight.Config) {
        paint.setHdrColor(highlight.style.color)
        paint.strokeWidth =
            ceil(highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)) * 2f
        paint.blur(highlight.blurRadius.toPx())
        if (isRuntimeShaderSupported()) {
            val shader =
                with(highlight.style) {
                    createShader(
                        shape = shapeProvider.shape,
                        runtimeShaderCache = runtimeShaderCache,
                    )
                }
            paint.setRuntimeShader(shader)
        }
    }
}
