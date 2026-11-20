package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.BackdropGeometry
import top.ltfan.backdrop.BackdropImage
import top.ltfan.backdrop.BackdropImageScope
import top.ltfan.backdrop.BackdropPrimitive
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropPrimitiveInput
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropRenderResources
import top.ltfan.backdrop.RenderImageNode
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.isRuntimeShaderSupported
import top.ltfan.backdrop.primitive
import top.ltfan.backdrop.requireAlpha

private val DefaultBasicWidth = 1.dp
private val DefaultContourWidth = 2.dp
private val DefaultBlurRadius = 12.dp
private const val DefaultBaseBrightness = .12f
private const val DefaultEnvironmentGain = .22f
private val DefaultDirection = HighlightDirection()
private val DefaultDisabledAngles
    get() = emptySet<ClosedFloatingPointRange<Float>>()

/**
 * Parameters for a surface highlight. [Contour] supports directional lighting and an optional
 * Gaussian-filtered environment image. [Basic] draws a uniform inner stroke.
 */
@Stable
public sealed interface Highlight {
    /** Omits the contour primitive and releases its rendering resources. */
    @Stable public data object None : Highlight

    /** Draws a uniform white inner stroke. */
    @Stable
    public data class Basic(
        public val width: Dp = DefaultBasicWidth,
        @param:FloatRange(from = 0.0) public val baseBrightness: Float = DefaultBaseBrightness,
        @param:FloatRange(from = 0.0, to = 1.0) public val opacity: Float = 1f,
    ) : Highlight {
        init {
            validateHighlightStroke(width, baseBrightness, opacity)
        }
    }

    /** Evaluates directional lighting along the contour. */
    @Stable
    public class Contour
    public constructor(
        public val width: Dp = DefaultContourWidth,
        public val blurRadius: Dp = DefaultBlurRadius,
        @param:FloatRange(from = 0.0) public val baseBrightness: Float = DefaultBaseBrightness,
        @param:FloatRange(from = 0.0) public val environmentGain: Float = DefaultEnvironmentGain,
        @param:FloatRange(from = 0.0, to = 1.0) public val opacity: Float = 1f,
        public val direction: HighlightDirection = DefaultDirection,
        disabledAngles: Set<ClosedFloatingPointRange<Float>> = DefaultDisabledAngles,
        public val disabledAngleDomain: HighlightAngleDomain = HighlightAngleDomain.Directed,
    ) : Highlight {
        public val disabledAngles: Set<ClosedFloatingPointRange<Float>> =
            FrozenSet(
                disabledAngles
                    .map { range ->
                        val limit =
                            if (disabledAngleDomain == HighlightAngleDomain.Unsigned) 180f else 360f
                        require(
                            range.start.isFinite() &&
                                range.endInclusive.isFinite() &&
                                range.start >= 0f &&
                                range.endInclusive <= limit &&
                                range.start <= range.endInclusive,
                        ) {
                            "Disabled ranges must be ordered and fit their angular domain"
                        }
                        range.start..range.endInclusive
                    }
                    .toSet(),
            )

        internal val cuts: Set<HighlightAngularCut> = buildSet {
            for (range in this@Contour.disabledAngles) {
                val middle = (range.start + range.endInclusive) * .5f
                val half =
                    minOf(180f, (range.endInclusive - range.start) * .5f + HighlightAngleTolerance)
                add(HighlightAngularCut(direction.angle + middle, half, 0f))
                if (disabledAngleDomain == HighlightAngleDomain.Unsigned)
                    add(HighlightAngularCut(direction.angle - middle, half, 0f))
            }
        }

        internal val cutUniforms: FloatArray = cuts.shaderValues()

        init {
            validateHighlightStroke(width, baseBrightness, opacity)
            require(blurRadius.value.isFinite() && blurRadius.value >= 0f) {
                "blurRadius must be finite and non-negative"
            }
            require(environmentGain.isFinite() && environmentGain >= 0f) {
                "environmentGain must be finite and non-negative"
            }
        }

        public fun copy(
            width: Dp = this.width,
            blurRadius: Dp = this.blurRadius,
            baseBrightness: Float = this.baseBrightness,
            environmentGain: Float = this.environmentGain,
            opacity: Float = this.opacity,
            direction: HighlightDirection = this.direction,
            disabledAngles: Set<ClosedFloatingPointRange<Float>> = this.disabledAngles,
            disabledAngleDomain: HighlightAngleDomain = this.disabledAngleDomain,
        ): Contour =
            Highlight(
                width = width,
                blurRadius = blurRadius,
                baseBrightness = baseBrightness,
                environmentGain = environmentGain,
                opacity = opacity,
                direction = direction,
                disabledAngles = disabledAngles,
                disabledAngleDomain = disabledAngleDomain,
            )

        public operator fun component1(): Dp = width

        public operator fun component2(): Dp = blurRadius

        public operator fun component3(): Float = baseBrightness

        public operator fun component4(): Float = environmentGain

        public operator fun component5(): Float = opacity

        public operator fun component6(): HighlightDirection = direction

        public operator fun component7(): Set<ClosedFloatingPointRange<Float>> = disabledAngles

        public operator fun component8(): HighlightAngleDomain = disabledAngleDomain

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Contour &&
                    width == other.width &&
                    blurRadius == other.blurRadius &&
                    baseBrightness == other.baseBrightness &&
                    environmentGain == other.environmentGain &&
                    opacity == other.opacity &&
                    direction == other.direction &&
                    disabledAngles == other.disabledAngles &&
                    disabledAngleDomain == other.disabledAngleDomain)

        override fun hashCode(): Int {
            var result = width.hashCode()
            result = 31 * result + blurRadius.hashCode()
            result = 31 * result + baseBrightness.hashCode()
            result = 31 * result + environmentGain.hashCode()
            result = 31 * result + opacity.hashCode()
            result = 31 * result + direction.hashCode()
            result = 31 * result + disabledAngles.hashCode()
            result = 31 * result + disabledAngleDomain.hashCode()
            return result
        }

        override fun toString(): String =
            "Highlight(width=$width, blurRadius=$blurRadius, baseBrightness=$baseBrightness, environmentGain=$environmentGain, opacity=$opacity, direction=$direction, disabledAngles=$disabledAngles, disabledAngleDomain=$disabledAngleDomain)"
    }

    public companion object {
        @Stable public val Default: Contour = Highlight()
    }
}

@Suppress("FunctionName")
public fun Highlight(
    width: Dp = DefaultContourWidth,
    blurRadius: Dp = DefaultBlurRadius,
    @FloatRange(from = 0.0) baseBrightness: Float = DefaultBaseBrightness,
    @FloatRange(from = 0.0) environmentGain: Float = DefaultEnvironmentGain,
    @FloatRange(from = 0.0, to = 1.0) opacity: Float = 1f,
    direction: HighlightDirection = DefaultDirection,
    disabledAngles: Set<ClosedFloatingPointRange<Float>> = DefaultDisabledAngles,
    disabledAngleDomain: HighlightAngleDomain = HighlightAngleDomain.Directed,
): Highlight.Contour =
    Highlight.Contour(
        width = width,
        blurRadius = blurRadius,
        baseBrightness = baseBrightness,
        environmentGain = environmentGain,
        opacity = opacity,
        direction = direction,
        disabledAngles = disabledAngles,
        disabledAngleDomain = disabledAngleDomain,
    )

private fun validateHighlightStroke(width: Dp, baseBrightness: Float, opacity: Float) {
    require(width.value.isFinite() && width.value >= 0f) {
        "width must be finite and non-negative"
    }
    require(baseBrightness.isFinite() && baseBrightness >= 0f) {
        "baseBrightness must be finite and non-negative"
    }
    require(opacity.isFinite() && opacity in 0f..1f) {
        "opacity must be finite and between 0 and 1"
    }
}

private class FrozenSet<T>(elements: Set<T>) : AbstractSet<T>() {
    private val values: Set<T> = elements.toSet()
    override val size: Int
        get() = values.size

    override fun contains(element: T): Boolean = values.contains(element)

    override fun iterator(): Iterator<T> = values.toList().iterator()
}

/** Appends a highlight using its white-light response. */
public fun BackdropImageScope.highlight(
    config: Highlight,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.Plus,
) {
    if (config === Highlight.None) return
    highlightWithConfig({ config }, null, alpha, blendMode)
}

/** Appends a contour highlight whose [environment] supplies color through one Gaussian blur. */
public fun BackdropImageScope.highlight(
    config: Highlight.Contour,
    environment: BackdropImage,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.Plus,
) {
    highlightWithConfig({ config }, environment, alpha, blendMode)
}

/**
 * Configures a contour highlight in declaration order. Drawing reads the visual parameters; the
 * Gaussian radius updates input sampling at resolution.
 */
public fun BackdropImageScope.highlight(
    environment: BackdropImage? = null,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.Plus,
    block: HighlightScope.() -> Unit,
) {
    highlightWithConfig({ Highlight(block) }, environment, alpha, blendMode)
}

internal fun BackdropImageScope.highlightWithConfig(
    config: () -> Highlight,
    environment: BackdropImage?,
    alpha: Float,
    blendMode: BlendMode,
) {
    requireAlpha(alpha)
    environment?.let(::checkImage)
    val highlight = EnvironmentHighlightPrimitive(config)
    if (environment == null || !isRuntimeShaderSupported()) {
        primitive(highlight, alpha = alpha, blendMode = blendMode)
        return
    }

    val environmentRadius =
        derivedStateOf(structuralEqualityPolicy()) {
            (config() as? Highlight.Contour)?.blurRadius
        }
    val filtered = image {
        replace(environment)
        effects { blur((environmentRadius.value ?: 0.dp).toPx()) }
    }
    val inputs = listOf(BackdropPrimitiveInput(filtered))
    drawPrimitive(
        RenderImageNode.DeferredPrimitive(listOf(filtered)) {
            RenderImageNode.Primitive(
                highlight,
                if (environmentRadius.value != null) inputs else emptyList(),
            )
        },
        alpha,
        blendMode,
    )
}

private data class EnvironmentHighlightPrimitive(
    val config: () -> Highlight,
) : BackdropPrimitive<EnvironmentHighlightPrimitive> {
    override fun bounds(geometry: BackdropGeometry): Rect = geometry.surfaceBounds

    override fun createRenderer(
        resources: BackdropRenderResources,
    ): BackdropPrimitiveRenderer<EnvironmentHighlightPrimitive> = Renderer(resources)

    private class Renderer(resources: BackdropRenderResources) :
        BackdropPrimitiveRenderer<EnvironmentHighlightPrimitive> {
        private val renderer = EnvironmentHighlightRenderer(resources)
        private var outlineShape: Shape? = null
        private var outlineProvider: ShapeProvider? = null

        override fun draw(
            scope: DrawScope,
            primitive: EnvironmentHighlightPrimitive,
            context: BackdropPrimitiveDrawContext,
        ) {
            val config = primitive.config()
            if (config === Highlight.None) {
                renderer.refresh()
                return
            }
            val shape = context.shape
            if (outlineShape != shape) {
                outlineShape = shape
                outlineProvider = ShapeProvider { shape }
            }
            renderer.draw(
                scope,
                config,
                checkNotNull(outlineProvider)
                    .shape
                    .createOutline(context.size, context.layoutDirection, context.density),
                context,
                environment =
                    if (config is Highlight.Contour && context.inputBounds.isNotEmpty())
                        context.input(0)
                    else null,
            )
        }

        override fun refresh() {
            renderer.refresh()
        }

        override fun release() {
            renderer.release()
        }
    }
}
