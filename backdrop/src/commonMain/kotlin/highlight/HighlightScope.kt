package top.ltfan.backdrop.highlight

import androidx.compose.ui.unit.Dp
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropRenderDsl

/** Builds one immutable highlight configuration; omitted values use [Highlight.Default]. */
@BackdropRenderDsl
public class HighlightScope internal constructor() {
    private val defaults = Highlight.Default
    public var width: Dp = defaults.width
    public var falloff: BackdropCurve = defaults.falloff
    public var direction: HighlightDirection = defaults.direction
    public var style: HighlightStyle = defaults.style
    public var colorResponse: HighlightColorResponse = defaults.colorResponse
    public var opacity: Float = defaults.opacity
    public var disabledAngles: Set<ClosedFloatingPointRange<Float>> = emptySet()
    public var disabledAngleDomain: HighlightAngleDomain = HighlightAngleDomain.Directed

    internal fun build(): Highlight.Config =
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
}

/** Configures a highlight using the same defaults and validation as its parameter constructor. */
@Suppress("FunctionName")
public fun Highlight(block: HighlightScope.() -> Unit): Highlight.Config =
    HighlightScope().apply(block).build()
