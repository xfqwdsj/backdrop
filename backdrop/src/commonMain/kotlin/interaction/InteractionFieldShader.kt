package top.ltfan.backdrop.interaction

import kotlin.math.exp
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropCurveShader

internal fun interactionLightWeight(
    distance: Float,
    radius: Float,
    sourceAlpha: Float,
    intensity: Float,
    falloff: BackdropCurve,
): Float {
    if (!distance.isFinite() || !radius.isFinite() || radius <= 0f || distance >= radius) return 0f
    if (!sourceAlpha.isFinite() || !intensity.isFinite()) return 0f
    val weight =
        maxOf(0f, sourceAlpha) *
            maxOf(0f, intensity) *
            falloff.transform((1f - distance / radius).coerceIn(0f, 1f))
    return weight.takeIf { it.isFinite() && it > 0f } ?: 0f
}

internal fun interactionFieldResponse(totalWeight: Float): Float =
    if (totalWeight.isFinite() && totalWeight > 0f) 1f - exp(-totalWeight) else 0f

/** Shared point-light field function used by the interior and contour runtime shaders. */
internal fun interactionFieldShader(capacity: Int, includeCurveShader: Boolean = true): String {
    require(capacity >= 4 && capacity.countOneBits() == 1)
    return """
uniform float4 interactionRowX;
uniform float4 interactionRowY;
uniform float4 interactionRowW;
uniform float4 interactionPositions[$capacity];
uniform float4 interactionColors[$capacity];
uniform float4 interactionParameters[$capacity];
uniform float4 interactionCurves[$capacity];
uniform int interactionLightCount;
${if (includeCurveShader) BackdropCurveShader else ""}
float4 interactionField(float2 p) {
  float3 local = float3(p, 1.0);
  float denominator = dot(interactionRowW.xyz, local);
  if (abs(denominator) < 0.00001) return float4(0.0);
  float2 world = float2(dot(interactionRowX.xyz, local), dot(interactionRowY.xyz, local)) / denominator;
  float sum = 0.0;
  float3 weightedColor = float3(0.0);
  for (int i = 0; i < $capacity; i++) {
    if (i >= interactionLightCount) break;
    float radius = interactionParameters[i].x;
    if (radius <= 0.0) continue;
    float distanceToLight = distance(world, interactionPositions[i].xy);
    if (distanceToLight >= radius) continue;
    float weight = max(0.0, interactionColors[i].a) * max(0.0, interactionParameters[i].y) *
      backdropCurve(clamp(1.0 - distanceToLight / radius, 0.0, 1.0), interactionCurves[i]);
    sum += weight;
    weightedColor += interactionColors[i].rgb * weight;
  }
  if (sum <= 0.0) return float4(0.0);
  return float4(weightedColor / sum, 1.0 - exp(-sum));
}
"""
        .trimIndent()
}
