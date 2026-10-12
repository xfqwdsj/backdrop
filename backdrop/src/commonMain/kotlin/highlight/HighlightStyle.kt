package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import top.ltfan.backdrop.RuntimeShader

/**
 * Defines the color and per-boundary shading behavior of a static highlight. Implementations with
 * mutable public properties keep them snapshot-backed so draw observation invalidates correctly.
 */
@Stable
public interface HighlightStyle {
    public val color: Color
    public val blendMode: BlendMode

    /** Whether directional width and strength curves affect this style. */
    public val directional: Boolean
        get() = true

    /**
     * AGSL declarations and a `shadeHighlight` function returning straight-alpha RGBA. Define
     * `half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, float
     * distance, float width, half4 color)` and keep returned alpha in 0..1. [shade] must return the
     * matching straight-alpha color for CPU fallback. The shared pipeline applies directional
     * strength, depth coverage, premultiplication, and exclusions after this function. Prefix
     * custom uniforms and helper functions to avoid the shared names `origin`, `segments`,
     * `geometryScale`, `boundaryBounds`, `cornerRadii`, `width`, `angle`, `directional`,
     * `widthCurve`, `strengthCurve`, `depthCurve`, and `color`.
     */
    public val shaderSource: String

    /** Returns the straight-alpha color for one boundary sample, matching [shaderSource]. */
    public fun shade(sample: HighlightSample): Color

    /** Sets custom shader uniforms for the current draw. */
    public fun configureShader(shader: RuntimeShader) {}

    /** Disables the static pass while retaining an optional environment contribution. */
    @Immutable
    public data object None : HighlightStyle {
        override val color: Color = Color.Transparent
        override val blendMode: BlendMode = BlendMode.SrcOver
        override val directional: Boolean = false
        override val shaderSource: String = DefaultHighlightShader

        override fun shade(sample: HighlightSample): Color = Color.Transparent
    }

    /** A uniform fixed-width stroke. Directional width and strength curves do not affect it. */
    @Immutable
    public data class Plain(
        override val color: Color = Color.White.copy(alpha = 0.38f),
        override val blendMode: BlendMode = BlendMode.Plus,
    ) : HighlightStyle {
        override val directional: Boolean = false
        override val shaderSource: String = DefaultHighlightShader

        override fun shade(sample: HighlightSample): Color = color
    }

    /** A symmetric rim whose width and strength follow [Highlight.Config.direction]. */
    @Immutable
    public data class Default(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus,
    ) : HighlightStyle {
        override val shaderSource: String = DefaultHighlightShader

        override fun shade(sample: HighlightSample): Color = color
    }

    /**
     * A signed directional sheen following local outward normals. Positive projection lights the
     * boundary; negative projection emits black RGB with the same alpha.
     */
    @Immutable
    public data class Ambient(
        /** Sheen color; its alpha controls strength independently of the layer alpha. */
        override val color: Color = Color.White.copy(alpha = 0.38f),
        override val blendMode: BlendMode = DrawScope.DefaultBlendMode,
    ) : HighlightStyle {
        override val shaderSource: String = AmbientHighlightShader

        override fun shade(sample: HighlightSample): Color =
            if (sample.projection >= 0f) color else Color(0f, 0f, 0f, color.alpha, color.colorSpace)
    }

    public companion object {
        @Stable public val Default: Default = Default()
        @Stable public val Ambient: Ambient = Ambient()
        @Stable public val Plain: Plain = Plain()
    }
}

/** Boundary data shared by custom CPU and AGSL highlight implementations. */
@Immutable
public data class HighlightSample(
    /** Surface-local pixel position. */
    public val position: Offset,
    /** Unit outward normal in surface-local coordinates. */
    public val outwardNormal: Offset,
    /** Signed incidence along [Highlight.Config.direction], in -1..1. */
    public val projection: Float,
    /** Distance inward from the outline, in pixels. */
    public val distance: Float,
    /** Direction-adjusted base band width, in pixels. */
    public val width: Float,
)

private const val DefaultHighlightShader: String =
    "half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, float distance, float width, half4 color) { return color; }"

private const val AmbientHighlightShader: String =
    "half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, float distance, float width, half4 color) { return half4(color.rgb * step(0.0, projection), color.a); }"
