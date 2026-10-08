package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Draws a source over [samplingBounds], in pixels in the current drawing coordinate space.
 *
 * [size] is the surface size. The requested region can extend beyond it. Procedural sources must
 * draw every requested coordinate they define; finite raster sources use [drawSource] to declare
 * their available texels and their boundary behavior before any effect runs.
 */
public interface BackdropDrawScope : DrawScope {
    public val samplingBounds: Rect

    /**
     * Applies an invertible two-dimensional affine transform and maps [samplingBounds] into that
     * space.
     */
    public fun withTransform(transform: Matrix, draw: BackdropDrawScope.() -> Unit)

    /**
     * Draws a finite source with pixel-aligned [bounds] in its own coordinate space. [transform] is
     * an invertible two-dimensional affine matrix mapping that space into the current drawing
     * space. [tileMode] applies only outside the source rectangle; transparent pixels inside it
     * keep their premultiplied alpha and RGB.
     *
     * The consuming modifier owns temporary rendering resources. The draw callback receives the
     * source size, retains the declared source coordinates, is clipped to [bounds], and can be
     * invoked more than once. Edge extension requires runtime shader support; other platforms draw
     * the finite source with transparent exterior pixels.
     */
    public fun drawSource(
        bounds: Rect,
        tileMode: TileMode = TileMode.Clamp,
        transform: Matrix = Matrix(),
        draw: DrawScope.() -> Unit,
    )
}
