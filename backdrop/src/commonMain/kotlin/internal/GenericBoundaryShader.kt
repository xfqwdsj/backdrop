package top.ltfan.backdrop.internal

/** Shared path-boundary distance and outward-normal functions for highlight passes. */
internal fun genericBoundaryShaderFunctions(segmentCount: Int): String {
    require(segmentCount > 0)
    return """
uniform float2 origin;
uniform float geometryScale;
uniform float4 segments[$segmentCount];

bool containsPoint(float2 p) {
  bool inside = false;
  for (int i = 0; i < $segmentCount; ++i) {
    float4 segment = segments[i];
    float2 a = segment.xy;
    float2 b = segment.zw;
    bool crosses = (a.y > p.y) != (b.y > p.y);
    if (crosses && p.x < a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)) inside = !inside;
  }
  return inside;
}

float3 nearestBoundary(float2 p) {
  float selectedDistance = 3.402823466e+38;
  float2 bestDelta = float2(0.0);
  float2 bestTangent = float2(1.0, 0.0);
  bool hasBest = false;
  float coordinateScale = max(geometryScale, max(abs(p.x), abs(p.y)));
  float tolerance = 9.5367432e-7 * coordinateScale;
  for (int i = 0; i < $segmentCount; ++i) {
    float4 segment = segments[i];
    float2 a = segment.xy;
    float2 b = segment.zw;
    float2 ab = b - a;
    float denominator = dot(ab, ab);
    float t = denominator > 0.0 ? clamp(dot(p - a, ab) / denominator, 0.0, 1.0) : 0.0;
    float2 delta = a + t * ab - p;
    float distance = length(delta);
    if (!hasBest || distance < selectedDistance - tolerance) {
      selectedDistance = distance;
      bestDelta = delta;
      bestTangent = ab;
      hasBest = true;
    }
  }
  float2 outward;
  if (selectedDistance > 0.0) outward = bestDelta / selectedDistance;
  else {
    float tangentLength = length(bestTangent);
    float2 normal = tangentLength > 0.0 ? float2(bestTangent.y, -bestTangent.x) / tangentLength : float2(0.0, -1.0);
    float probeDistance = max(0.0001, tolerance * 4.0);
    outward = containsPoint(p + normal * probeDistance) ? -normal : normal;
  }
  return float3(selectedDistance, outward);
}
"""
        .trimIndent()
}
