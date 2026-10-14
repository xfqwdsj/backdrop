package top.ltfan.backdrop.highlight

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.LocalBackdropRenderEpoch
import top.ltfan.backdrop.LocalBackdropStyle
import top.ltfan.backdrop.ProcessedBackdropFrame
import top.ltfan.backdrop.RuntimeShaderCacheImpl
import top.ltfan.backdrop.internal.RuntimeShaderEffect
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.internal.blur
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.highlightCurveVariant
import top.ltfan.backdrop.internal.setRuntimeShader
import top.ltfan.backdrop.internal.staticHighlightTileShader
import top.ltfan.backdrop.isRuntimeShaderSupported
import top.ltfan.backdrop.shaderControls

internal fun boundaryUniformCapacity(candidateCount: Int): Int {
    require(candidateCount >= 0)
    var capacity = 1
    while (capacity < candidateCount) capacity *= 2
    return capacity
}

internal class HighlightElement(
    val shapeProvider: ShapeProvider,
    val highlight: (() -> Highlight)?,
    val processedFrame: ProcessedBackdropFrame,
) : ModifierNodeElement<HighlightNode>() {

    override fun create(): HighlightNode {
        return HighlightNode(shapeProvider, highlight, processedFrame)
    }

    override fun update(node: HighlightNode) {
        node.shapeProvider = shapeProvider
        node.highlight = highlight
        node.processedFrame = processedFrame
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
        if (processedFrame !== other.processedFrame) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + highlight.hashCode()
        result = 31 * result + processedFrame.hashCode()
        return result
    }
}

private class StaticShaderSignature(
    val geometryKey: String,
    val segmentCapacity: Int,
    val falloffVariant: String,
    val sharedDirectionCurve: Boolean,
    val styleSource: String,
    val transformSource: String,
    val exclusionCount: Int,
    val sampledColor: Boolean,
) {
    fun matches(
        geometryKey: String,
        segmentCapacity: Int,
        falloffVariant: String,
        sharedDirectionCurve: Boolean,
        styleSource: String,
        transformSource: String,
        exclusionCount: Int,
        sampledColor: Boolean,
    ): Boolean =
        this.geometryKey == geometryKey &&
            this.segmentCapacity == segmentCapacity &&
            this.falloffVariant == falloffVariant &&
            this.sharedDirectionCurve == sharedDirectionCurve &&
            this.styleSource == styleSource &&
            this.transformSource == transformSource &&
            this.exclusionCount == exclusionCount &&
            this.sampledColor == sampledColor
}

private data class CpuFallbackKey(
    val geometry: HighlightBoundaryGeometry,
    val size: Size,
    val width: Float,
    val density: Float,
    val falloff: BackdropCurve,
    val style: HighlightStyle,
    val direction: HighlightDirection,
    val exclusions: Set<HighlightExclusion>,
)

internal class HighlightNode(
    var shapeProvider: ShapeProvider,
    var highlight: (() -> Highlight)?,
    var processedFrame: ProcessedBackdropFrame,
) : DrawModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var highlightLayer: GraphicsLayer? = null
    private var boundaryOutline: Outline? = null
    private var boundaryGeometry: HighlightBoundaryGeometry? = null
    private var surfaceSize = Size.Zero
    private var renderEpoch = 0
    private var staticShaderSignature: StaticShaderSignature? = null
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
        if (highlight !is Highlight.Config || highlight.opacity == 0f) {
            processedFrame.begin(false)
            releaseDrawingLayers()
            return drawContent()
        }
        if (size.minDimension <= 0f) {
            processedFrame.begin(false)
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
            releaseOutputLayer()
        }
        processedFrame.begin(
            highlight.width.value > 0f &&
                highlight.colorResponse.amount > 0f &&
                highlight.style !== HighlightStyle.None &&
                isRuntimeShaderSupported()
        )
        drawContent()
        if (highlight.width.value == 0f || highlight.style === HighlightStyle.None) {
            releaseDrawingLayers()
            return
        }
        val outline = shapeProvider.shape.createOutline(size, layoutDirection, this)
        drawStatic(highlight, outline)
    }

    private fun DrawScope.drawStatic(
        highlight: Highlight.Config,
        outline: Outline,
    ) {
        if (!isRuntimeShaderSupported()) {
            drawStaticFallback(highlight, outline)
            return
        }
        val geometry = boundaryGeometry(outline)
        if (geometry.isEmpty) {
            releaseStaticLayer()
            return
        }
        val width = highlight.width.toPx()
        require(width.isFinite()) { "highlight width must remain finite in pixels" }
        val directionConfig = highlight.direction as? HighlightDirection.Config
        val sharedDirectionCurve =
            directionConfig != null &&
                directionConfig.strength.curve == directionConfig.widthScale.curve
        val maximumWidthScale = directionConfig?.widthScale?.let { maxOf(it.from, it.to) } ?: 1f
        require((width * maximumWidthScale).isFinite()) {
            "direction-scaled highlight width must remain finite in pixels"
        }
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val safeSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val style = highlight.style
        val exclusions = highlight.exclusions
        val response = highlight.colorResponse
        val sourceLayer = processedFrame.layer.takeIf { response.amount > 0f }
        val colorTransform = response.transform.takeIf { sourceLayer != null }
        val colorTransformSource = colorTransform?.shaderSource.orEmpty()
        val segmentCapacity = boundaryUniformCapacity(geometry.segmentCount)
        val falloffVariant = highlightCurveVariant(highlight.falloff)
        val styleSource = style.shaderSource
        val sampledColor = sourceLayer != null
        if (
            staticShaderSignature?.matches(
                geometry.shaderKey,
                segmentCapacity,
                falloffVariant,
                sharedDirectionCurve,
                styleSource,
                colorTransformSource,
                exclusions.size,
                sampledColor,
            ) != true
        ) {
            runtimeShaderCache.clear()
            staticShaderSignature =
                StaticShaderSignature(
                    geometry.shaderKey,
                    segmentCapacity,
                    falloffVariant,
                    sharedDirectionCurve,
                    styleSource,
                    colorTransformSource,
                    exclusions.size,
                    sampledColor,
                )
        }
        val shaderKey = "StaticHighlight"
        val shader =
            runtimeShaderCache.obtainRuntimeShader(shaderKey) {
                staticHighlightTileShader(
                    geometry,
                    style,
                    exclusions.size,
                    colorTransform,
                    segmentCapacity,
                    highlight.falloff,
                    sharedDirectionCurve,
                )
            }
        paint.style = PaintingStyle.Fill
        paint.blur(0f)
        geometry.configureShader(shader)
        if (!geometry.isAnalytic) {
            shader.setFloatUniform("segments", geometry.paddedSegments(segmentCapacity))
            shader.setFloatUniform(
                "segmentNormalSigns",
                geometry.paddedNormalSigns(segmentCapacity),
            )
        }
        shader.setFloatUniform("tileBounds", 0f, 0f, size.width, size.height)
        shader.setFloatUniform("width", width)
        val radians = normalizedAngle(directionConfig?.angle ?: 0f) * (PI / 180.0).toFloat()
        shader.setFloatUniform("directionVector", cos(radians), sin(radians))
        shader.setFloatUniform("directionEnabled", if (directionConfig == null) 0f else 1f)
        shader.setFloatUniform(
            "directionMode",
            if (directionConfig?.mode == HighlightDirection.Mode.Single) 0f else 1f,
        )
        shader.setFloatUniform(
            "directionStrengthRange",
            directionConfig?.strength?.from ?: 1f,
            directionConfig?.strength?.to ?: 1f,
        )
        shader.setFloatUniform(
            "directionStrengthCurve",
            directionConfig?.strength?.curve?.shaderControls()
                ?: BackdropCurve.Linear.shaderControls(),
        )
        shader.setFloatUniform(
            "directionWidthRange",
            directionConfig?.widthScale?.from ?: 1f,
            directionConfig?.widthScale?.to ?: 1f,
        )
        if (!sharedDirectionCurve) {
            shader.setFloatUniform(
                "directionWidthCurve",
                directionConfig?.widthScale?.curve?.shaderControls()
                    ?: BackdropCurve.Linear.shaderControls(),
            )
        }
        if (highlightCurveVariant(highlight.falloff) != "linear") {
            shader.setFloatUniform("falloffCurve", highlight.falloff.shaderControls())
        }
        shader.setColorUniform("color", style.color)
        style.configureShader(shader)
        if (exclusions.isNotEmpty()) shader.setFloatUniform("exclusions", exclusions.shaderValues())
        val layer =
            highlightLayer
                ?: requireGraphicsContext().createGraphicsLayer().also { highlightLayer = it }
        layer.alpha = highlight.opacity
        layer.blendMode = highlight.blendMode
        val canvas = drawContext.canvas
        if (colorTransform != null) {
            shader.setFloatUniform("colorResponseAmount", response.amount)
            colorTransform.configureShader(shader)
            val source = requireNotNull(sourceLayer)
            layer.renderEffect =
                RuntimeShaderEffect(
                    shader,
                    "content",
                    processedFrame.sourceBounds,
                )
            layer.record(safeSize) {
                translate(
                    -processedFrame.layerOffset.x - source.topLeft.x,
                    -processedFrame.layerOffset.y - source.topLeft.y,
                ) {
                    drawLayer(source)
                }
            }
            canvas.save()
            canvas.clipOutline(outline, roundedClipPath)
            drawLayer(layer)
            canvas.restore()
        } else {
            layer.renderEffect = null
            paint.setRuntimeShader(shader)
            layer.record(safeSize) {
                drawContext.canvas.drawRect(Rect(0f, 0f, size.width, size.height), paint)
            }
            canvas.save()
            canvas.clipOutline(outline, roundedClipPath)
            drawLayer(layer)
            canvas.restore()
        }
    }

    private fun DrawScope.drawStaticFallback(highlight: Highlight.Config, outline: Outline) {
        val layer =
            highlightLayer
                ?: requireGraphicsContext().createGraphicsLayer().also { highlightLayer = it }
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val safeSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val geometry = boundaryGeometry(outline)
        val width = highlight.width.toPx()
        val exclusions = highlight.exclusions
        val key =
            CpuFallbackKey(
                geometry,
                size,
                width,
                this.density,
                highlight.falloff,
                highlight.style,
                highlight.direction,
                exclusions,
            )
        val cacheable =
            highlight.style is HighlightStyle.Solid ||
                highlight.style is HighlightStyle.TwoTone ||
                highlight.style === HighlightStyle.None
        val pixels =
            if (cacheable && cpuFallbackKey == key) {
                requireNotNull(cpuFallbackPixels)
            } else {
                renderStaticHighlightPixels(
                        geometry = geometry,
                        size = size,
                        widthPx = width,
                        falloff = highlight.falloff,
                        direction = highlight.direction,
                        style = highlight.style,
                        exclusions = exclusions,
                        density = this@drawStaticFallback.density,
                    )
                    .also {
                        cpuFallbackKey = if (cacheable) key else null
                        cpuFallbackPixels = if (cacheable) it else null
                    }
            }
        layer.alpha = highlight.opacity
        layer.blendMode = highlight.blendMode
        layer.renderEffect = null
        layer.record(safeSize) {
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
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipOutline(outline, roundedClipPath)
        drawLayer(layer)
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

    override fun onDensityChange() {
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads {
            (highlight ?: currentValueOf(LocalBackdropStyle).highlight)()
        }
        invalidateDraw()
    }

    override fun onDetach() {
        releaseDrawingLayers()
        processedFrame.clear()
    }

    private fun releaseStaticLayer() {
        releaseOutputLayer()
        runtimeShaderCache.clear()
        staticShaderSignature = null
        cpuFallbackKey = null
        cpuFallbackPixels = null
        clipPath = null
    }

    private fun releaseOutputLayer() {
        highlightLayer?.let {
            it.renderEffect = null
            requireGraphicsContext().releaseGraphicsLayer(it)
            paint.setRuntimeShader(null)
            paint.blur(0f)
        }
        highlightLayer = null
    }

    private fun releaseDrawingLayers() {
        releaseStaticLayer()
        boundaryOutline = null
        boundaryGeometry = null
    }
}
