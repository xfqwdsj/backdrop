package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Light sampled from the raw backdrop along each outline's outward normal. [blurRadius] prefilters
 * that source before edge shading so the generated band retains its configured depth and
 * exclusions. Filtered color follows the platform's blur color support.
 */
@Immutable
public data class EnvironmentHighlight(
    public val sampleDistance: Dp = 16.dp,
    /**
     * Gaussian blur applied to the raw backdrop before environment edge shading; zero disables it.
     */
    public val blurRadius: Dp = 4.dp,
    public val brightness: Contribution = Contribution(0.2f, 0.2f),
    public val tint: Contribution = Contribution(0.08f, 0.05f),
    public val thickness: Thickness = Thickness(),
    public val blendMode: BlendMode = BlendMode.Plus,
) {
    init {
        require(sampleDistance.value.isFinite() && sampleDistance.value > 0f) {
            "sampleDistance must be finite and positive"
        }
        require(blurRadius.value.isFinite() && blurRadius.value >= 0f) {
            "blurRadius must be finite and non-negative"
        }
    }

    /** A sampled channel and the brightness at which it begins to contribute. */
    @Immutable
    public data class Contribution(
        @param:FloatRange(from = 0.0, to = 1.0) public val strength: Float,
        @param:FloatRange(from = 0.0) public val threshold: Float,
    ) {
        init {
            require(strength.isFinite() && strength in 0f..1f) {
                "strength must be finite and between 0 and 1"
            }
            require(threshold.isFinite() && threshold >= 0f) {
                "threshold must be finite and non-negative"
            }
        }
    }

    /** A thresholded geometric width extension for brightness and tint contributions. */
    @Immutable
    public data class Thickness(
        public val maxWidth: Dp = 1.dp,
        @param:FloatRange(from = 0.0) public val threshold: Float = 0.2f,
    ) {
        init {
            require(maxWidth.value.isFinite() && maxWidth.value >= 0f) {
                "maxWidth must be finite and non-negative"
            }
            require(threshold.isFinite() && threshold >= 0f) {
                "threshold must be finite and non-negative"
            }
        }
    }
}
