package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect

/**
 * Declares the input region an effect needs to produce an output region.
 *
 * Both rectangles use pixel coordinates relative to the surface's top-left, independent of the
 * effect layer's expanded origin. Return a finite, non-inverted rectangle containing every input
 * coordinate the effect can read for any point in the output region. Include the full interpolation
 * footprint, such as neighboring texels read by bilinear sampling. The returned region may exclude
 * the output region, as with a translated or remapped lookup.
 *
 * Backdrop evaluates declarations from the last effect to the first, so sampling regions for serial
 * stages accumulate. It unions the output and every intermediate input region to derive the
 * recording bounds. Implementations should be deterministic for a given output rectangle.
 */
@Stable
public fun interface BackdropSampling {
    /** Returns all input coordinates needed to produce [output]. */
    public fun requiredInput(output: Rect): Rect

    public companion object {
        /**
         * The effect reads each output pixel at the same coordinate and adds no sampling margin.
         */
        @Stable public val Identity: BackdropSampling = IdentitySampling

        /**
         * Declares a fixed rectangular sampling footprint around each output pixel. Each outset is
         * measured in pixels and must be finite and non-negative.
         */
        public fun outsets(left: Float, top: Float, right: Float, bottom: Float): BackdropSampling {
            require(left.isFinite() && left >= 0f)
            require(top.isFinite() && top >= 0f)
            require(right.isFinite() && right >= 0f)
            require(bottom.isFinite() && bottom >= 0f)
            return Outsets(left, top, right, bottom)
        }

        /**
         * Maps an output region to the same region translated by [dx] and [dy], in surface pixels.
         * The resulting input region can lie wholly or partly outside the output region. This
         * models coordinate translation; a lookup with interpolation must additionally declare its
         * neighboring texels, especially when the translation is fractional.
         */
        public fun translated(dx: Float, dy: Float): BackdropSampling {
            require(dx.isFinite() && dy.isFinite())
            return Translation(dx, dy)
        }
    }
}

@Immutable
private object IdentitySampling : BackdropSampling {
    override fun requiredInput(output: Rect): Rect = output
}

@Immutable
private data class Outsets(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : BackdropSampling {
    override fun requiredInput(output: Rect): Rect =
        Rect(output.left - left, output.top - top, output.right + right, output.bottom + bottom)
}

@Immutable
private data class Translation(val dx: Float, val dy: Float) : BackdropSampling {
    override fun requiredInput(output: Rect): Rect =
        Rect(output.left + dx, output.top + dy, output.right + dx, output.bottom + dy)
}
