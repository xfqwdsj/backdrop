package top.ltfan.backdrop.highlight

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.colorspace.ColorSpaces
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
import kotlin.math.PI
import kotlin.math.ceil
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropExtension
import top.ltfan.backdrop.LocalBackdropRenderEpoch
import top.ltfan.backdrop.LocalBackdropStyle
import top.ltfan.backdrop.RuntimeShaderCacheImpl
import top.ltfan.backdrop.internal.BackdropSourceRenderer
import top.ltfan.backdrop.internal.RuntimeShaderEffect
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.internal.SurfaceBounds
import top.ltfan.backdrop.internal.blur
import top.ltfan.backdrop.internal.chain
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.environmentHighlightSamplingOutset
import top.ltfan.backdrop.internal.genericEnvironmentHighlightShader
import top.ltfan.backdrop.internal.highlightExclusionMaskShader
import top.ltfan.backdrop.internal.setRuntimeShader
import top.ltfan.backdrop.internal.staticHighlightShader
import top.ltfan.backdrop.isRuntimeShaderSupported
import top.ltfan.backdrop.shaderControls

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

private data class CpuFallbackKey(
    val geometry: HighlightBoundaryGeometry,
    val size: Size,
    val width: Float,
    val blurRadius: Float,
    val style: HighlightStyle,
    val direction: HighlightDirection,
    val depthCurve: BackdropCurve,
    val exclusions: Set<HighlightExclusion>,
)

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
    private var boundaryOutline: Outline? = null
    private var boundaryGeometry: HighlightBoundaryGeometry? = null
    private val environmentClipPath = Path()
    private val environmentShaderCache = RuntimeShaderCacheImpl()
    private var coordinates: LayoutCoordinates? = null
    private var surfaceSize = Size.Zero
    private var renderEpoch = 0
    private var staticShaderSignature: String? = null
    private var environmentShaderSignature: String? = null
    private var cpuFallbackKey: CpuFallbackKey? = null
    private var cpuFallbackPixels: FloatArray? = null

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
        if (size.minDimension <= 0f) {
            releaseDrawingLayers()
            return drawContent()
        }
        if (surfaceSize != size) {
            surfaceSize = size
            boundaryOutline = null
            boundaryGeometry = null
            onObservedReadsChanged()
        }
        val epoch = currentValueOf(LocalBackdropRenderEpoch)
        if (epoch != renderEpoch) {
            renderEpoch = epoch
            releaseDrawingLayers()
        }
        drawContent()
        val outline = shapeProvider.shape.createOutline(size, layoutDirection, this)
        if (highlight.style == HighlightStyle.None || highlight.width.value <= 0f) {
            releaseStaticLayer()
        } else {
            drawStatic(highlight, outline)
        }
        drawEnvironment(highlight, outline)
    }

    private fun DrawScope.drawStatic(highlight: Highlight.Config, outline: Outline) {
        if (!isRuntimeShaderSupported()) {
            drawStaticFallback(highlight, outline)
            return
        }
        val geometry = boundaryGeometry(outline)
        if (geometry.isEmpty) {
            releaseStaticLayer()
            return
        }
        val layer =
            highlightLayer
                ?: requireGraphicsContext().createGraphicsLayer().also {
                    highlightLayer = it
                }
        val width = highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)
        val surface = size
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val blur = highlight.blurRadius.toPx()
        val room = ceil(maxOf(1f, blur * 3f))
        val safeSize =
            IntSize(ceil(size.width + room * 2f).toInt(), ceil(size.height + room * 2f).toInt())
        val style = highlight.style
        val exclusions = highlight.exclusions.toSet()
        val shaderSignature =
            "${geometry.cacheKey}:${style.directional}:${exclusions.size}:${style.shaderSource}"
        if (staticShaderSignature != shaderSignature) {
            runtimeShaderCache.clear()
            staticShaderSignature = shaderSignature
        }
        val shader =
            runtimeShaderCache.obtainRuntimeShader(
                "StaticHighlight:$shaderSignature",
                staticHighlightShader(geometry, style),
            )
        val angle = normalizedAngle(highlight.direction.angle) * (PI / 180.0).toFloat()
        geometry.configureShader(shader, 0f, 0f)
        shader.setFloatUniform("width", width)
        shader.setFloatUniform("angle", angle)
        shader.setFloatUniform("widthCurve", highlight.direction.widthCurve.shaderControls())
        shader.setFloatUniform("strengthCurve", highlight.direction.strengthCurve.shaderControls())
        shader.setFloatUniform("depthCurve", highlight.depthCurve.shaderControls())
        shader.setFloatUniform("directional", if (style.directional) 1f else 0f)
        shader.setColorUniform("color", style.color)
        style.configureShader(shader)
        paint.style = PaintingStyle.Fill
        paint.setRuntimeShader(shader)
        paint.blur(0f)
        layer.alpha = highlight.alpha
        layer.blendMode = style.blendMode
        val maskEffect =
            if (exclusions.isNotEmpty()) {
                val mask =
                    runtimeShaderCache.obtainRuntimeShader(
                        "HighlightExclusions:${geometry.cacheKey}:${exclusions.size}",
                        highlightExclusionMaskShader(geometry, exclusions.size),
                    )
                geometry.configureShader(mask, room, room)
                mask.setFloatUniform("exclusions", exclusions.shaderValues())
                RuntimeShaderEffect(
                    mask,
                    "content",
                    Rect(0f, 0f, safeSize.width.toFloat(), safeSize.height.toFloat()),
                )
            } else null
        val blurEffect = if (blur > 0f) BlurEffect(blur, blur, TileMode.Decal) else null
        layer.renderEffect =
            when {
                blurEffect != null && maskEffect != null -> blurEffect.chain(maskEffect)
                blurEffect != null -> blurEffect
                else -> maskEffect
            }
        layer.record(safeSize) {
            translate(room, room) {
                val canvas = drawContext.canvas
                canvas.save()
                canvas.clipOutline(outline, roundedClipPath)
                canvas.drawRect(Rect(0f, 0f, surface.width, surface.height), paint)
                canvas.restore()
            }
        }
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipOutline(outline, roundedClipPath)
        translate(-room, -room) { drawLayer(layer) }
        canvas.restore()
    }

    private fun DrawScope.drawStaticFallback(highlight: Highlight.Config, outline: Outline) {
        val layer =
            highlightLayer
                ?: requireGraphicsContext().createGraphicsLayer().also { highlightLayer = it }
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val room = ceil(maxOf(1f, highlight.blurRadius.toPx() * 3f))
        val safeSize =
            IntSize(ceil(size.width + room * 2f).toInt(), ceil(size.height + room * 2f).toInt())
        val geometry = boundaryGeometry(outline)
        val width = highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)
        val blur = highlight.blurRadius.toPx()
        val exclusions = highlight.exclusions.toSet()
        val key =
            CpuFallbackKey(
                geometry,
                size,
                width,
                blur,
                highlight.style,
                highlight.direction,
                highlight.depthCurve,
                exclusions,
            )
        val cacheable =
            highlight.style is HighlightStyle.Default ||
                highlight.style is HighlightStyle.Ambient ||
                highlight.style is HighlightStyle.Plain
        val pixels =
            if (cacheable && cpuFallbackKey == key) {
                requireNotNull(cpuFallbackPixels)
            } else {
                renderStaticHighlightPixels(
                        geometry = geometry.cpuGeometry(),
                        size = size,
                        width = width,
                        blurRadius = blur,
                        style = highlight.style,
                        direction = highlight.direction,
                        depthCurve = highlight.depthCurve,
                        exclusions = exclusions,
                    )
                    .also {
                        cpuFallbackKey = if (cacheable) key else null
                        cpuFallbackPixels = if (cacheable) it else null
                    }
            }
        layer.alpha = highlight.alpha
        layer.blendMode = highlight.style.blendMode
        layer.renderEffect = null
        layer.record(safeSize) {
            translate(room, room) {
                val pixelWidth = ceil(size.width).toInt()
                val pixelHeight = ceil(size.height).toInt()
                for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
                    val offset = (y * pixelWidth + x) * 4
                    val alpha = pixels[offset + 3]
                    if (alpha <= 0f) continue
                    drawRect(
                        Color(
                            pixels[offset] / alpha,
                            pixels[offset + 1] / alpha,
                            pixels[offset + 2] / alpha,
                            alpha,
                            ColorSpaces.ExtendedSrgb,
                        ),
                        Offset(x.toFloat(), y.toFloat()),
                        Size(1f, 1f),
                    )
                }
            }
        }
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipOutline(outline, roundedClipPath)
        translate(-room, -room) { drawLayer(layer) }
        canvas.restore()
    }

    private fun boundaryGeometry(outline: Outline): HighlightBoundaryGeometry {
        if (boundaryOutline != outline || boundaryGeometry == null) {
            boundaryGeometry = HighlightBoundaryGeometry(outline)
            boundaryOutline = outline
        }
        return requireNotNull(boundaryGeometry)
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
                            (environment.brightness.strength > 0f ||
                                environment.tint.strength > 0f) &&
                            surfaceSize.minDimension > 0f &&
                            isRuntimeShaderSupported()
                    ) {
                        environmentHighlightSamplingOutset(
                            environment.sampleDistance.toPx(),
                            environment.blurRadius.toPx(),
                        )
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
        environmentShaderSignature = null
        environmentClipPath.rewind()
    }

    private fun releaseStaticLayer() {
        highlightLayer?.let {
            it.renderEffect = null
            requireGraphicsContext().releaseGraphicsLayer(it)
            paint.setRuntimeShader(null)
            paint.blur(0f)
        }
        highlightLayer = null
        runtimeShaderCache.clear()
        staticShaderSignature = null
        cpuFallbackKey = null
        cpuFallbackPixels = null
        clipPath = null
    }

    private fun releaseDrawingLayers() {
        releaseStaticLayer()
        releaseEnvironmentLayer()
        boundaryOutline = null
        boundaryGeometry = null
    }

    private fun DrawScope.drawEnvironment(highlight: Highlight.Config, outline: Outline) {
        val environment = highlight.environment
        if (
            environment == null ||
                (environment.brightness.strength <= 0f && environment.tint.strength <= 0f) ||
                !isRuntimeShaderSupported()
        ) {
            releaseEnvironmentLayer()
            return
        }
        val bounds = outline.bounds
        val geometry = boundaryGeometry(outline)
        if (bounds.isEmpty || geometry.isEmpty) {
            releaseEnvironmentLayer()
            return
        }
        val width = highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)
        val distance = environment.sampleDistance.toPx()
        val environmentBlurRadius = environment.blurRadius.toPx()
        val room = environmentHighlightSamplingOutset(distance, environmentBlurRadius)
        val layerSize =
            IntSize(ceil(size.width + room * 2f).toInt(), ceil(size.height + room * 2f).toInt())
        val exclusions = highlight.exclusions.toSet()
        val signature = "${geometry.cacheKey}:${exclusions.size}"
        if (environmentShaderSignature != signature) {
            environmentShaderCache.clear()
            environmentShaderSignature = signature
        }
        val shader =
            environmentShaderCache
                .obtainRuntimeShader(
                    "Environment:$signature",
                    genericEnvironmentHighlightShader(geometry, exclusions.size),
                )
                .also {
                    geometry.configureShader(it, room, room)
                    it.setFloatUniform("width", width)
                    it.setFloatUniform(
                        "angle",
                        normalizedAngle(highlight.direction.angle) * (PI / 180.0).toFloat(),
                    )
                    it.setFloatUniform(
                        "widthCurve",
                        highlight.direction.widthCurve.shaderControls(),
                    )
                    it.setFloatUniform("depthCurve", highlight.depthCurve.shaderControls())
                    it.setFloatUniform("directional", if (highlight.style.directional) 1f else 0f)
                    it.setFloatUniform(
                        "staticEnabled",
                        if (highlight.style == HighlightStyle.None) 0f else 1f,
                    )
                    if (exclusions.isNotEmpty())
                        it.setFloatUniform("exclusions", exclusions.shaderValues())
                }
        shader.setFloatUniform("sampleDistance", distance)
        shader.setFloatUniform("brightnessStrength", environment.brightness.strength)
        shader.setFloatUniform("brightnessThreshold", environment.brightness.threshold)
        shader.setFloatUniform("tintStrength", environment.tint.strength)
        shader.setFloatUniform("tintThreshold", environment.tint.threshold)
        shader.setFloatUniform("thicknessWidth", environment.thickness.maxWidth.toPx())
        shader.setFloatUniform("thicknessThreshold", environment.thickness.threshold)
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
        val highlightEffect =
            RuntimeShaderEffect(
                shader,
                "content",
                Rect(0f, 0f, layerSize.width.toFloat(), layerSize.height.toFloat()),
            )
        layer.renderEffect =
            if (environmentBlurRadius > 0f) {
                BlurEffect(environmentBlurRadius, environmentBlurRadius, TileMode.Decal)
                    .chain(highlightEffect)
            } else {
                highlightEffect
            }
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
}
