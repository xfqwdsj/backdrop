package top.ltfan.backdrop.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import top.ltfan.backdrop.BackdropAxis
import top.ltfan.backdrop.BackdropEdge
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.BackdropInsets
import top.ltfan.backdrop.BackdropRamp
import top.ltfan.backdrop.BackdropSide
import top.ltfan.backdrop.backdropRampEnds
import top.ltfan.backdrop.isRuntimeShaderSupported
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
 * Blurs the backdrop with a strength that ramps from [radius] at [edge] to none at the opposite
 * edge. The blur radius varies per pixel through a vertical-then-horizontal Gaussian pass, so the
 * clear end stays sharp while the surface itself stays opaque.
 *
 * [ramp] supplies the curve, its end intensities and its anchors, which the shader evaluates over
 * the same controls a mask of that ramp samples, so the two stay on one curve and on one stretch of
 * the surface. An anchor inside the surface holds its end intensity beyond it, which is how a bar
 * keeps full strength above its content and fades the rest below.
 *
 * [BackdropEffectScope.extension] places the anchors in the effect layer. Extending or contracting
 * the drawing region clips the selected curve without moving its anchors; the ramp itself controls
 * where the curve begins and ends, inside or outside the surface.
 *
 * Every pixel takes the radius its position earns, one pass per axis, and on platforms without
 * runtime shader support this falls back to a uniform [blur].
 */
public fun BackdropEffectScope.progressiveBlur(
    radius: Float,
    edge: BackdropEdge,
    ramp: BackdropRamp = BackdropRamp(),
) {
    if (radius <= 0f) return
    if (!isRuntimeShaderSupported()) {
        blur(radius)
        return
    }
    // The shader samples the layer it draws into and skips samples outside it, so the blur needs no
    // room of its own: room beyond the backdrop would hold no pixels, and reading those would let
    // the content behind the surface show through.

    val side = edge.side(layoutDirection)
    val vertical = side.isVertical
    val axis = if (vertical) 1f else 0f
    val curve = ramp.curve
    val startIntensity = ramp.from
    val endIntensity = ramp.to
    // The ramp answers on the axis a surface reports: 0 where the scrolled content passes it,
    // positive towards the surface's own far edge. Whatever it reaches past the surface is the room
    // this effect covers.
    val thickness = if (vertical) size.height else size.width
    if (thickness.isNaN() || thickness <= 0f) return
    val axisOfSurface =
        BackdropAxis(
            edge = side,
            occupied = 0f..thickness,
            content = 0f..thickness,
            density = density,
            fontScale = fontScale,
        )
    val span = ramp.span(axisOfSurface)
    val pastContentSide = maxOf(0f, -span.start)
    val pastFarSide = maxOf(0f, span.endInclusive - thickness)
    val covered = coveredInsets(side, pastContentSide, pastFarSide)
    cover = covered
    // The resolved layer includes coverage and sampling room, with its origin before the surface.
    val room = extension
    val layerWidth = size.width + room.left + room.right
    val layerHeight = size.height + room.top + room.bottom
    val (start, end) =
        backdropRampEnds(ramp, edge, size, layoutDirection, Offset(room.left, room.top))
    val anchorStart = if (vertical) start.y else start.x
    val anchorEnd = if (vertical) end.y else end.x
    runtimeShaderEffect(
        key = "ProgressiveBlurVertical",
        shaderString = ProgressiveBlurVerticalShader,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", layerWidth, layerHeight)
        setFloatUniform("anchors", anchorStart, anchorEnd)
        setFloatUniform("blurRadius", radius)
        setFloatUniform("axis", axis)
        setFloatUniform("curve", curve.x1, curve.y1, curve.x2, curve.y2)
        setFloatUniform("intensities", startIntensity, endIntensity)
    }
    runtimeShaderEffect(
        key = "ProgressiveBlurHorizontal",
        shaderString = ProgressiveBlurHorizontalShader,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", layerWidth, layerHeight)
        setFloatUniform("anchors", anchorStart, anchorEnd)
        setFloatUniform("blurRadius", radius)
        setFloatUniform("axis", axis)
        setFloatUniform("curve", curve.x1, curve.y1, curve.x2, curve.y2)
        setFloatUniform("intensities", startIntensity, endIntensity)
    }
}

private val ProgressiveBlurVerticalShader: String by lazy { progressiveBlurShader(vertical = true) }
private val ProgressiveBlurHorizontalShader: String by lazy {
    progressiveBlurShader(vertical = false)
}

/**
 * A separable Gaussian whose radius is driven per pixel by a smoothstep ramp mask. The mask value
 * is derived from the coordinate so the two passes share the ramp without an extra shader input.
 */
private fun progressiveBlurShader(vertical: Boolean): String =
    """
uniform shader content;

// The effect layer's extent, which is where the content the blur samples actually is.
uniform vec2 size;
// The ramp's anchors in effect-layer pixels, including the surface origin inside that layer.
uniform vec2 anchors;
uniform float blurRadius;
uniform float axis;
uniform float4 curve;
uniform float2 intensities;

const float maxRadius = 150.0;

bool inside(vec2 p) {
  return p.x >= 0.5 && p.y >= 0.5 && p.x <= size.x - 0.5 && p.y <= size.y - 0.5;
}

float bezierAxis(float t, float first, float second) {
  float inverse = 1.0 - t;
  return 3.0 * inverse * inverse * t * first + 3.0 * inverse * t * t * second + t * t * t;
}

// Inverting the curve by bisection keeps this to one expression the Kotlin side solves the same
// way, so a mask of the same ramp and this blur agree on one curve.
float bezier(float x) {
  x = clamp(x, 0.0, 1.0);
  float low = 0.0;
  float high = 1.0;
  float t = x;
  for (int i = 0; i < 16; i++) {
    t = (low + high) * 0.5;
    if (bezierAxis(t, curve.x, curve.z) < x) {
      low = t;
    } else {
      high = t;
    }
  }
  return bezierAxis(t, curve.y, curve.w);
}

vec4 blur(vec2 coord, float radius) {
  if (radius <= 0.0) {
    return content.eval(coord);
  }

  // The radius follows the ramp, which reaches nothing at the edge of the area the effect covers:
  // sampling out to the next whole pixel and taking the spread from the radius itself lets the
  // neighbours lose their weight as the radius shrinks, so the blur thins out instead of stopping
  // at the edge as a line the mask no longer hides.
  float r = ceil(radius);

  float sigma = max(radius / 2.0, 1e-4);
  float inv2Sigma2 = 1.0 / (2.0 * sigma * sigma);

  vec4 result = vec4(0.0);
  float weightSum = 0.0;

  bool fullyInside = ${if (vertical) "coord.y - r > 0.5 && coord.y + r < size.y - 0.5" else "coord.x - r > 0.5 && coord.x + r < size.x - 0.5"};

  float wPrev = 1.0;

  if (fullyInside) {
    result += wPrev * content.eval(coord);
    weightSum += wPrev;

    for (float i = 1.0; i < maxRadius; i += 2.0) {
      if (i >= r) { break; }

      float w1 = wPrev * exp(-(2.0 * (i - 1.0) + 1.0) * inv2Sigma2);
      float w2 = w1 * exp(-(2.0 * i + 1.0) * inv2Sigma2);

      float weight = w1 + w2;

      vec2 offset = ${if (vertical) "vec2(0.0, i + w2 / weight)" else "vec2(i + w2 / weight, 0.0)"};

      result += weight * content.eval(coord - offset);
      result += weight * content.eval(coord + offset);
      weightSum += 2.0 * weight;

      wPrev = w2;
    }

    if (r < maxRadius && mod(r, 2.0) == 1.0) {
      float w = wPrev * exp(-(2.0 * (r - 1.0) + 1.0) * inv2Sigma2);

      vec2 offset = ${if (vertical) "vec2(0.0, r)" else "vec2(r, 0.0)"};

      result += w * content.eval(coord - offset);
      result += w * content.eval(coord + offset);
      weightSum += 2.0 * w;
    }
  } else {
    if (inside(coord)) {
      result += wPrev * content.eval(coord);
      weightSum += wPrev;
    }

    for (float i = 1.0; i < maxRadius; i += 2.0) {
      if (i >= r) { break; }

      float w1 = wPrev * exp(-(2.0 * (i - 1.0) + 1.0) * inv2Sigma2);
      float w2 = w1 * exp(-(2.0 * i + 1.0) * inv2Sigma2);

      float weight = w1 + w2;

      vec2 offset = ${if (vertical) "vec2(0.0, i + w2 / weight)" else "vec2(i + w2 / weight, 0.0)"};

      vec2 p1 = coord - offset;
      if (inside(p1)) {
        result += weight * content.eval(p1);
        weightSum += weight;
      }

      vec2 p2 = coord + offset;
      if (inside(p2)) {
        result += weight * content.eval(p2);
        weightSum += weight;
      }

      wPrev = w2;
    }

    if (r < maxRadius && mod(r, 2.0) == 1.0) {
      float w = wPrev * exp(-(2.0 * (r - 1.0) + 1.0) * inv2Sigma2);

      vec2 offset = ${if (vertical) "vec2(0.0, r)" else "vec2(r, 0.0)"};

      vec2 p1 = coord - offset;
      if (inside(p1)) {
        result += w * content.eval(p1);
        weightSum += w;
      }

      vec2 p2 = coord + offset;
      if (inside(p2)) {
        result += w * content.eval(p2);
        weightSum += w;
      }
    }
  }

  return result / max(weightSum, 1e-5);
}

vec4 main(vec2 coord) {
  float position = mix(coord.x, coord.y, axis);
  // The ramp runs from its start anchor to its end anchor, which is what a brush of it paints.
  float ramp = (position - anchors.x) / (anchors.y - anchors.x);
  float intensity = mix(intensities.x, intensities.y, bezier(clamp(ramp, 0.0, 1.0)));

  if (intensity <= 0.0 || blurRadius <= 0.0) {
    return content.eval(coord);
  }

  return blur(coord, mix(0.0, blurRadius, intensity));
}
"""

/**
 * Asks the effects to cover what [ramp] reaches past the surface, on the side [edge] names.
 *
 * A mask fades over the same span, so a surface that masks without blurring still needs that room:
 * without it the layer would end at the surface, the mask would be clipped there, and the ramp's
 * remaining strength would show as a line along the edge.
 */
public fun BackdropEffectScope.coverRamp(ramp: BackdropRamp, edge: BackdropEdge) {
    val side = edge.side(layoutDirection)
    val thickness = if (side.isVertical) size.height else size.width
    if (thickness.isNaN() || thickness <= 0f) return
    val axis =
        BackdropAxis(
            edge = side,
            occupied = 0f..thickness,
            content = 0f..thickness,
            density = density,
            fontScale = fontScale,
        )
    val span = ramp.span(axis)
    cover =
        coveredInsets(
            side,
            pastContentSide = maxOf(0f, -span.start),
            pastFarSide = maxOf(0f, span.endInclusive - thickness),
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
