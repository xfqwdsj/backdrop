package top.ltfan.backdrop.internal

/** Builds a runtime shader specialized to the number of line segments in a flattened outline. */
internal fun genericEnvironmentHighlightShader(segmentCount: Int): String {
    require(segmentCount > 0)
    return """
uniform shader content;
uniform float2 origin;
uniform float geometryScale;
uniform float width;
uniform float sampleDistance;
uniform float strength;
uniform float threshold;
uniform float4 segments[$segmentCount];

bool containsPoint(float2 p) {
    bool inside = false;
    for (int i = 0; i < $segmentCount; ++i) {
        float4 segment = segments[i];
        float2 a = segment.xy;
        float2 b = segment.zw;
        bool crosses = (a.y > p.y) != (b.y > p.y);
        if (crosses && p.x < a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)) {
            inside = !inside;
        }
    }
    return inside;
}

float3 nearestBoundary(float2 p) {
    float bestDistance = 3.402823466e+38;
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
        bestDistance = min(bestDistance, distance);
        if (!hasBest || distance < selectedDistance - tolerance) {
            selectedDistance = distance;
            bestDelta = delta;
            bestTangent = ab;
            hasBest = true;
        }
    }
    float2 outward;
    if (selectedDistance > 0.0) {
        outward = bestDelta / selectedDistance;
    } else {
        float tangentLength = length(bestTangent);
        float2 normal = tangentLength > 0.0
            ? float2(bestTangent.y, -bestTangent.x) / tangentLength
            : float2(0.0, -1.0);
        float probeDistance = max(0.0001, tolerance * 4.0);
        outward = containsPoint(p + normal * probeDistance) ? -normal : normal;
    }
    return float3(bestDistance, outward);
}

float brightness(float3 color) {
    return dot(color, float3(0.2126, 0.7152, 0.0722));
}

half4 main(float2 coord) {
    float2 localCoord = coord - origin;
    float3 boundary = nearestBoundary(localCoord);
    float insideDistance = boundary.x;
    if (insideDistance >= width || width <= 0.0) return half4(0.0);
    float2 outward = boundary.yz;

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
        .trimIndent()
}
