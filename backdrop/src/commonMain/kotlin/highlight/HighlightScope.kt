package top.ltfan.backdrop.highlight

import androidx.compose.ui.unit.Dp
import top.ltfan.backdrop.BackdropRenderDsl

/** Builds one immutable contour highlight; omitted values use [Highlight.Default]. */
@BackdropRenderDsl
public class HighlightScope internal constructor() {
    private val defaults = Highlight.Default
    public var width: Dp = defaults.width
    public var blurRadius: Dp = defaults.blurRadius
    public var baseBrightness: Float = defaults.baseBrightness
    public var environmentGain: Float = defaults.environmentGain
    public var opacity: Float = defaults.opacity
    public var direction: HighlightDirection = defaults.direction
    public var disabledAngles: Set<ClosedFloatingPointRange<Float>> = emptySet()
    public var disabledAngleDomain: HighlightAngleDomain = HighlightAngleDomain.Directed

    internal fun build(): Highlight.Contour =
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
}

/**
 * Configures a contour highlight using the same defaults and validation as its parameter
 * constructor.
 */
@Suppress("FunctionName")
public fun Highlight(block: HighlightScope.() -> Unit): Highlight.Contour =
    HighlightScope().apply(block).build()
