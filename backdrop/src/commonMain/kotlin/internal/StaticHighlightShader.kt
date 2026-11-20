package top.ltfan.backdrop.internal

import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.angularDirectionShader
import top.ltfan.backdrop.highlight.exclusionShader

/** Shades one nearest-contour response from a prefiltered environment image. */
internal fun environmentHighlightTileShader(
    boundary: HighlightBoundaryGeometry,
    segmentCapacity: Int = boundary.segmentCount,
    segmentGroupSize: Int = 0,
    certifiedNormalsOnly: Boolean = false,
    sharedDirectionCurve: Boolean = false,
    hasEnvironment: Boolean = false,
    angularCapacity: Int = 0,
    exclusionCapacity: Int = 0,
): String {
    val geometry =
        if (boundary.isAnalytic) {
            boundary.shaderFunctions(segmentCapacity)
        } else {
            genericBoundaryShaderFunctions(
                boundary.segmentCount,
                segmentCapacity,
                segmentGroupSize,
                certifiedNormalsOnly,
            )
        }
    val directionWidthCurveUniform =
        if (sharedDirectionCurve) "" else "uniform float4 directionWidthCurve;"
    val directionCurveValues =
        if (angularCapacity > 0) {
            """
            float2 angularValues = angularResponse(normal);
            float widthScale = angularValues.x;
            float strengthByDirection = angularValues.y;
            """
                .trimIndent()
        } else if (sharedDirectionCurve) {
            """
            float sharedDirectionLevel = backdropCurve(magnitude, directionStrengthCurve);
            float strengthByDirection = directionEnabled > 0.5
              ? directionStrengthRange.x +
                (directionStrengthRange.y - directionStrengthRange.x) * sharedDirectionLevel
              : 1.0;
            float widthScale = directionEnabled > 0.5
              ? directionWidthRange.x +
                (directionWidthRange.y - directionWidthRange.x) * sharedDirectionLevel
              : 1.0;
            """
                .trimIndent()
        } else {
            """
            float strengthByDirection = directionEnabled > 0.5
              ? response(magnitude, directionStrengthRange, directionStrengthCurve) : 1.0;
            float widthScale = directionEnabled > 0.5
              ? response(magnitude, directionWidthRange, directionWidthCurve) : 1.0;
            """
                .trimIndent()
        }
    val directionUniforms =
        if (angularCapacity > 0) {
            "uniform float angularMaximumWidthScale;\n" + angularDirectionShader(angularCapacity)
        } else {
            """
            uniform float directionEnabled;
            uniform float directionMode;
            uniform float2 directionStrengthRange;
            uniform float4 directionStrengthCurve;
            uniform float2 directionWidthRange;
            $directionWidthCurveUniform
            """
                .trimIndent()
        }
    val maximumWidth =
        if (angularCapacity > 0) "angularMaximumWidthScale"
        else "max(directionWidthRange.x, directionWidthRange.y)"
    val nearestQuery =
        if (boundary.isAnalytic) "nearestBoundaryWithPosition(p, nearestPosition)"
        else "nearestBoundaryWithPosition(p, conservativeUpper, nearestPosition)"
    val environmentDeclaration = if (hasEnvironment) "uniform shader environment;" else ""
    val environmentContribution =
        if (hasEnvironment) {
            """
            half4 environmentSample = environment.eval(coord);
            float environmentAlpha = max(0.0, float(environmentSample.a));
            if (environmentAlpha > 0.000001) {
              float3 environmentColor =
                float3(environmentSample.rgb) / environmentAlpha;
              light += toLinearSrgb(environmentColor) * environmentGain * environmentAlpha;
            }
            """
                .trimIndent()
        } else ""
    val magnitudeInitialization =
        if (angularCapacity == 0) {
            "magnitude = directionMode < 0.5 ? max(0.0, projection) : abs(projection);"
        } else ""
    return """
$geometry
$HighlightCurveShader
uniform float4 tileBounds;
uniform float width;
uniform float baseBrightness;
uniform float environmentGain;
uniform float2 directionVector;
$directionUniforms
$environmentDeclaration
${exclusionShader(exclusionCapacity)}

float response(float value, float2 range, float4 controls) {
  return range.x + (range.y - range.x) * backdropCurve(value, controls);
}

half4 shadeAt(float2 coord) {
  float2 p = coord - origin;
  if (width <= 0.0) return half4(0.0);
  float maximumBandWidth = width * $maximumWidth;
  float conservativeUpper =
    maximumBandWidth + max(0.0001, abs(maximumBandWidth) * 0.000001) + 0.5;
  float2 nearestPosition;
  float3 nearest = $nearestQuery;
  if (nearest.x >= conservativeUpper) return half4(0.0);
  float2 normal = nearest.yz;
  float projection = dot(normal, directionVector);
  float magnitude = 0.0;
  $magnitudeInitialization
  $directionCurveValues
  float bandWidth = width * widthScale;
  if (bandWidth <= 0.0 || nearest.x >= bandWidth + 0.5) return half4(0.0);
  float fade = 1.0 - smoothstep(0.0, max(bandWidth, 0.0001), nearest.x);
  float exclusion = exclusionWeight(normal);
  if (fade <= 0.0 || exclusion <= 0.0) return half4(0.0);

  float3 light = float3(baseBrightness);
  $environmentContribution
  light *= fade * strengthByDirection * exclusion;
  float peak = max(light.r, max(light.g, light.b));
  if (!(peak > 0.0)) return half4(0.0);
  float alpha = clamp(peak, 0.0, 1.0);
  return half4(fromLinearSrgb(light / max(alpha, 0.000001)) * alpha, half(alpha));
}

half4 main(float2 coord) {
  float2 p = coord - origin;
  if (
    p.x < tileBounds.x ||
    p.y < tileBounds.y ||
    p.x >= tileBounds.z ||
    p.y >= tileBounds.w
  ) return half4(0.0);
  return shadeAt(coord);
}
"""
        .trimIndent()
}

internal fun highlightDirectionUsesSharedCurve(direction: HighlightDirection): Boolean {
    val incidence = direction as? HighlightDirection.Incidence ?: return false
    return incidence.strength.curve == incidence.widthScale.curve
}
