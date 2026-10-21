package top.ltfan.backdrop.highlight

/** Exclusions are ordered as angle, half-angle, and feather in radians. */
internal fun exclusionShader(exclusionCount: Int): String {
    require(exclusionCount >= 0)
    if (exclusionCount == 0) return "float exclusionWeight(float2 normal) { return 1.0; }"
    return """
uniform float3 exclusions[$exclusionCount];

float exclusionWeight(float2 normal) {
  float weight = 1.0;
  for (int i = 0; i < $exclusionCount; ++i) {
    float3 exclusion = exclusions[i];
    float2 direction = float2(cos(exclusion.x), sin(exclusion.x));
    float2 relative = float2(dot(direction, normal), direction.x * normal.y - direction.y * normal.x);
    float angle = atan(relative.y, relative.x);
    float2 estimate = float2(cos(angle), sin(angle));
    angle += (estimate.x * relative.y - estimate.y * relative.x) / dot(estimate, relative);
    float difference = abs(angle);
    float allowed = difference <= exclusion.y
        ? 0.0
        : (exclusion.z > 0.0 ? smoothstep(exclusion.y, exclusion.y + exclusion.z, difference) : 1.0);
    weight = min(weight, allowed);
  }
  return weight;
}
"""
        .trimIndent()
}
