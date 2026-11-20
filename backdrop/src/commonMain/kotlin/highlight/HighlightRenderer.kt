package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import top.ltfan.backdrop.BackdropImageInput
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropRenderResources
import top.ltfan.backdrop.RuntimeShaderCacheImpl
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.environmentHighlightTileShader
import top.ltfan.backdrop.internal.highlightDirectionUsesSharedCurve
import top.ltfan.backdrop.internal.setRuntimeShader
import top.ltfan.backdrop.isRuntimeShaderSupported
import top.ltfan.backdrop.shaderControls
import top.ltfan.backdrop.shape.Contour
import top.ltfan.backdrop.shape.ContourShape
import top.ltfan.backdrop.shape.ExperimentalBackdropShapeApi

internal fun boundaryUniformCapacity(candidateCount: Int): Int {
    require(candidateCount >= 0)
    var capacity = 1
    while (capacity < candidateCount) capacity *= 2
    return capacity
}

private data class ShaderSignature(
    val geometryKey: String,
    val certifiedNormalsOnly: Boolean,
    val segmentCapacity: Int,
    val segmentGroupSize: Int,
    val sharedDirectionCurve: Boolean,
    val hasEnvironment: Boolean,
    val angularCapacity: Int,
    val exclusionCapacity: Int,
)

/** Generates environment-lit contour pixels into receiver-owned layers. */
@OptIn(ExperimentalBackdropShapeApi::class)
internal class EnvironmentHighlightRenderer(
    private val resources: BackdropRenderResources,
) {
    private var highlightLayer: GraphicsLayer? = null
    private val additionalLayers = mutableListOf<GraphicsLayer>()
    private val tiles = HighlightTiles()
    private var boundaryOutline: Outline? = null
    private var boundaryContour: Contour? = null
    private var boundaryGeometry: HighlightBoundaryGeometry? = null
    private var surfaceSize = Size.Zero
    private val shaderKeys = mutableMapOf<ShaderSignature, String>()
    private var nextShaderKey = 0
    private var shaderSignature: ShaderSignature? = null
    private var currentShaderKey: String? = null
    private val paint = Paint()
    private var clipPath: Path? = null
    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    fun draw(
        scope: DrawScope,
        highlight: Highlight,
        outline: Outline,
        context: BackdropPrimitiveDrawContext,
        environment: BackdropImageInput?,
    ) {
        if (surfaceSize != scope.size) {
            surfaceSize = scope.size
            boundaryOutline = null
            boundaryContour = null
            boundaryGeometry = null
        }
        with(scope) {
            when (highlight) {
                is Highlight.Basic ->
                    drawBasic(
                        outline,
                        toHighlightPx(highlight.width),
                        highlight.baseBrightness,
                        highlight.opacity,
                    )
                is Highlight.Contour -> drawContour(highlight, outline, context, environment)
                Highlight.None -> refresh()
            }
        }
    }

    fun refresh() {
        releaseOutputLayers()
    }

    fun release() {
        releaseOutputLayers()
        runtimeShaderCache.clear()
        shaderKeys.clear()
        shaderSignature = null
        currentShaderKey = null
        boundaryOutline = null
        boundaryContour = null
        boundaryGeometry = null
        clipPath = null
    }

    private fun DrawScope.drawContour(
        highlight: Highlight.Contour,
        outline: Outline,
        context: BackdropPrimitiveDrawContext,
        environment: BackdropImageInput?,
    ) {
        val width = toHighlightPx(highlight.width)
        if (
            highlight.opacity <= 0f ||
                width == 0f ||
                (highlight.baseBrightness == 0f &&
                    (environment == null || highlight.environmentGain == 0f))
        ) {
            releaseOutputLayers()
            return
        }
        if (!isRuntimeShaderSupported()) {
            drawBasic(outline, width, highlight.baseBrightness, highlight.opacity)
            return
        }
        val maximumWidthScale = highlight.direction.maximumWidthScale
        require((width * maximumWidthScale).isFinite()) {
            "direction-scaled highlight width must remain finite in pixels"
        }
        val geometry = boundaryGeometry(outline, context)
        if (geometry.isEmpty) {
            releaseOutputLayers()
            return
        }
        val angular = highlight.direction as? HighlightDirection.Angular
        val angularCapacity = angular?.profile?.capacity ?: 0
        val certifiedNormalsOnly = geometry.certifiesNormalsFor(size)
        val segmentCapacity = boundaryUniformCapacity(geometry.segmentCount)
        val segmentGroupSize = if (!geometry.isAnalytic && segmentCapacity >= 32) 8 else 0
        val hasEnvironment = environment != null
        val sharedDirectionCurve = highlightDirectionUsesSharedCurve(highlight.direction)
        val signature =
            ShaderSignature(
                geometry.shaderKey,
                certifiedNormalsOnly,
                segmentCapacity,
                segmentGroupSize,
                sharedDirectionCurve,
                hasEnvironment,
                angularCapacity,
                highlight.cuts.size,
            )
        val shaderKey =
            if (shaderSignature == signature) {
                checkNotNull(currentShaderKey)
            } else {
                shaderSignature = signature
                shaderKeys
                    .getOrPut(signature) { "EnvironmentHighlight:${nextShaderKey++}" }
                    .also { currentShaderKey = it }
            }
        val shader =
            runtimeShaderCache.obtainRuntimeShader(shaderKey) {
                environmentHighlightTileShader(
                    boundary = geometry,
                    segmentCapacity = segmentCapacity,
                    segmentGroupSize = segmentGroupSize,
                    certifiedNormalsOnly = certifiedNormalsOnly,
                    sharedDirectionCurve = sharedDirectionCurve,
                    hasEnvironment = hasEnvironment,
                    angularCapacity = angularCapacity,
                    exclusionCapacity = highlight.cuts.size,
                )
            }
        geometry.configureShader(shader)
        if (!geometry.isAnalytic) {
            shader.setFloatUniform("segments", geometry.paddedSegments(segmentCapacity))
            shader.setFloatUniform(
                "segmentNormalSigns",
                geometry.paddedNormalSigns(segmentCapacity),
            )
        }
        if (segmentGroupSize > 0) {
            shader.setFloatUniform(
                "segmentGroupBounds",
                geometry.segmentGroupBounds(segmentCapacity, segmentGroupSize),
            )
        }
        shader.setFloatUniform("tileBounds", 0f, 0f, size.width, size.height)
        shader.setFloatUniform("width", width)
        shader.setFloatUniform("baseBrightness", highlight.baseBrightness)
        shader.setFloatUniform("environmentGain", highlight.environmentGain)
        val radians = normalizedAngle(highlight.direction.angle) * (PI / 180.0).toFloat()
        shader.setFloatUniform("directionVector", cos(radians), sin(radians))
        if (angular != null) {
            angular.profile.upload(shader)
            shader.setFloatUniform("angularMaximumWidthScale", maximumWidthScale)
        } else {
            val incidence = highlight.direction as? HighlightDirection.Incidence
            shader.setFloatUniform("directionEnabled", if (incidence == null) 0f else 1f)
            shader.setFloatUniform(
                "directionMode",
                if (incidence?.mode == HighlightDirection.Mode.Single) 0f else 1f,
            )
            shader.setFloatUniform(
                "directionStrengthRange",
                incidence?.strength?.from ?: 1f,
                incidence?.strength?.to ?: 1f,
            )
            shader.setFloatUniform(
                "directionStrengthCurve",
                incidence?.strength?.curve?.shaderControls() ?: floatArrayOf(0f, 0f, 1f, 1f),
            )
            shader.setFloatUniform(
                "directionWidthRange",
                incidence?.widthScale?.from ?: 1f,
                incidence?.widthScale?.to ?: 1f,
            )
            if (!sharedDirectionCurve) {
                shader.setFloatUniform(
                    "directionWidthCurve",
                    incidence?.widthScale?.curve?.shaderControls() ?: floatArrayOf(0f, 0f, 1f, 1f),
                )
            }
        }
        if (highlight.cuts.isNotEmpty()) shader.setFloatUniform("exclusions", highlight.cutUniforms)

        val safeSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val contourFreeBounds =
            if (geometry.isAnalytic && (environment == null || !supportsMergedHighlightTiles))
                Rect.Zero
            else geometry.contourFreeBounds
        tiles.prepare(safeSize, contourFreeBounds, width * maximumWidthScale)
        val mergedEffect =
            if (environment != null && tiles.count > 1)
                mergeHighlightTiles(environment, shader, tiles)
            else null
        trimOutputLayers(if (mergedEffect == null) tiles.count else 1)
        val recordingDensity = Density(density, fontScale)
        val roundedClipPath =
            if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
        val canvas = drawContext.canvas
        canvas.save()
        try {
            canvas.clipOutline(outline, roundedClipPath)
            if (mergedEffect != null) {
                val layer = outputLayer(0)
                layer.alpha = highlight.opacity
                layer.blendMode = BlendMode.SrcOver
                layer.renderEffect = mergedEffect
                layer.record(recordingDensity, layoutDirection, safeSize) {
                    checkNotNull(environment).draw(this)
                }
                drawLayer(layer)
                return
            }
            for (index in 0 until tiles.count) {
                val output = tiles[index]
                val storage =
                    IntRect(
                        maxOf(0, output.left - 1),
                        maxOf(0, output.top - 1),
                        if (output.right < safeSize.width) output.right + 1 else output.right,
                        if (output.bottom < safeSize.height) output.bottom + 1 else output.bottom,
                    )
                val storageSize = IntSize(storage.width, storage.height)
                val origin = Offset(-storage.left.toFloat(), -storage.top.toFloat())
                shader.setFloatUniform("origin", origin.x, origin.y)
                val layer = outputLayer(index)
                layer.alpha = highlight.opacity
                layer.blendMode = BlendMode.SrcOver
                if (environment != null) {
                    layer.renderEffect =
                        environment.runtimeShaderEffect(shader, "environment", origin)
                    layer.record(recordingDensity, layoutDirection, storageSize) {
                        val recording = drawContext.canvas
                        recording.save()
                        try {
                            recording.translate(origin.x, origin.y)
                            environment.draw(this)
                        } finally {
                            recording.restore()
                        }
                    }
                } else {
                    layer.renderEffect = null
                    paint.setRuntimeShader(shader)
                    layer.record(recordingDensity, layoutDirection, storageSize) {
                        drawContext.canvas.drawRect(Rect(0f, 0f, size.width, size.height), paint)
                    }
                }
                canvas.save()
                try {
                    canvas.clipRect(
                        output.left.toFloat() - if (output.left == 0) 1f else 0f,
                        output.top.toFloat() - if (output.top == 0) 1f else 0f,
                        output.right.toFloat() + if (output.right == safeSize.width) 1f else 0f,
                        output.bottom.toFloat() + if (output.bottom == safeSize.height) 1f else 0f,
                    )
                    canvas.translate(storage.left.toFloat(), storage.top.toFloat())
                    drawLayer(layer)
                } finally {
                    canvas.restore()
                }
            }
        } finally {
            canvas.restore()
        }
    }

    private fun DrawScope.drawBasic(
        outline: Outline,
        width: Float,
        baseBrightness: Float,
        opacity: Float,
    ) {
        releaseOutputLayers()
        if (width <= 0f || baseBrightness <= 0f) return
        val amount = minOf(1f, baseBrightness * opacity)
        if (amount <= 0f) return
        val path = outline.asPath()
        val canvas = drawContext.canvas
        canvas.save()
        try {
            canvas.clipOutline(outline, clipPath ?: Path().also { clipPath = it })
            drawPath(
                path,
                Color.White.copy(alpha = amount),
                style =
                    Stroke(
                        width = width * 2f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )
        } finally {
            canvas.restore()
        }
    }

    @OptIn(ExperimentalBackdropShapeApi::class)
    private fun boundaryGeometry(
        outline: Outline,
        context: BackdropPrimitiveDrawContext,
    ): HighlightBoundaryGeometry {
        val contour =
            (context.shape as? ContourShape)?.createContour(
                context.size,
                context.layoutDirection,
                context.density,
            )
        if (boundaryOutline != outline || boundaryContour != contour || boundaryGeometry == null) {
            boundaryGeometry =
                if (contour == null) HighlightBoundaryGeometry(outline)
                else HighlightBoundaryGeometry(contour)
            boundaryOutline = outline
            boundaryContour = contour
        }
        return requireNotNull(boundaryGeometry)
    }

    private fun outputLayer(index: Int): GraphicsLayer {
        if (index == 0)
            return highlightLayer ?: resources.createGraphicsLayer().also { highlightLayer = it }
        while (additionalLayers.size < index) additionalLayers += resources.createGraphicsLayer()
        return additionalLayers[index - 1]
    }

    private fun trimOutputLayers(count: Int) {
        while (additionalLayers.size > maxOf(0, count - 1)) {
            additionalLayers.removeAt(additionalLayers.lastIndex).let {
                it.renderEffect = null
                resources.releaseGraphicsLayer(it)
            }
        }
        if (count > 0) return
        highlightLayer?.let {
            it.renderEffect = null
            resources.releaseGraphicsLayer(it)
        }
        highlightLayer = null
    }

    private fun releaseOutputLayers() {
        trimOutputLayers(0)
        paint.setRuntimeShader(null)
    }
}

private fun DrawScope.toHighlightPx(width: Dp): Float =
    width.toPx().also {
        require(it.isFinite() && it >= 0f) {
            "highlight width must remain finite and non-negative in pixels"
        }
    }

private fun Outline.asPath(): Path =
    when (this) {
        is Outline.Rectangle -> Path().apply { addRect(rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
        is Outline.Generic -> Path().apply { addPath(path) }
    }
