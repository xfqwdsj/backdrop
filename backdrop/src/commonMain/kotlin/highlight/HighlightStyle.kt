package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import top.ltfan.backdrop.RuntimeShader

/** Straight-alpha light emitted by the nearest-contour highlight sample. */
@Immutable
public data class HighlightEmission(public val color: Color, public val strength: Float = 1f) {
    init {
        require(strength.isFinite() && strength >= 0f) {
            "strength must be finite and non-negative"
        }
    }
}

/**
 * Produces straight-alpha color and non-negative emission strength for the nearest boundary sample.
 * Custom implementations with mutable properties keep them snapshot-backed so drawing observes
 * changes. [shaderSource] declares `half4 shadeHighlight(float2 position, float2 outwardNormal,
 * float projection, half4 color)` and `float highlightStrength(float2 position, float2
 * outwardNormal, float projection)`. The returned color is straight alpha; the strength is
 * independent of color luminance, so black samples retain emission strength. [shade] and both
 * shader functions must match. GPU and CPU rendering evaluate their matching shading functions once
 * per covered sample; the direction-scaled contour profile shares that response. The sample `width`
 * and GPU uniform `width` remain the configured base width; direction scaling changes only
 * coverage. Prefix custom uniforms and helpers to avoid the shared shader names `position`,
 * `outwardNormal`, `projection`, `color`, `origin`, `geometryScale`, `segments`, `width`,
 * `directionEnabled`, `directionMode`, `directionVector`, `directionStrengthRange`,
 * `directionStrengthCurve`, `directionWidthRange`, `directionWidthCurve`, `falloffCurve`,
 * `tileBounds`, `exclusionCount`, `exclusions`, `content`, `colorResponseAmount`, and
 * `transformHighlightColor`.
 */
@Stable
public interface HighlightStyle {
    public val color: Color
    public val shaderSource: String

    public fun shade(sample: HighlightSample): HighlightEmission

    public fun configureShader(shader: RuntimeShader) {}

    @Immutable
    public data object None : HighlightStyle {
        override val color: Color = Color.White
        override val shaderSource: String = NoHighlightShader

        override fun shade(sample: HighlightSample): HighlightEmission =
            HighlightEmission(Color.White, 0f)
    }

    @Immutable
    public data class Solid(override val color: Color = Color.White.copy(alpha = .32f)) :
        HighlightStyle {
        override val shaderSource: String = DefaultHighlightShader

        override fun shade(sample: HighlightSample): HighlightEmission = HighlightEmission(color)
    }

    @Immutable
    public data class TwoTone(override val color: Color = Color.White.copy(alpha = .32f)) :
        HighlightStyle {
        override val shaderSource: String = TwoToneHighlightShader

        override fun shade(sample: HighlightSample): HighlightEmission =
            HighlightEmission(
                if (sample.projection >= 0f) color
                else Color(0f, 0f, 0f, color.alpha, color.colorSpace)
            )
    }

    public companion object {
        @Stable public val Solid: Solid = Solid()
        @Stable public val TwoTone: TwoTone = TwoTone()
    }
}

/** Surface-local point, normal, and signed directional incidence at the nearest boundary sample. */
@Immutable
public data class HighlightSample(
    public val position: Offset,
    public val outwardNormal: Offset,
    public val projection: Float,
    public val width: Float = 0f,
)

private const val DefaultHighlightShader =
    "half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, half4 color) { return color; }\nfloat highlightStrength(float2 position, float2 outwardNormal, float projection) { return 1.0; }"
private const val TwoToneHighlightShader =
    "half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, half4 color) { return half4(color.rgb * step(0.0, projection), color.a); }\nfloat highlightStrength(float2 position, float2 outwardNormal, float projection) { return 1.0; }"
private const val NoHighlightShader =
    "half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, half4 color) { return color; }\nfloat highlightStrength(float2 position, float2 outwardNormal, float projection) { return 0.0; }"
