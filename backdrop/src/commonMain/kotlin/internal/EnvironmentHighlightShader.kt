package top.ltfan.backdrop.internal

import kotlin.math.ceil

/** Sampling bounds cover the outward ray from the shape edge plus a bilinear texel footprint. */
internal fun environmentHighlightSamplingOutset(sampleDistance: Float): Float {
    require(sampleDistance.isFinite() && sampleDistance > 0f)
    return ceil(sampleDistance + 0.5f)
}

internal const val EnvironmentHighlightShaderString =
    """
uniform shader content;
uniform float2 size;
uniform float2 origin;
uniform float4 cornerRadii;
uniform float width;
uniform float sampleDistance;
uniform float strength;
uniform float threshold;

// Keep the exact maximum distance, but retain the earlier normal across coordinate-rounding ties.
// Candidate order (left, right, top, bottom, then corners) is the deterministic tie priority.
float3 nearestBoundary(float3 best, float candidate, float2 inwardNormal, float tolerance) {
    float3 result = best;
    result.x = max(best.x, candidate);
    if (candidate > best.x + tolerance) result = float3(result.x, inwardNormal);
    return result;
}

float3 boundaryAt(float2 p, float2 shapeSize, float4 radii, float tolerance) {
    float3 best = float3(-p.x, 1.0, 0.0);
    best = nearestBoundary(best, p.x - shapeSize.x, float2(-1.0, 0.0), tolerance);
    best = nearestBoundary(best, -p.y, float2(0.0, 1.0), tolerance);
    best = nearestBoundary(best, p.y - shapeSize.y, float2(0.0, -1.0), tolerance);

    float r = radii.x;
    if (r > 0.0 && p.x < r && p.y < r) {
        float2 delta = float2(r, r) - p;
        float distance = length(delta);
        float2 normal = distance > 0.0 ? delta / distance : float2(0.0);
        best = nearestBoundary(best, distance - r, normal, tolerance);
    }
    r = radii.y;
    if (r > 0.0 && p.x > shapeSize.x - r && p.y < r) {
        float2 delta = float2(shapeSize.x - r, r) - p;
        float distance = length(delta);
        float2 normal = distance > 0.0 ? delta / distance : float2(0.0);
        best = nearestBoundary(best, distance - r, normal, tolerance);
    }
    r = radii.z;
    if (r > 0.0 && p.x > shapeSize.x - r && p.y > shapeSize.y - r) {
        float2 delta = float2(shapeSize.x - r, shapeSize.y - r) - p;
        float distance = length(delta);
        float2 normal = distance > 0.0 ? delta / distance : float2(0.0);
        best = nearestBoundary(best, distance - r, normal, tolerance);
    }
    r = radii.w;
    if (r > 0.0 && p.x < r && p.y > shapeSize.y - r) {
        float2 delta = float2(r, shapeSize.y - r) - p;
        float distance = length(delta);
        float2 normal = distance > 0.0 ? delta / distance : float2(0.0);
        best = nearestBoundary(best, distance - r, normal, tolerance);
    }
    return best;
}

float brightness(float3 color) {
    return dot(color, float3(0.2126, 0.7152, 0.0722));
}

half4 main(float2 coord) {
    float2 localCoord = coord - origin;
    float coordinateScale =
        max(
            1.0,
            max(
                max(abs(coord.x), abs(coord.y)),
                max(max(abs(origin.x), abs(origin.y)), max(size.x, size.y))
            )
        );
    // Scale the tie tolerance to float precision as layer origins shift in RenderEffect.
    float tolerance = 8.0 * 1.1920929e-7 * coordinateScale;
    float3 boundary = boundaryAt(localCoord, size, cornerRadii, tolerance);
    float insideDistance = max(-boundary.x, 0.0);
    if (boundary.x > 0.0 || insideDistance >= width || width <= 0.0) return half4(0.0);

    float2 outward = -boundary.yz;

    float stepDistance = sampleDistance / 3.0;
    float4 a = content.eval(coord + outward * (insideDistance + stepDistance));
    float4 b = content.eval(coord + outward * (insideDistance + 2.0 * stepDistance));
    float4 c = content.eval(coord + outward * (insideDistance + sampleDistance));
    float4 sampleColor = (a + b + c) / 3.0;
    if (!(sampleColor.a > 0.0)) return half4(0.0);

    float light = brightness(sampleColor.rgb / sampleColor.a);
    float extraction = smoothstep(threshold, threshold + max(threshold * 0.25, 0.0001), light);
    float edge = 1.0 - smoothstep(0.0, width, insideDistance);
    float alpha = sampleColor.a * extraction * edge * strength;
    return half4(sampleColor.rgb * extraction * edge * strength, alpha);
}
"""
