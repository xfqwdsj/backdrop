package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.BackdropCurve

/** A highlight decoration, either configured or explicitly disabled. */
@Stable
public sealed interface Highlight {

    /** Disables the decoration and releases its drawing layer. */
    @Immutable public data object None : Highlight

    /**
     * Parameters evaluated by the decoration's drawing node. [depthCurve] falls from one at the
     * outline to zero at the band's inner edge.
     */
    @Stable
    public data class Config(
        public val width: Dp = 2.dp,
        public val blurRadius: Dp = width / 2f,
        @param:FloatRange(from = 0.0, to = 1.0) public val alpha: Float = 1f,
        public val style: HighlightStyle = HighlightStyle.Default,
        public val direction: HighlightDirection = HighlightDirection(),
        public val depthCurve: BackdropCurve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
        public val exclusions: Set<HighlightExclusion> = emptySet(),
        /** Optional raw-backdrop edge light drawn over [style]. */
        public val environment: EnvironmentHighlight? = null,
    ) : Highlight {
        init {
            require(width.value.isFinite() && width.value >= 0f) {
                "width must be finite and non-negative"
            }
            require(blurRadius.value.isFinite() && blurRadius.value >= 0f) {
                "blurRadius must be finite and non-negative"
            }
            require(alpha.isFinite() && alpha in 0f..1f) {
                "alpha must be finite and between 0 and 1"
            }
        }
    }

    public companion object {

        @Stable public val Default: Config = Highlight()

        @Stable public val Ambient: Config = Highlight(style = HighlightStyle.Ambient)

        @Stable public val Plain: Config = Highlight(style = HighlightStyle.Plain)
    }
}

/** Creates a configured highlight decoration. */
@Suppress("FunctionName")
@Stable
public fun Highlight(
    width: Dp = 2.dp,
    blurRadius: Dp = width / 2f,
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1f,
    style: HighlightStyle = HighlightStyle.Default,
    direction: HighlightDirection = HighlightDirection(),
    depthCurve: BackdropCurve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
    exclusions: Set<HighlightExclusion> = emptySet(),
    environment: EnvironmentHighlight? = null,
): Highlight.Config =
    Highlight.Config(
        width = width,
        blurRadius = blurRadius,
        alpha = alpha,
        style = style,
        direction = direction,
        depthCurve = depthCurve,
        exclusions = exclusions.toSet(),
        environment = environment,
    )
