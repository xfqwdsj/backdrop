package top.ltfan.backdrop

import androidx.compose.ui.unit.LayoutDirection

/** The edge a caller asks a surface to ramp from, in reading order where that matters. */
public enum class BackdropEdge {
    Top,
    Bottom,
    Start,
    End,
}

/**
 * The physical edge this request names in [layoutDirection]: a vertical request is already
 * physical, and a reading-order request becomes the side it names there.
 */
public fun BackdropEdge.side(layoutDirection: LayoutDirection): BackdropSide =
    when (this) {
        BackdropEdge.Top -> BackdropSide.Top
        BackdropEdge.Bottom -> BackdropSide.Bottom
        BackdropEdge.Start ->
            if (layoutDirection == LayoutDirection.Ltr) BackdropSide.Left else BackdropSide.Right
        BackdropEdge.End ->
            if (layoutDirection == LayoutDirection.Ltr) BackdropSide.Right else BackdropSide.Left
    }

/**
 * A physical edge of a surface: the side a ramp starts at, whichever way the reading order runs.
 */
public enum class BackdropSide {
    Top,
    Bottom,
    Left,
    Right;

    /** Whether this edge selects the surface's height as its ramp axis: top and bottom do. */
    public val isVertical: Boolean
        get() = this == Top || this == Bottom

    /** Whether ramp coordinates reflect across the surface thickness, as for top and left edges. */
    public val carriesContent: Boolean
        get() = this == Top || this == Left
}
