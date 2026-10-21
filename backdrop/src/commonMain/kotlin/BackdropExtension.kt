package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Per-side recording room in pixels, combining visible coverage with effect sampling needs. Layout
 * and foreground geometry retain the surface's own bounds. Drawing callbacks are clipped to visible
 * coverage; sampling room supplies the neighboring pixels required by effects. Add [originInNode]
 * to layer coordinates to recover surface-local coordinates.
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
 * Surface-local drawing geometry and the resolved recording extension. [size] is the surface size
 * when a callback runs in an expanded recording layer.
 */
@Immutable
public data class BackdropRoom(
    public val size: Size,
    public val extension: BackdropExtension,
)
