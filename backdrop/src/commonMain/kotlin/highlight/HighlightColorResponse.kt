package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import top.ltfan.backdrop.RuntimeShader

/** Controls how processed backdrop color tints the highlight while preserving its emission. */
@Stable
public data class HighlightColorResponse(
    public val amount: Float = .5f,
    public val transform: HighlightColorTransform = HighlightColorTransform.PreservePeak,
) {
    init {
        require(amount.isFinite() && amount in 0f..1f) {
            "amount must be finite and between 0 and 1"
        }
    }
}

/**
 * Maps base highlight and processed backdrop colors in linear extended-sRGB. The CPU conversion and
 * shader function must match; the renderer preserves the base alpha independently of RGB. Mutable
 * implementations keep their public properties snapshot-backed so drawing observes changes.
 */
@Stable
public interface HighlightColorTransform {
    public fun transform(baseColor: Color, sampleColor: Color): Color

    /** Declares `float3 transformHighlightColor(float3 base, float3 sampled)` in AGSL. */
    public val shaderSource: String

    public fun configureShader(shader: RuntimeShader) {}

    public companion object {
        @Stable public val PreservePeak: HighlightColorTransform = PreservePeakTransform
    }
}

@Immutable
private data object PreservePeakTransform : HighlightColorTransform {
    override val shaderSource: String =
        "float3 transformHighlightColor(float3 base, float3 sampled) { float bp = max(base.r, max(base.g, base.b)); float sp = max(sampled.r, max(sampled.g, sampled.b)); return (sp > 0.000001 && bp > 0.0 && abs(sp) <= 3.402823466e+38 && abs(bp) <= 3.402823466e+38) ? sampled * (bp / sp) : base; }"

    override fun transform(baseColor: Color, sampleColor: Color): Color {
        val base = baseColor.toHighlightLinearExtendedSrgb()
        val sample = sampleColor.toHighlightLinearExtendedSrgb()
        if (!base.red.isFinite() || !base.green.isFinite() || !base.blue.isFinite())
            return baseColor
        if (
            !sample.red.isFinite() ||
                !sample.green.isFinite() ||
                !sample.blue.isFinite() ||
                !sample.alpha.isFinite()
        )
            return baseColor
        val basePeak = maxOf(base.red, base.green, base.blue)
        val samplePeak = maxOf(sample.red, sample.green, sample.blue)
        if (
            !basePeak.isFinite() ||
                !samplePeak.isFinite() ||
                basePeak <= 0f ||
                samplePeak <= .000001f
        )
            return baseColor
        val scale = basePeak / samplePeak
        if (!scale.isFinite()) return baseColor
        return Color(
            encodeHighlightExtendedSrgb(sample.red * scale),
            encodeHighlightExtendedSrgb(sample.green * scale),
            encodeHighlightExtendedSrgb(sample.blue * scale),
            base.alpha,
            ColorSpaces.ExtendedSrgb,
        )
    }
}
