package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropPrimitiveResources
import top.ltfan.backdrop.ProcessedBackdropFrame
import top.ltfan.backdrop.RuntimeShaderCacheImpl
import top.ltfan.backdrop.highlightBandProfile
import top.ltfan.backdrop.interaction.InteractionLightUniforms
import top.ltfan.backdrop.interaction.interactionFieldResponse
import top.ltfan.backdrop.interaction.interactionLightWeight
import top.ltfan.backdrop.interaction.isFiniteForRendering
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

private class StaticShaderSignature(
    val geometryKey: String,
    val segmentCapacity: Int,
    val falloffVariant: String,
    val sharedDirectionCurve: Boolean,
    val styleSource: String,
    val transformSource: String,
    val exclusionCount: Int,
    val sampledColor: Boolean,
    val interactionCapacity: Int,
    val angularCapacity: Int,
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
        interactionCapacity: Int,
        angularCapacity: Int,
    ): Boolean =
        this.geometryKey == geometryKey &&
            this.segmentCapacity == segmentCapacity &&
            this.falloffVariant == falloffVariant &&
            this.sharedDirectionCurve == sharedDirectionCurve &&
            this.styleSource == styleSource &&
            this.transformSource == transformSource &&
            this.exclusionCount == exclusionCount &&
            this.sampledColor == sampledColor &&
            this.interactionCapacity == interactionCapacity &&
            this.angularCapacity == angularCapacity
}

private data class CpuFallbackKey(
    val geometry: HighlightBoundaryGeometry,
    val size: Size,
    val width: Float,
    val density: Float,
    val falloff: BackdropCurve,
    val style: HighlightStyle,
    val direction: HighlightDirection,
    val exclusions: Set<HighlightAngularCut>,
)

/** Generates contour pixels into receiver-owned layers. */
internal class HighlightRenderer(private val resources: BackdropPrimitiveResources) {
    private var processedFrame = ProcessedBackdropFrame()
    private var highlightLayer: GraphicsLayer? = null
    private var boundaryOutline: Outline? = null
    private var boundaryGeometry: HighlightBoundaryGeometry? = null
    private var surfaceSize = Size.Zero
    private val staticShaderKeys = mutableMapOf<StaticShaderSignature, String>()
    private var nextStaticShaderKey = 0
    private var staticShaderSignature: StaticShaderSignature? = null
    private var currentStaticShaderKey: String? = null
    private var cpuFallbackKey: CpuFallbackKey? = null
    private var cpuFallbackPixels: FloatArray? = null
    private var cpuInteractionPixels: FloatArray? = null
    private var interactionCapacity = 0
    private val interactionUniforms = mutableMapOf<Int, InteractionLightUniforms>()

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    fun draw(
        scope: DrawScope,
        highlight: Highlight.Config,
        outline: Outline,
        frame: ProcessedBackdropFrame,
        contourOpacity: Float = 0f,
    ) {
        processedFrame = frame
        if (surfaceSize != scope.size) {
            surfaceSize = scope.size
            boundaryOutline = null
            boundaryGeometry = null
        }
        var capacity = 0
        if (contourOpacity > 0f) {
            capacity = 4
            while (capacity < frame.interactionLights.size) capacity *= 2
        }
        interactionCapacity = maxOf(interactionCapacity, capacity)
        with(scope) { drawStatic(highlight, outline, capacity, contourOpacity) }
    }

    fun refresh() {
        releaseOutputLayer()
    }

    fun release() {
        releaseDrawingLayers()
    }

    private fun DrawScope.drawStatic(
        highlight: Highlight.Config,
        outline: Outline,
        interactionCapacity: Int,
        interactionContourOpacity: Float,
    ) {
        if (!isRuntimeShaderSupported()) {
            drawStaticFallback(highlight, outline, interactionContourOpacity)
            return
        }
        val geometry = boundaryGeometry(outline)
        if (geometry.isEmpty) {
            releaseStaticLayer()
            return
        }
        val width = highlight.width.toPx()
        require(width.isFinite()) { "highlight width must remain finite in pixels" }
        val directionConfig = highlight.direction as? HighlightDirection.Incidence
        val angular = highlight.direction as? HighlightDirection.Angular
        val angularCapacity = angular?.profile?.capacity ?: 0
        val sharedDirectionCurve =
            directionConfig != null &&
                directionConfig.strength.curve == directionConfig.widthScale.curve
        val maximumWidthScale = highlight.direction.maximumWidthScale
        require((width * maximumWidthScale).isFinite()) {
            "direction-scaled highlight width must remain finite in pixels"
        }
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val safeSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val style = highlight.style
        val exclusions = highlight.cuts
        val response = highlight.colorResponse
        val sourceImage = processedFrame.image.takeIf { response.amount > 0f }
        val fieldUniforms =
            if (interactionCapacity >= 4) {
                interactionUniforms
                    .getOrPut(interactionCapacity) { InteractionLightUniforms(interactionCapacity) }
                    .also { uniforms ->
                        val coordinates = processedFrame.receiverCoordinates
                        if (coordinates == null) uniforms.clear()
                        else uniforms.prepare(processedFrame.interactionLights, coordinates, size)
                    }
            } else null
        val colorTransform = response.transform.takeIf { sourceImage != null }
        val colorTransformSource = colorTransform?.shaderSource.orEmpty()
        val segmentCapacity = boundaryUniformCapacity(geometry.segmentCount)
        val falloffVariant = highlightCurveVariant(highlight.falloff)
        val styleSource = style.shaderSource
        val sampledColor = sourceImage != null
        val signatureMatches =
            staticShaderSignature?.matches(
                geometry.shaderKey,
                segmentCapacity,
                falloffVariant,
                sharedDirectionCurve,
                styleSource,
                colorTransformSource,
                exclusions.size,
                sampledColor,
                interactionCapacity,
                angularCapacity,
            ) == true
        val shaderKey =
            if (signatureMatches) {
                requireNotNull(currentStaticShaderKey)
            } else {
                val signature =
                    StaticShaderSignature(
                        geometry.shaderKey,
                        segmentCapacity,
                        falloffVariant,
                        sharedDirectionCurve,
                        styleSource,
                        colorTransformSource,
                        exclusions.size,
                        sampledColor,
                        interactionCapacity,
                        angularCapacity,
                    )
                staticShaderSignature = signature
                staticShaderKeys
                    .getOrPut(signature) { "StaticHighlight:${nextStaticShaderKey++}" }
                    .also { currentStaticShaderKey = it }
            }
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
                    interactionCapacity,
                    angularCapacity,
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
        val radians = normalizedAngle(highlight.direction.angle) * (PI / 180.0).toFloat()
        shader.setFloatUniform("directionVector", cos(radians), sin(radians))
        if (angular != null) {
            angular.profile.upload(shader)
            shader.setFloatUniform("angularMaximumWidthScale", maximumWidthScale)
        } else {
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
        }
        if (highlightCurveVariant(highlight.falloff) != "linear") {
            shader.setFloatUniform("falloffCurve", highlight.falloff.shaderControls())
        }
        shader.setColorUniform("color", style.color)
        style.configureShader(shader)
        if (exclusions.isNotEmpty()) shader.setFloatUniform("exclusions", highlight.cutUniforms)
        fieldUniforms?.upload(shader)
        if (fieldUniforms != null)
            shader.setFloatUniform("interactionContourOpacity", interactionContourOpacity)
        val layer = highlightLayer ?: resources.createGraphicsLayer().also { highlightLayer = it }
        layer.alpha = highlight.opacity
        layer.blendMode = BlendMode.SrcOver
        val canvas = drawContext.canvas
        if (colorTransform != null) {
            shader.setFloatUniform("colorResponseAmount", response.amount)
            colorTransform.configureShader(shader)
            val source = requireNotNull(sourceImage)
            layer.renderEffect = source.runtimeShaderEffect(shader, "content")
            layer.record(Density(density, fontScale), layoutDirection, safeSize) {
                source.draw(this)
            }
            canvas.save()
            canvas.clipOutline(outline, roundedClipPath)
            drawLayer(layer)
            canvas.restore()
        } else {
            layer.renderEffect = null
            paint.setRuntimeShader(shader)
            layer.record(Density(density, fontScale), layoutDirection, safeSize) {
                drawContext.canvas.drawRect(Rect(0f, 0f, size.width, size.height), paint)
            }
            canvas.save()
            canvas.clipOutline(outline, roundedClipPath)
            drawLayer(layer)
            canvas.restore()
        }
    }

    private fun DrawScope.drawStaticFallback(
        highlight: Highlight.Config,
        outline: Outline,
        interactionContourOpacity: Float,
    ) {
        val layer = highlightLayer ?: resources.createGraphicsLayer().also { highlightLayer = it }
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val safeSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val geometry = boundaryGeometry(outline)
        val width = highlight.width.toPx()
        val exclusions = highlight.cuts
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
        val displayedPixels =
            if (interactionContourOpacity > 0f && processedFrame.interactionLights.isNotEmpty()) {
                val output =
                    cpuInteractionPixels?.takeIf { it.size == pixels.size }
                        ?: FloatArray(pixels.size).also {
                            cpuInteractionPixels = it
                        }
                pixels.copyInto(output)
                compositeInteractionContour(
                    pixels = output,
                    pixelWidth = ceil(size.width).toInt(),
                    highlight = highlight,
                    geometry = geometry,
                    contourOpacity = interactionContourOpacity,
                )
                output
            } else pixels
        layer.alpha = highlight.opacity
        layer.blendMode = BlendMode.SrcOver
        layer.renderEffect = null
        layer.record(Density(density, fontScale), layoutDirection, safeSize) {
            val pixelWidth = ceil(size.width).toInt()
            val pixelHeight = ceil(size.height).toInt()
            for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
                val offset = (y * pixelWidth + x) * 4
                val alpha = displayedPixels[offset + 3]
                if (alpha <= 0f) continue
                drawRect(
                    Color(
                        displayedPixels[offset] / alpha,
                        displayedPixels[offset + 1] / alpha,
                        displayedPixels[offset + 2] / alpha,
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

    private fun DrawScope.compositeInteractionContour(
        pixels: FloatArray,
        pixelWidth: Int,
        highlight: Highlight.Config,
        geometry: HighlightBoundaryGeometry,
        contourOpacity: Float,
    ) {
        val coordinates = processedFrame.receiverCoordinates ?: return
        val entries = processedFrame.interactionLights
        val axisAngle =
            normalizedAngle((highlight.direction as? HighlightDirection.Incidence)?.angle ?: 0f) *
                (PI / 180.0).toFloat()
        val axisX = cos(axisAngle)
        val axisY = sin(axisAngle)
        val width = highlight.width.toPx()
        if (width <= 0f) return
        val pixelHeight = ceil(size.height).toInt()
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val point = Offset(x + .5f, y + .5f)
            if (!geometry.contains(point)) continue
            val boundary = geometry.nearestBoundary(point)
            val normal = boundary.outwardNormal
            val projection = normal.x * axisX + normal.y * axisY
            val magnitude = directionLevel(highlight.direction, projection)
            val bandWidth = width * directionWidthScale(highlight.direction, magnitude)
            if (boundary.distance >= bandWidth + .5f) continue
            val coverage =
                highlightBandProfile(boundary.distance, bandWidth, highlight.falloff) *
                    exclusionWeight(normal, highlight.cuts)
            if (coverage <= 0f) continue
            val world = coordinates.localToWindow(point)
            if (!world.x.isFinite() || !world.y.isFinite()) continue
            var totalWeight = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            for (entry in entries) {
                if (!entry.isFiniteForRendering() || entry.radiusPx <= 0f) continue
                val dx = world.x - entry.x
                val dy = world.y - entry.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance >= entry.radiusPx) continue
                val light = entry.linearColor
                val weight =
                    interactionLightWeight(
                        distance,
                        entry.radiusPx,
                        light.alpha,
                        entry.intensity,
                        entry.falloff,
                    )
                if (weight <= 0f) continue
                totalWeight += weight
                red += light.red * weight
                green += light.green * weight
                blue += light.blue * weight
            }
            if (totalWeight <= 0f) continue
            val interactionAlpha = contourOpacity * interactionFieldResponse(totalWeight) * coverage
            if (interactionAlpha <= 0f) continue
            val offset = (y * pixelWidth + x) * 4
            val baseAlpha = pixels[offset + 3]
            val alpha = interactionAlpha + baseAlpha * (1f - interactionAlpha)
            val baseRed =
                if (baseAlpha > 0f)
                    decodeHighlightExtendedSrgb(pixels[offset] / baseAlpha) * baseAlpha
                else 0f
            val baseGreen =
                if (baseAlpha > 0f)
                    decodeHighlightExtendedSrgb(pixels[offset + 1] / baseAlpha) * baseAlpha
                else 0f
            val baseBlue =
                if (baseAlpha > 0f)
                    decodeHighlightExtendedSrgb(pixels[offset + 2] / baseAlpha) * baseAlpha
                else 0f
            pixels[offset] =
                encodeHighlightExtendedSrgb(
                    (red / totalWeight * interactionAlpha + baseRed * (1f - interactionAlpha)) /
                        alpha,
                ) * alpha
            pixels[offset + 1] =
                encodeHighlightExtendedSrgb(
                    (green / totalWeight * interactionAlpha + baseGreen * (1f - interactionAlpha)) /
                        alpha,
                ) * alpha
            pixels[offset + 2] =
                encodeHighlightExtendedSrgb(
                    (blue / totalWeight * interactionAlpha + baseBlue * (1f - interactionAlpha)) /
                        alpha,
                ) * alpha
            pixels[offset + 3] = alpha
        }
    }

    private fun boundaryGeometry(outline: Outline): HighlightBoundaryGeometry {
        if (boundaryOutline != outline || boundaryGeometry == null) {
            boundaryGeometry = HighlightBoundaryGeometry(outline)
            boundaryOutline = outline
        }
        return requireNotNull(boundaryGeometry)
    }

    private fun releaseStaticLayer() {
        releaseOutputLayer()
        runtimeShaderCache.clear()
        staticShaderSignature = null
        currentStaticShaderKey = null
        staticShaderKeys.clear()
        nextStaticShaderKey = 0
        cpuFallbackKey = null
        cpuFallbackPixels = null
        cpuInteractionPixels = null
        interactionCapacity = 0
        interactionUniforms.clear()
        clipPath = null
    }

    private fun releaseOutputLayer() {
        highlightLayer?.let {
            it.renderEffect = null
            resources.releaseGraphicsLayer(it)
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
