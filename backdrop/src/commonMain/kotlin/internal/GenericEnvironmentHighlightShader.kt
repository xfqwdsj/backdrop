package top.ltfan.backdrop.internal

import top.ltfan.backdrop.BackdropCurveShader
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry
import top.ltfan.backdrop.highlight.exclusionShader

/** Environment lighting uses the same flattened boundary functions as static highlights. */
internal fun genericEnvironmentHighlightShader(segmentCount: Int, exclusionCount: Int = 0): String {
    require(segmentCount > 0)
    return genericEnvironmentHighlightShader(
        genericBoundaryShaderFunctions(segmentCount),
        exclusionCount,
    )
}

internal fun genericEnvironmentHighlightShader(
    geometry: HighlightBoundaryGeometry,
    exclusionCount: Int = 0,
): String = genericEnvironmentHighlightShader(geometry.shaderFunctions(), exclusionCount)

private fun genericEnvironmentHighlightShader(geometry: String, exclusionCount: Int): String {
    return """
$geometry
$BackdropCurveShader
uniform float width;
uniform float angle;
uniform float4 widthCurve;
uniform float4 depthCurve;
uniform float directional;
uniform float staticEnabled;
uniform shader content;
uniform float sampleDistance;
uniform float brightnessStrength;
uniform float brightnessThreshold;
uniform float tintStrength;
uniform float tintThreshold;
uniform float thicknessWidth;
uniform float thicknessThreshold;
${exclusionShader(exclusionCount)}

float brightness(float3 color) {
  return dot(color, float3(0.2126, 0.7152, 0.0722));
}
float extraction(float value, float threshold) {
  return smoothstep(threshold, threshold + max(threshold * 0.25, 0.0001), value);
}
half4 main(float2 coord) {
  float2 p = coord - origin;
  if (!containsPoint(p)) return half4(0.0);
  float3 boundary = nearestBoundary(p);
  float distance = boundary.x;
  float2 outward = boundary.yz;
  float projection = abs(dot(outward, float2(cos(angle), sin(angle))));
  float baseWidth = staticEnabled < 0.5 ? 0.0 : (directional > 0.5 ? width * backdropCurve(projection, widthCurve) : width);
  float stepDistance = sampleDistance / 3.0;
  float4 a = content.eval(coord + outward * (distance + stepDistance));
  float4 b = content.eval(coord + outward * (distance + 2.0 * stepDistance));
  float4 c = content.eval(coord + outward * (distance + sampleDistance));
  float4 sampled = (a + b + c) / 3.0;
  if (!(sampled.a > 0.0)) return half4(0.0);
  float3 straight = sampled.rgb / sampled.a;
  float luminance = brightness(straight);
  float thicknessResponse = extraction(luminance, thicknessThreshold);
  float addedWidth = thicknessWidth * thicknessResponse;
  float totalWidth = baseWidth + addedWidth;
  float brightnessCoverage = highlightBandProfile(distance, totalWidth, depthCurve);
  float tintWidth = min(totalWidth, width);
  float tintCoverage = highlightBandProfile(distance, tintWidth, depthCurve);
  float bright = extraction(luminance, brightnessThreshold) * brightnessStrength * brightnessCoverage;
  float tint = extraction(luminance, tintThreshold) * tintStrength * tintCoverage;
  float weight = exclusionWeight(outward);
  float alphaCoverage = max(bright, tint) * weight;
  float alpha = min(sampled.a, sampled.a * alphaCoverage);
  float3 rgb = sampled.a * (float3(luminance) * bright + straight * tint) * weight;
  return half4(rgb, alpha);
}
"""
        .trimIndent()
}
