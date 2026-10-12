package top.ltfan.backdrop.internal

import top.ltfan.backdrop.BackdropCurveShader
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.exclusionShader

/** Static boundary shading shared by analytic-independent path outlines. */
internal fun staticHighlightShader(
    geometry: HighlightBoundaryGeometry,
    style: HighlightStyle,
): String = staticHighlightShader(geometry.shaderFunctions(), style)

internal fun staticHighlightShader(segmentCount: Int, style: HighlightStyle): String {
    require(segmentCount > 0)
    return staticHighlightShader(genericBoundaryShaderFunctions(segmentCount), style)
}

private fun staticHighlightShader(geometryFunctions: String, style: HighlightStyle): String {
    return """
$geometryFunctions
uniform float width;
uniform float angle;
uniform float directional;
uniform float4 widthCurve;
uniform float4 strengthCurve;
uniform float4 depthCurve;
layout(color) uniform half4 color;
${style.shaderSource}

$BackdropCurveShader

half4 main(float2 coord) {
  float2 p = coord - origin;
  if (!containsPoint(p)) return half4(0.0);
  float3 boundary = nearestBoundary(p);
  float2 n = boundary.yz;
  float projection = dot(n, float2(cos(angle), sin(angle)));
  float widthScale = directional > 0.5 ? backdropCurve(abs(projection), widthCurve) : 1.0;
  float strength = directional > 0.5 ? backdropCurve(abs(projection), strengthCurve) : 1.0;
  float bandWidth = width * widthScale;
  float coverage = highlightBandProfile(boundary.x, bandWidth, depthCurve);
  half4 shaded = shadeHighlight(p, n, projection, boundary.x, bandWidth, color);
  return half4(shaded.rgb * shaded.a * strength * coverage, shaded.a * strength * coverage);
}
"""
        .trimIndent()
}

/** Exclusion mask applied after blur so no filtered light reaches a forbidden normal. */
internal fun highlightExclusionMaskShader(segmentCount: Int, exclusionCount: Int): String {
    require(segmentCount > 0)
    return highlightExclusionMaskShader(
        genericBoundaryShaderFunctions(segmentCount),
        exclusionCount,
    )
}

internal fun highlightExclusionMaskShader(
    geometry: HighlightBoundaryGeometry,
    exclusionCount: Int,
): String = highlightExclusionMaskShader(geometry.shaderFunctions(), exclusionCount)

private fun highlightExclusionMaskShader(geometryFunctions: String, exclusionCount: Int): String =
    """
$geometryFunctions
uniform shader content;
${exclusionShader(exclusionCount)}
half4 main(float2 coord) {
  float2 p = coord - origin;
  if (!containsPoint(p)) return half4(0.0);
  float2 normal = nearestBoundary(p).yz;
  return content.eval(coord) * exclusionWeight(normal);
}
"""
        .trimIndent()
