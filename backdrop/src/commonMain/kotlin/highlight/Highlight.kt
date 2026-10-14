package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.BackdropCurve

private val DefaultWidth = 2.dp
private val DefaultStyle = HighlightStyle.Solid
private val DefaultDirection = HighlightDirection()
private val DefaultColorResponse = HighlightColorResponse()
private val DefaultHighlightFalloff = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f)
private val DefaultExclusions
    get() = emptySet<HighlightExclusion>()

/** A highlight decoration, configured or explicitly disabled. */
@Stable
public sealed interface Highlight {
    @Stable public data object None : Highlight

    /** Configures a fixed-width highlight along the nearest outline boundary. */
    @Stable
    public class Config
    public constructor(
        public val width: Dp = DefaultWidth,
        public val falloff: BackdropCurve = DefaultHighlightFalloff,
        public val direction: HighlightDirection = DefaultDirection,
        public val style: HighlightStyle = DefaultStyle,
        public val colorResponse: HighlightColorResponse = DefaultColorResponse,
        @param:FloatRange(from = 0.0, to = 1.0) public val opacity: Float = 1f,
        public val blendMode: BlendMode = BlendMode.SrcOver,
        exclusions: Set<HighlightExclusion> = DefaultExclusions,
    ) : Highlight {
        private val storedExclusions: Set<HighlightExclusion> = FrozenSet(exclusions)
        public val exclusions: Set<HighlightExclusion>
            get() = storedExclusions

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
            blendMode: BlendMode = this.blendMode,
            exclusions: Set<HighlightExclusion> = this.exclusions,
        ): Config =
            Config(width, falloff, direction, style, colorResponse, opacity, blendMode, exclusions)

        public operator fun component1(): Dp = width

        public operator fun component2(): BackdropCurve = falloff

        public operator fun component3(): HighlightDirection = direction

        public operator fun component4(): HighlightStyle = style

        public operator fun component5(): HighlightColorResponse = colorResponse

        public operator fun component6(): Float = opacity

        public operator fun component7(): BlendMode = blendMode

        public operator fun component8(): Set<HighlightExclusion> = exclusions

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Config &&
                    width == other.width &&
                    falloff == other.falloff &&
                    direction == other.direction &&
                    style == other.style &&
                    colorResponse == other.colorResponse &&
                    opacity == other.opacity &&
                    blendMode == other.blendMode &&
                    exclusions == other.exclusions)

        override fun hashCode(): Int {
            var result = width.hashCode()
            result = 31 * result + falloff.hashCode()
            result = 31 * result + direction.hashCode()
            result = 31 * result + style.hashCode()
            result = 31 * result + colorResponse.hashCode()
            result = 31 * result + opacity.hashCode()
            result = 31 * result + blendMode.hashCode()
            result = 31 * result + exclusions.hashCode()
            return result
        }

        override fun toString(): String =
            "Config(width=$width, falloff=$falloff, direction=$direction, style=$style, colorResponse=$colorResponse, opacity=$opacity, blendMode=$blendMode, exclusions=$exclusions)"
    }

    public companion object {
        @Stable public val Default: Config = Highlight()
        @Stable public val Ambient: Config = Highlight(style = HighlightStyle.TwoTone)
        @Stable public val Plain: Config = Highlight(direction = HighlightDirection.None)
    }
}

private class FrozenSet<T>(elements: Set<T>) : AbstractSet<T>() {
    private val values: Set<T> = elements.toSet()
    override val size: Int
        get() = values.size

    override fun contains(element: T): Boolean = values.contains(element)

    override fun iterator(): Iterator<T> = values.toList().iterator()
}

@Suppress("FunctionName")
@Stable
public fun Highlight(
    width: Dp = DefaultWidth,
    falloff: BackdropCurve = DefaultHighlightFalloff,
    direction: HighlightDirection = DefaultDirection,
    style: HighlightStyle = DefaultStyle,
    colorResponse: HighlightColorResponse = DefaultColorResponse,
    @FloatRange(from = 0.0, to = 1.0) opacity: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
    exclusions: Set<HighlightExclusion> = DefaultExclusions,
): Highlight.Config =
    Highlight.Config(
        width,
        falloff,
        direction,
        style,
        colorResponse,
        opacity,
        blendMode,
        exclusions,
    )
