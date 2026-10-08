package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Adds edge light sampled along the outward boundary normal of the raw backdrop. Three taps reach
 * up to [sampleDistance] beyond the boundary; their average color supplies the light. Sampling uses
 * a separate layer, independent of the surface effect chain. Sampled extended-range RGB passes
 * through the shader; [strength] scales its premultiplied RGB and alpha together.
 *
 * The edge band shares [Highlight.Config.width], and [Highlight.Config.alpha] controls its layer
 * opacity. [Highlight.Config.blurRadius] affects the static style. Static color alpha and
 * [strength] control their respective contributions independently.
 *
 * Rectangular and normalized rounded-rectangle outlines with circular corners are supported. Other
 * outlines fail when this pass is active. Platforms without runtime shaders retain the static
 * style.
 */
@Immutable
public data class EnvironmentHighlight(
    /** Maximum sampling distance beyond the shape boundary. */
    public val sampleDistance: Dp = 16.dp,
    /** Contribution of the sampled environment to the edge light. */
    @param:FloatRange(from = 0.0, to = 1.0) public val strength: Float = 0.5f,
    /** Minimum working-space brightness that contributes to the edge light. */
    @param:FloatRange(from = 0.0) public val threshold: Float = 0.2f,
    /** Blend mode used to combine the environment light with the configured highlight. */
    public val blendMode: BlendMode = BlendMode.Plus,
) {
    init {
        require(sampleDistance.value.isFinite() && sampleDistance.value > 0f) {
            "sampleDistance must be finite and positive"
        }
        require(strength.isFinite() && strength in 0f..1f) {
            "strength must be finite and between 0 and 1"
        }
        require(threshold.isFinite() && threshold >= 0f) {
            "threshold must be finite and non-negative"
        }
    }
}
