package top.ltfan.backdrop.internal

import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry
import top.ltfan.backdrop.highlight.HighlightColorTransform
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.exclusionShader

/** Static fixed-width shading from the nearest contour boundary. */
internal fun staticHighlightTileShader(
    geometry: FlattenedBoundaryGeometry,
    style: HighlightStyle,
    exclusionCount: Int,
    colorTransform: HighlightColorTransform? = null,
    segmentCapacity: Int = geometry.segmentCount,
    falloff: BackdropCurve = BackdropCurve.Default,
    sharedDirectionCurve: Boolean = false,
): String =
    nearestBandShader(
        genericBoundaryShaderFunctions(geometry.segmentCount, segmentCapacity),
        style,
        exclusionCount,
        colorTransform,
        falloff,
        sharedDirectionCurve,
    )

internal fun staticHighlightTileShader(
    boundary: HighlightBoundaryGeometry,
    style: HighlightStyle,
    exclusionCount: Int,
    colorTransform: HighlightColorTransform? = null,
    segmentCapacity: Int = boundary.segmentCount,
    falloff: BackdropCurve = BackdropCurve.Default,
    sharedDirectionCurve: Boolean = false,
): String =
    if (boundary.isAnalytic) {
        nearestBandShader(
            boundary.shaderFunctions(segmentCapacity),
            style,
            exclusionCount,
            colorTransform,
            falloff,
            sharedDirectionCurve,
        )
    } else {
        staticHighlightTileShader(
            boundary.cpuGeometry(),
            style,
            exclusionCount,
            colorTransform,
            segmentCapacity,
            falloff,
            sharedDirectionCurve,
        )
    }

private fun nearestBandShader(
    geometry: String,
    style: HighlightStyle,
    exclusionCount: Int,
    colorTransform: HighlightColorTransform?,
    falloff: BackdropCurve,
    sharedDirectionCurve: Boolean,
): String {
    val colorResponseShader =
        if (colorTransform == null) ""
        else {
            """
            uniform shader content;
            uniform float colorResponseAmount;
            ${colorTransform.shaderSource}
            bool backdropHighlightFinite3(float3 value) {
              return abs(value.x) <= 3.402823466e+38 && abs(value.y) <= 3.402823466e+38 && abs(value.z) <= 3.402823466e+38;
            }
            bool backdropHighlightFinite4(float4 value) {
              return abs(value.x) <= 3.402823466e+38 && abs(value.y) <= 3.402823466e+38 && abs(value.z) <= 3.402823466e+38 && abs(value.w) <= 3.402823466e+38;
            }
            float3 applyBackdropColorResponse(float2 p, float3 baseColor) {
              float4 sample = content.eval(p);
              if (!backdropHighlightFinite4(sample) || !(sample.a > 0.000001) || sample.a > 1.0) return baseColor;
              float3 sampledColor = sample.rgb / sample.a;
              if (!backdropHighlightFinite3(sampledColor) || !backdropHighlightFinite3(baseColor)) return baseColor;
              float3 linearSample = toLinearSrgb(sampledColor);
              if (!backdropHighlightFinite3(linearSample)) return baseColor;
              float3 transformed = transformHighlightColor(baseColor, linearSample);
              if (!backdropHighlightFinite3(transformed)) return baseColor;
              return mix(baseColor, transformed, clamp(colorResponseAmount * sample.a, 0.0, 1.0));
            }
            """
                .trimIndent()
        }
    val colorResponseCall =
        if (colorTransform == null) ""
        else "linearColor = applyBackdropColorResponse(p, linearColor);"
    val directionWidthCurveUniform =
        if (sharedDirectionCurve) "" else "uniform float4 directionWidthCurve;"
    val directionCurveValues =
        if (sharedDirectionCurve) {
            """
            float sharedDirectionLevel = backdropCurve(magnitude, directionStrengthCurve);
            float strengthByDirection = directionEnabled > 0.5
              ? directionStrengthRange.x + (directionStrengthRange.y - directionStrengthRange.x) * sharedDirectionLevel : 1.0;
            float widthScale = directionEnabled > 0.5
              ? directionWidthRange.x + (directionWidthRange.y - directionWidthRange.x) * sharedDirectionLevel : 1.0;
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
    val falloffCurveShader =
        highlightCurveShader(falloff).replace("backdropCurve", "backdropFalloffCurve")
    return """
$geometry
$HighlightCurveShader
$falloffCurveShader
uniform float width;
uniform float directionEnabled;
uniform float directionMode;
uniform float2 directionVector;
uniform float2 directionStrengthRange;
uniform float4 directionStrengthCurve;
uniform float2 directionWidthRange;
$directionWidthCurveUniform
uniform float4 falloffCurve;
uniform float4 tileBounds;
layout(color) uniform half4 color;
${style.shaderSource}
$colorResponseShader
${exclusionShader(exclusionCount)}

float response(float value, float2 range, float4 controls) {
  return range.x + (range.y - range.x) * backdropCurve(value, controls);
}
float fixedBandProfile(float distance, float bandWidth, float4 curve) {
  if (bandWidth <= 0.0) return 0.0;
  float low = max(0.0, distance - 0.5);
  float high = min(bandWidth, distance + 0.5);
  float coverage = max(0.0, high - low);
  if (coverage <= 0.0) return 0.0;
  float progress = clamp(1.0 - (low + high) * 0.5 / bandWidth, 0.0, 1.0);
  return coverage * backdropFalloffCurve(progress, curve);
}
half4 shadeAt(float2 coord) {
  float2 p = coord - origin;
  if (width <= 0.0) return half4(0.0);
  float maxBandWidth = width * max(directionWidthRange.x, directionWidthRange.y);
  float conservativeUpper = maxBandWidth + max(0.0001, abs(maxBandWidth) * 0.000001) + 0.5;
  float2 nearestPosition;
  float3 nearest = nearestBoundaryWithPosition(p, nearestPosition);
  if (nearest.x >= conservativeUpper) return half4(0.0);
  float2 normal = nearest.yz;
  float projection = dot(normal, directionVector);
  float magnitude = directionMode < 0.5 ? max(0.0, projection) : abs(projection);
  $directionCurveValues
  float bandWidth = width * widthScale;
  if (nearest.x >= bandWidth + 0.5) return half4(0.0);
  half4 shaded = shadeHighlight(nearestPosition, normal, projection, color);
  float baseStrength = max(0.0, highlightStrength(nearestPosition, normal, projection));
  float light = max(0.0, float(shaded.a)) * baseStrength * strengthByDirection;
  float q = light * fixedBandProfile(nearest.x, bandWidth, falloffCurve) * exclusionWeight(normal);
  if (q <= 0.0) return half4(0.0);
  float3 linearColor = toLinearSrgb(float3(shaded.rgb));
  $colorResponseCall
  float alpha = min(q, 1.0);
  return half4(fromLinearSrgb(linearColor * (q / max(alpha, 0.000001))) * alpha, alpha);
}
half4 main(float2 coord) {
  float2 p = coord - origin;
  if (p.x < tileBounds.x || p.y < tileBounds.y || p.x >= tileBounds.z || p.y >= tileBounds.w) return half4(0.0);
  return shadeAt(coord);
}
"""
        .trimIndent()
}
