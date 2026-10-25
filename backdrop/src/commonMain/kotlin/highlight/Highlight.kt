package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropGeometry
import top.ltfan.backdrop.BackdropImage
import top.ltfan.backdrop.BackdropImageScope
import top.ltfan.backdrop.BackdropPrimitive
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropPrimitiveInput
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropRenderResources
import top.ltfan.backdrop.BackdropSurfaceScope
import top.ltfan.backdrop.PrimitiveContext
import top.ltfan.backdrop.ProcessedBackdropFrame
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.primitive

private val DefaultWidth = 2.dp
private val DefaultStyle = HighlightStyle.Solid
private val DefaultDirection = HighlightDirection()
private val DefaultColorResponse = HighlightColorResponse()
private val DefaultHighlightFalloff = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f)
private val DefaultDisabledAngles
    get() = emptySet<ClosedFloatingPointRange<Float>>()

/** Parameters for a contour highlight image. */
@Stable
public sealed interface Highlight {
    /** Omits the contour primitive and releases its rendering resources. */
    @Stable public data object None : Highlight

    @Stable
    public class Config
    public constructor(
        public val width: Dp = DefaultWidth,
        public val falloff: BackdropCurve = DefaultHighlightFalloff,
        public val direction: HighlightDirection = DefaultDirection,
        public val style: HighlightStyle = DefaultStyle,
        public val colorResponse: HighlightColorResponse = DefaultColorResponse,
        @param:FloatRange(from = 0.0, to = 1.0) public val opacity: Float = 1f,
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
            for (range in this@Config.disabledAngles) {
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
            require(width.value.isFinite() && width.value >= 0f) {
                "width must be finite and non-negative"
            }
            require(opacity.isFinite() && opacity in 0f..1f) {
                "opacity must be finite and between 0 and 1"
            }
        }

        public fun copy(
            width: Dp = this.width,
            falloff: BackdropCurve = this.falloff,
            direction: HighlightDirection = this.direction,
            style: HighlightStyle = this.style,
            colorResponse: HighlightColorResponse = this.colorResponse,
            opacity: Float = this.opacity,
            disabledAngles: Set<ClosedFloatingPointRange<Float>> = this.disabledAngles,
            disabledAngleDomain: HighlightAngleDomain = this.disabledAngleDomain,
        ): Config =
            Highlight(
                width,
                falloff,
                direction,
                style,
                colorResponse,
                opacity,
                disabledAngles,
                disabledAngleDomain,
            )

        public operator fun component1(): Dp = width

        public operator fun component2(): BackdropCurve = falloff

        public operator fun component3(): HighlightDirection = direction

        public operator fun component4(): HighlightStyle = style

        public operator fun component5(): HighlightColorResponse = colorResponse

        public operator fun component6(): Float = opacity

        public operator fun component7(): Set<ClosedFloatingPointRange<Float>> = disabledAngles

        public operator fun component8(): HighlightAngleDomain = disabledAngleDomain

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Config &&
                    width == other.width &&
                    falloff == other.falloff &&
                    direction == other.direction &&
                    style == other.style &&
                    colorResponse == other.colorResponse &&
                    opacity == other.opacity &&
                    disabledAngles == other.disabledAngles &&
                    disabledAngleDomain == other.disabledAngleDomain)

        override fun hashCode(): Int {
            var result = width.hashCode()
            result = 31 * result + falloff.hashCode()
            result = 31 * result + direction.hashCode()
            result = 31 * result + style.hashCode()
            result = 31 * result + colorResponse.hashCode()
            result = 31 * result + opacity.hashCode()
            result = 31 * result + disabledAngles.hashCode()
            result = 31 * result + disabledAngleDomain.hashCode()
            return result
        }

        override fun toString(): String =
            "Highlight(width=$width, falloff=$falloff, direction=$direction, style=$style, colorResponse=$colorResponse, opacity=$opacity, disabledAngles=$disabledAngles, disabledAngleDomain=$disabledAngleDomain)"
    }

    public companion object {
        @Stable public val Default: Config = Highlight()
        @Stable public val Ambient: Config = Highlight(style = HighlightStyle.TwoTone)
        @Stable public val Plain: Config = Highlight(direction = HighlightDirection.Uniform)
    }
}

@Suppress("FunctionName")
public fun Highlight(
    width: Dp = DefaultWidth,
    falloff: BackdropCurve = DefaultHighlightFalloff,
    direction: HighlightDirection = DefaultDirection,
    style: HighlightStyle = DefaultStyle,
    colorResponse: HighlightColorResponse = DefaultColorResponse,
    @FloatRange(from = 0.0, to = 1.0) opacity: Float = 1f,
    disabledAngles: Set<ClosedFloatingPointRange<Float>> = DefaultDisabledAngles,
    disabledAngleDomain: HighlightAngleDomain = HighlightAngleDomain.Directed,
): Highlight.Config =
    Highlight.Config(
        width,
        falloff,
        direction,
        style,
        colorResponse,
        opacity,
        disabledAngles,
        disabledAngleDomain,
    )

private class FrozenSet<T>(elements: Set<T>) : AbstractSet<T>() {
    private val values: Set<T> = elements.toSet()
    override val size: Int
        get() = values.size

    override fun contains(element: T): Boolean = values.contains(element)

    override fun iterator(): Iterator<T> = values.toList().iterator()
}

/** Draws a contour primitive with the selected color source and compositing parameters. */
public fun BackdropImageScope.highlight(
    config: Highlight,
    colorSource: BackdropImage? = null,
    interactionLighting: InteractionLighting = InteractionLighting.None,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    if (config === Highlight.None) return
    config as Highlight.Config
    colorSource?.let(::checkImage)
    require(config.colorResponse.amount == 0f || colorSource != null) {
        "A highlight with color response requires an explicit colorSource"
    }
    primitive(
        HighlightPrimitive(config, interactionLighting),
        inputs = colorSource?.let { listOf(BackdropPrimitiveInput(it)) }.orEmpty(),
        alpha = alpha,
        blendMode = blendMode,
    )
}

/** Configures and draws a contour primitive in declaration order. */
public fun BackdropImageScope.highlight(
    colorSource: BackdropImage? = null,
    interactionLighting: InteractionLighting = InteractionLighting.None,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
    block: HighlightScope.() -> Unit,
) {
    highlight(Highlight(block), colorSource, interactionLighting, alpha, blendMode)
}

private data class HighlightPrimitive(
    val config: Highlight.Config,
    val lighting: InteractionLighting,
) : BackdropPrimitive<HighlightPrimitive> {
    override val compositingStrategy: CompositingStrategy = CompositingStrategy.Auto

    override fun bounds(geometry: BackdropGeometry): Rect = geometry.surfaceBounds

    override fun createRenderer(
        resources: BackdropRenderResources,
    ): BackdropPrimitiveRenderer<HighlightPrimitive> = Renderer(resources)

    private class Renderer(resources: BackdropRenderResources) :
        BackdropPrimitiveRenderer<HighlightPrimitive> {
        private val renderer = HighlightRenderer(resources)
        private val frame = ProcessedBackdropFrame()
        private var outlineShape: Shape? = null
        private var outlineProvider: ShapeProvider? = null

        override fun draw(
            scope: DrawScope,
            primitive: HighlightPrimitive,
            context: BackdropPrimitiveDrawContext,
        ) {
            val shape = context.shape
            if (outlineShape != shape) {
                outlineShape = shape
                outlineProvider = ShapeProvider { shape }
            }
            val interactions = (context as PrimitiveContext).interactionFrame
            frame.begin(context.inputBounds.isNotEmpty())
            if (context.inputBounds.isNotEmpty()) frame.publish(context.input(0))
            frame.publishInteraction(
                interactions.interactionLights,
                interactions.receiverWindow,
                interactions.receiverCoordinates,
            )
            try {
                renderer.draw(
                    scope,
                    primitive.config,
                    checkNotNull(outlineProvider)
                        .shape
                        .createOutline(
                            context.size,
                            context.layoutDirection,
                            context.density,
                        ),
                    frame,
                    (primitive.lighting as? InteractionLighting.Config)?.contourOpacity ?: 0f,
                )
            } finally {
                frame.clear()
            }
        }

        override fun refresh() {
            renderer.refresh()
        }

        override fun release() {
            renderer.release()
        }
    }
}

/** Configures the contour phase of a fixed-order surface declaration. */
public fun BackdropSurfaceScope.highlight(block: HighlightScope.() -> Unit) {
    highlight(Highlight(block))
}
