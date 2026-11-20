package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    // Explicit quadrants preserve the antipodal normal on GPU backends.
    float angle = relative.x == 0.0
        ? (relative.y < 0.0 ? -1.5707963267948966 : 1.5707963267948966)
        : atan(relative.y / relative.x);
    if (relative.x < 0.0) angle += relative.y < 0.0 ? -3.141592653589793 : 3.141592653589793;
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

/** CPU counterpart of [exclusionShader], evaluated against an outward contour normal. */
internal fun exclusionWeight(normal: Offset, exclusions: Set<HighlightAngularCut>): Float {
    var weight = 1f
    val radians = (PI / 180.0).toFloat()
    for ((angle, halfAngle, featherAngle) in exclusions) {
        val axis = normalizedAngle(angle) * radians
        val difference =
            abs(
                atan2(
                    cos(axis) * normal.y - sin(axis) * normal.x,
                    normal.x * cos(axis) + normal.y * sin(axis),
                ),
            )
        val start = halfAngle * radians
        val end = (halfAngle + featherAngle) * radians
        val allowed =
            when {
                difference <= start -> 0f
                featherAngle <= 0f -> 1f
                difference >= end -> 1f
                else -> smoothstep(start, end, difference)
            }
        weight = minOf(weight, allowed)
    }
    return weight
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
