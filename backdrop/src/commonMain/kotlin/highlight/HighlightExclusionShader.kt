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
    float difference = acos(clamp(dot(normal, float2(cos(exclusion.x), sin(exclusion.x))), -1.0, 1.0));
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
