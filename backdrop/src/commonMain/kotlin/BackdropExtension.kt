package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * How far the effect layer reaches beyond the surface's own rectangle, per side, in pixels: what
 * the caller asked to cover, plus the room the effect chain needs in order to sample it. The
 * surface keeps its own rectangle, coordinate space and shape; this is extra room, not a new space.
 *
 * The room an effect needs is sampling headroom that stays outside the area the caller asked to
 * cover, so a blur reads the pixels it blends without widening the surface. [BackdropEffectScope]
 * reports this total while the drawing callbacks are clipped to the requested part.
 *
 * A shader receives coordinates in the effect layer, whose origin sits this far before the
 * surface's, so an effect that maps coordinates adds [originInNode] to work in the surface's own
 * space.
 */
@Immutable
public data class BackdropExtension(
    public val left: Float = 0f,
    public val top: Float = 0f,
    public val right: Float = 0f,
    public val bottom: Float = 0f,
) {
    /** Whether the effects reach past the surface at all. */
    public val isZero: Boolean
        get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    /** The extended area's top-left in the surface's coordinate space. */
    public val originInNode: Offset
        get() = Offset(-left, -top)

    public companion object {

        /** The effects stay inside the surface's own rectangle. */
        @Stable public val None: BackdropExtension = BackdropExtension()
    }
}

/**
 * What a drawing callback knows about its surface: the surface's own rectangle, which is also the
 * coordinate space the callback draws in, and how far the effect layer reaches beyond it.
 *
 * The size is carried here because an exported backdrop records the layer, whose drawing scope is
 * the extended area rather than the surface; a callback that measures or positions against the
 * surface reads this instead of the drawing scope.
 */
@Immutable
public data class BackdropRoom(
    public val size: Size,
    public val extension: BackdropExtension,
)
