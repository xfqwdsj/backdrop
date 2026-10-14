package top.ltfan.backdrop.internal

/** Shared path-boundary distance and outward-normal functions for highlight passes. */
internal fun genericBoundaryShaderFunctions(
    segmentCount: Int,
    segmentCapacity: Int = segmentCount,
): String {
    require(segmentCount > 0)
    require(segmentCapacity >= segmentCount)
    val segments = "uniform float4 segments[$segmentCapacity];"
    val normalSigns = "uniform float segmentNormalSigns[$segmentCapacity];"
    return """
uniform float2 origin;
uniform float geometryScale;
$segments
$normalSigns

bool containsPoint(float2 p) {
  bool inside = false;
  for (int i = 0; i < $segmentCapacity; ++i) {
    float4 segment = segments[i];
    float2 a = segment.xy;
    float2 b = segment.zw;
    bool crosses = (a.y > p.y) != (b.y > p.y);
    if (crosses && p.x < a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)) inside = !inside;
  }
  return inside;
}

float3 nearestBoundaryWithPosition(float2 p, out float2 closest) {
  float selectedDistance = 3.402823466e+38;
  float2 bestDelta = float2(0.0);
  float2 bestTangent = float2(1.0, 0.0);
  float bestNormalSign = 0.0;
  bool hasBest = false;
  float coordinateScale = max(geometryScale, max(abs(p.x), abs(p.y)));
  float tolerance = 9.5367432e-7 * coordinateScale;
  for (int i = 0; i < $segmentCapacity; ++i) {
    float4 segment = segments[i];
    float2 a = segment.xy;
    float2 b = segment.zw;
    float2 ab = b - a;
    float denominator = dot(ab, ab);
    if (denominator <= 0.0) continue;
    float t = clamp(dot(p - a, ab) / denominator, 0.0, 1.0);
    float2 delta = a + t * ab - p;
    float distance = length(delta);
    if (!hasBest || distance < selectedDistance - tolerance) {
      selectedDistance = distance;
      bestDelta = delta;
      bestTangent = ab;
      bestNormalSign = segmentNormalSigns[i];
      hasBest = true;
    }
  }
  closest = p + bestDelta;
  float tangentLength = length(bestTangent);
  float2 outward = tangentLength > 0.0 ? float2(bestTangent.y, -bestTangent.x) / tangentLength : float2(0.0, -1.0);
  if (bestNormalSign != 0.0 && abs(p.x) <= geometryScale && abs(p.y) <= geometryScale) {
    outward *= bestNormalSign;
  } else {
    float probeDistance = max(0.0001, tolerance * 4.0);
    if (containsPoint(closest + outward * probeDistance)) outward = -outward;
  }
  return float3(selectedDistance, outward);
}

float3 nearestBoundary(float2 p) {
  float2 closest;
  return nearestBoundaryWithPosition(p, closest);
}

"""
        .trimIndent()
}
