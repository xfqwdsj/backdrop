package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far a surface's effects may run outside the surface's own rectangle, per side. The rectangle
 * the surface occupies does not change: layout, hit testing and any size the caller measures stay
 * the same, while the effects and drawing callbacks cover the requested region. Positive values
 * extend a side and negative values contract it; content keeps its own shape.
 *
 * A side is physical: [left] is always the left edge, whatever the layout direction. Callers that
 * describe an edge in reading order resolve it to a physical side themselves.
 *
 * This request adds to the coverage declared by the effect chain. Sampling room is added outside
 * that visible region so effects can read neighboring pixels without widening what is drawn.
 */
@Immutable
public data class BackdropInsets(
    public val left: Dp = 0.dp,
    public val top: Dp = 0.dp,
    public val right: Dp = 0.dp,
    public val bottom: Dp = 0.dp,
) {
    /** Whether every side is zero, so the region is the surface's own rectangle. */
    public val isZero: Boolean
        get() = left == 0.dp && top == 0.dp && right == 0.dp && bottom == 0.dp

    public companion object {

        /** No extension: the effect region is the surface's own rectangle. */
        @Stable public val None: BackdropInsets = BackdropInsets()

        /** The same inset on all four sides. */
        public fun all(inset: Dp): BackdropInsets =
            BackdropInsets(left = inset, top = inset, right = inset, bottom = inset)

        /** Insets on the top and bottom sides, for an effect that ramps along the vertical axis. */
        public fun vertical(top: Dp = 0.dp, bottom: Dp = 0.dp): BackdropInsets =
            BackdropInsets(top = top, bottom = bottom)

        /**
         * Insets on the left and right sides, for an effect that ramps along the horizontal axis.
         */
        public fun horizontal(left: Dp = 0.dp, right: Dp = 0.dp): BackdropInsets =
            BackdropInsets(left = left, right = right)
    }
}
