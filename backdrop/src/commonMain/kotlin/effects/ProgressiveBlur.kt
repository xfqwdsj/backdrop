package top.ltfan.backdrop.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.exp
import top.ltfan.backdrop.BackdropAxis
import top.ltfan.backdrop.BackdropCurveShader
import top.ltfan.backdrop.BackdropEdge
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.BackdropInsets
import top.ltfan.backdrop.BackdropRamp
import top.ltfan.backdrop.BackdropSampling
import top.ltfan.backdrop.BackdropSide
import top.ltfan.backdrop.backdropRampEnds
import top.ltfan.backdrop.isRuntimeShaderSupported
import top.ltfan.backdrop.shaderControls
import top.ltfan.backdrop.side

/**
 * Strength of a progressive ramp at [position], which is measured from the edge the ramp runs from:
 * one at that edge and zero at the opposite one. This is the same profile [progressiveBlur] sends
 * to its shader, so a mask that draws it stays aligned with the blur it covers.
 */
public fun progressiveBackdropStrength(
    position: Float,
    ramp: BackdropRamp = BackdropRamp(),
): Float = ramp.intensityAt(1f - position)

/**
 * Applies spatially varying blur controlled by [ramp] along [edge]. Anchors determine the
 * transition span and any coverage beyond the surface; changing drawing insets clips it without
 * moving it.
 *
 * Radius must be finite and non-negative. Ramp endpoint intensities must be finite and in `0..1`,
 * and anchors must resolve to a finite increasing span so sampling bounds remain well-defined. The
 * shader evaluates the shared curve per pixel. When runtime shaders are unavailable, this operation
 * preserves its input and requests no coverage. Zero radius behaves the same way. Radius and
 * endpoint intensities are validated before either early return; anchors are resolved only when the
 * shader algorithm runs.
 */
public fun BackdropEffectScope.progressiveBlur(
    radius: Float,
    edge: BackdropEdge,
    ramp: BackdropRamp = BackdropRamp(),
) {
    progressiveBlurImpl(radius, edge, ramp, isRuntimeShaderSupported())
}

internal fun BackdropEffectScope.progressiveBlurImpl(
    radius: Float,
    edge: BackdropEdge,
    ramp: BackdropRamp,
    supported: Boolean,
) {
    validateProgressiveBlur(radius, ramp)
    val curve = ramp.curve
    val startIntensity = ramp.from
    val endIntensity = ramp.to
    if (radius == 0f || !supported) return

    val geometry = progressiveBlurGeometry(size, layoutDirection, edge, ramp) ?: return
    val vertical = geometry.side.isVertical
    val axis = if (vertical) 1f else 0f
    cover(geometry.coverage)
    val anchorStart = if (vertical) geometry.start.y else geometry.start.x
    val anchorEnd = if (vertical) geometry.end.y else geometry.end.x
    val verticalSampling =
        progressiveBlurSampling(true, vertical, radius, ramp, anchorStart, anchorEnd)
    runtimeShaderEffect(
        key = "ProgressiveBlurVertical",
        shaderString = ProgressiveBlurVerticalShader,
        uniformShaderName = "content",
        sampling = verticalSampling,
    ) {
        val room = extension
        val offset = if (vertical) room.top else room.left
        setFloatUniform("anchors", anchorStart + offset, anchorEnd + offset)
        setFloatUniform("blurRadius", radius)
        setFloatUniform("axis", axis)
        setFloatUniform("curve", curve.shaderControls())
        setFloatUniform("intensities", startIntensity, endIntensity)
    }
    runtimeShaderEffect(
        key = "ProgressiveBlurHorizontal",
        shaderString = ProgressiveBlurHorizontalShader,
        uniformShaderName = "content",
        sampling =
            progressiveBlurSampling(
                false,
                vertical,
                radius,
                ramp,
                anchorStart,
                anchorEnd,
            ),
    ) {
        val room = extension
        val offset = if (vertical) room.top else room.left
        setFloatUniform("anchors", anchorStart + offset, anchorEnd + offset)
        setFloatUniform("blurRadius", radius)
        setFloatUniform("axis", axis)
        setFloatUniform("curve", curve.shaderControls())
        setFloatUniform("intensities", startIntensity, endIntensity)
    }
}

internal fun progressiveBlurSampling(
    vertical: Boolean,
    verticalRamp: Boolean,
    radius: Float,
    ramp: BackdropRamp,
    anchorStart: Float,
    anchorEnd: Float,
): BackdropSampling = BackdropSampling { output ->
    val rampStart = if (verticalRamp) output.top else output.left
    val rampEnd = if (verticalRamp) output.bottom else output.right
    val first = ramp.intensityAt((rampStart - anchorStart) / (anchorEnd - anchorStart))
    val second = ramp.intensityAt((rampEnd - anchorStart) / (anchorEnd - anchorStart))
    val intensity = maxOf(first, second)
    if (intensity <= 0f) return@BackdropSampling output
    val support = progressiveBlurKernelSupport(radius * intensity)
    if (vertical) {
        Rect(
            output.left,
            output.top - support,
            output.right,
            output.bottom + support,
        )
    } else {
        Rect(
            output.left - support,
            output.top,
            output.right + support,
            output.bottom,
        )
    }
}

private const val ProgressiveBlurMaxRadius = 150f

internal fun progressiveBlurKernelSupport(radius: Float): Float {
    if (radius <= 0f) return 0f
    val tapCount = minOf(ceil(radius), ProgressiveBlurMaxRadius)
    if (tapCount < ProgressiveBlurMaxRadius && tapCount % 2f == 1f) return tapCount + 0.5f

    val lastPair = tapCount - 1f
    val sigma = maxOf(radius / 2f, 1e-4f)
    val exponent = (2f * lastPair + 1f) / (2f * sigma * sigma)
    val lastOffset = lastPair + 1f / (1f + exp(exponent.toDouble()).toFloat())
    return lastOffset + 0.5f
}

private val ProgressiveBlurVerticalShader: String by lazy { progressiveBlurShader(vertical = true) }
private val ProgressiveBlurHorizontalShader: String by lazy {
    progressiveBlurShader(vertical = false)
}

/**
 * A separable Gaussian whose radius follows the shared ramp curve at each pixel. Intensity is
 * derived from the coordinate so both passes share the ramp without an extra shader input.
 * Successive Gaussian quotients replace per-tap exponentials while retaining paired sample
 * positions and kernel support. Each blurred pixel initializes the recurrence with two
 * exponentials. See
 * [Incremental Computation of the Gaussian](https://developer.nvidia.com/gpugems/gpugems3/part-vi-gpu-computing/chapter-40-incremental-computation-gaussian).
 */
internal fun progressiveBlurShader(vertical: Boolean): String =
    """
uniform shader content;

// The ramp's anchors in effect-layer pixels, including the surface origin inside that layer.
uniform vec2 anchors;
uniform float blurRadius;
uniform float axis;
uniform float4 curve;
uniform float2 intensities;

const float maxRadius = 150.0;

$BackdropCurveShader

vec4 blur(vec2 coord, float radius) {
  if (radius <= 0.0) {
    return content.eval(coord);
  }

  // The local radius determines Gaussian spread, while tap selection rounds upward
  // to cover fractional radii.
  float r = ceil(radius);

  float sigma = max(radius / 2.0, 1e-4);
  float inv2Sigma2 = 1.0 / (2.0 * sigma * sigma);

  vec4 result = vec4(0.0);
  float weightSum = 0.0;

  // Successive Gaussian ratios differ by a constant factor: only two exponentials per pixel.
  float ratio = exp(-inv2Sigma2);
  float ratioStep = exp(-2.0 * inv2Sigma2);
  float wPrev = 1.0;
  result += wPrev * content.eval(coord);
  weightSum += wPrev;

  for (float i = 1.0; i < maxRadius; i += 2.0) {
    if (i >= r) { break; }

    float w1 = wPrev * ratio;
    ratio *= ratioStep;
    float w2 = w1 * ratio;
    ratio *= ratioStep;

    float weight = w1 + w2;

    vec2 offset = ${if (vertical) "vec2(0.0, i + w2 / weight)" else "vec2(i + w2 / weight, 0.0)"};

    result += weight * content.eval(coord - offset);
    result += weight * content.eval(coord + offset);
    weightSum += 2.0 * weight;

    wPrev = w2;
  }

  if (r < maxRadius && mod(r, 2.0) == 1.0) {
    float w = wPrev * ratio;

    vec2 offset = ${if (vertical) "vec2(0.0, r)" else "vec2(r, 0.0)"};

    result += w * content.eval(coord - offset);
    result += w * content.eval(coord + offset);
    weightSum += 2.0 * w;
  }

  return result / max(weightSum, 1e-5);
}

vec4 main(vec2 coord) {
  float position = mix(coord.x, coord.y, axis);
  // The ramp runs from its start anchor to its end anchor, which is what a brush of it paints.
  float ramp = (position - anchors.x) / (anchors.y - anchors.x);
  float intensity = mix(intensities.x, intensities.y, backdropCurve(clamp(ramp, 0.0, 1.0), curve));

  if (intensity <= 0.0 || blurRadius <= 0.0) {
    return content.eval(coord);
  }

  return blur(coord, mix(0.0, blurRadius, intensity));
}
"""

/**
 * Asks the effects to cover what [ramp] reaches past the surface, on the side [edge] names.
 *
 * A mask fades over the same span, so masking requires that room independently of blur: without it
 * the layer would end at the surface, the mask would be clipped there, and the ramp's remaining
 * strength would show as a line along the edge.
 */
public fun BackdropEffectScope.coverRamp(ramp: BackdropRamp, edge: BackdropEdge) {
    val side = edge.side(layoutDirection)
    val thickness = if (side.isVertical) size.height else size.width
    if (!thickness.isFinite() || thickness <= 0f) return
    val axis =
        BackdropAxis(
            edge = side,
            occupied = 0f..thickness,
            content = 0f..thickness,
            density = density,
            fontScale = fontScale,
        )
    val span = ramp.span(axis)
    require(
        span.start.isFinite() && span.endInclusive.isFinite() && span.start <= span.endInclusive,
    ) {
        "Progressive blur ramp anchors must be finite and ordered"
    }
    cover(
        coveredInsets(
            side,
            pastContentSide = maxOf(0f, -span.start),
            pastFarSide = maxOf(0f, span.endInclusive - thickness),
        ),
    )
}

/** The room a ramp's two ends reach past a surface, on the sides a physical edge names. */
private fun Density.coveredInsets(
    side: BackdropSide,
    pastContentSide: Float,
    pastFarSide: Float,
): BackdropInsets =
    when (side) {
        BackdropSide.Top ->
            BackdropInsets(top = pastFarSide.toDp(), bottom = pastContentSide.toDp())
        BackdropSide.Bottom ->
            BackdropInsets(top = pastContentSide.toDp(), bottom = pastFarSide.toDp())
        BackdropSide.Left ->
            BackdropInsets(left = pastFarSide.toDp(), right = pastContentSide.toDp())
        BackdropSide.Right ->
            BackdropInsets(left = pastContentSide.toDp(), right = pastFarSide.toDp())
    }

internal fun validateProgressiveBlur(radius: Float, ramp: BackdropRamp) {
    require(radius.isFinite() && radius >= 0f) {
        "Progressive blur radius must be finite and non-negative"
    }
    require(
        ramp.from.isFinite() && ramp.from in 0f..1f && ramp.to.isFinite() && ramp.to in 0f..1f,
    ) {
        "Progressive blur intensities must be finite and within 0..1"
    }
}

internal data class ProgressiveBlurGeometry(
    val side: BackdropSide,
    val start: Offset,
    val end: Offset,
    val coverage: BackdropInsets,
)

internal fun Density.progressiveBlurGeometry(
    size: Size,
    layoutDirection: LayoutDirection,
    edge: BackdropEdge,
    ramp: BackdropRamp,
): ProgressiveBlurGeometry? {
    val side = edge.side(layoutDirection)
    val thickness = if (side.isVertical) size.height else size.width
    if (!thickness.isFinite() || thickness <= 0f) return null
    val span = ramp.span(BackdropAxis(side, 0f..thickness, 0f..thickness, density, fontScale))
    require(
        span.start.isFinite() && span.endInclusive.isFinite() && span.start < span.endInclusive,
    ) {
        "Progressive blur ramp anchors must be finite and ordered"
    }
    // Freeze the resolved span so a custom anchor producer is evaluated once per operation.
    val resolved = ramp.copy(anchors = { span })
    val (start, end) = backdropRampEnds(resolved, edge, size, layoutDirection, Offset.Zero)
    require(
        start.x.isFinite() &&
            start.y.isFinite() &&
            end.x.isFinite() &&
            end.y.isFinite() &&
            start != end,
    ) {
        "Progressive blur ramp anchors must be finite and distinct"
    }
    return ProgressiveBlurGeometry(
        side,
        start,
        end,
        coveredInsets(side, maxOf(0f, -span.start), maxOf(0f, span.endInclusive - thickness)),
    )
}
