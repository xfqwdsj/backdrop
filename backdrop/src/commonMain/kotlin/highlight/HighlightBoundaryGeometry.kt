package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.internal.GenericEnvironmentGeometry
import top.ltfan.backdrop.internal.genericBoundaryShaderFunctions

/** Shared outline representation used by static, exclusion, and environment rendering. */
internal class HighlightBoundaryGeometry(private val outline: Outline) {
    internal val bounds: Rect = outline.bounds
    private val analyticRadii: FloatArray?
    private val analyticBounds: Rect?
    private var flattened: GenericEnvironmentGeometry? = null

    init {
        val radii =
            when (outline) {
                is Outline.Rectangle -> floatArrayOf(0f, 0f, 0f, 0f)
                is Outline.Rounded -> circularRadii(outline)
                is Outline.Generic -> null
            }
        analyticRadii = radii?.takeIf { validRadii(bounds, it) }
        analyticBounds = if (analyticRadii != null) bounds else null
    }

    internal val isAnalytic: Boolean
        get() = analyticBounds != null

    internal val isEmpty: Boolean
        get() = if (isAnalytic) bounds.isEmpty else cpuGeometry().segmentCount == 0

    internal val segmentCount: Int
        get() = if (isAnalytic) 0 else cpuGeometry().segmentCount

    internal val coordinateScale: Float
        get() =
            if (isAnalytic)
                maxOf(
                    1f,
                    abs(bounds.left),
                    abs(bounds.top),
                    abs(bounds.right),
                    abs(bounds.bottom),
                )
            else cpuGeometry().coordinateScale

    internal val cacheKey: String
        get() =
            if (isAnalytic) "analytic:${analyticRadii!!.joinToString()}" else "path:${segmentCount}"

    internal fun cpuGeometry(): GenericEnvironmentGeometry =
        flattened ?: GenericEnvironmentGeometry(outlinePath(outline)).also { flattened = it }

    internal fun shaderFunctions(): String =
        if (isAnalytic) analyticBoundaryShaderFunctions()
        else genericBoundaryShaderFunctions(segmentCount)

    internal fun configureShader(
        shader: RuntimeShader,
        originX: Float,
        originY: Float,
    ) {
        shader.setFloatUniform("origin", originX, originY)
        shader.setFloatUniform("geometryScale", coordinateScale)
        if (isAnalytic) {
            val rect = requireNotNull(analyticBounds)
            shader.setFloatUniform("boundaryBounds", rect.left, rect.top, rect.right, rect.bottom)
            shader.setFloatUniform("cornerRadii", requireNotNull(analyticRadii))
        } else {
            shader.setFloatUniform("segments", cpuGeometry().segments)
        }
    }

    private fun circularRadii(outline: Outline.Rounded): FloatArray? {
        val roundRect = outline.roundRect
        val corners =
            listOf(
                roundRect.topLeftCornerRadius,
                roundRect.topRightCornerRadius,
                roundRect.bottomRightCornerRadius,
                roundRect.bottomLeftCornerRadius,
            )
        if (
            corners.any {
                !it.x.isFinite() || !it.y.isFinite() || it.x < 0f || it.y < 0f || it.x != it.y
            }
        )
            return null
        return FloatArray(4) { corners[it].x }
    }

    private fun validRadii(bounds: Rect, radii: FloatArray): Boolean {
        val (topLeft, topRight, bottomRight, bottomLeft) = radii
        return topLeft + topRight <= bounds.width &&
            bottomLeft + bottomRight <= bounds.width &&
            topLeft + bottomLeft <= bounds.height &&
            topRight + bottomRight <= bounds.height
    }

    private fun outlinePath(outline: Outline): Path =
        Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> {
                    fillType = outline.path.fillType
                    addPath(outline.path)
                }
            }
        }
}

private fun analyticBoundaryShaderFunctions(): String =
    """
    uniform float2 origin;
    uniform float geometryScale;
    uniform float4 boundaryBounds;
    uniform float4 cornerRadii;

    float3 nearestCandidate(float3 best, float candidate, float2 inwardNormal, float tolerance) {
      float3 result = best;
      result.x = max(best.x, candidate);
      if (candidate > best.x + tolerance) result = float3(result.x, inwardNormal);
      return result;
    }

    float3 boundaryAt(float2 p, float2 shapeSize, float4 radii, float tolerance) {
      float3 best = float3(-p.x, 1.0, 0.0);
      best = nearestCandidate(best, p.x - shapeSize.x, float2(-1.0, 0.0), tolerance);
      best = nearestCandidate(best, -p.y, float2(0.0, 1.0), tolerance);
      best = nearestCandidate(best, p.y - shapeSize.y, float2(0.0, -1.0), tolerance);
      float r = radii.x;
      if (r > 0.0 && p.x < r && p.y < r) {
        float2 delta = float2(r, r) - p;
        float distance = length(delta);
        best = nearestCandidate(best, distance - r, distance > 0.0 ? delta / distance : float2(0.0), tolerance);
      }
      r = radii.y;
      if (r > 0.0 && p.x > shapeSize.x-r && p.y < r) {
        float2 delta = float2(shapeSize.x-r, r) - p;
        float distance = length(delta);
        best = nearestCandidate(best, distance-r, distance > 0.0 ? delta / distance : float2(0.0), tolerance);
      }
      r = radii.z;
      if (r > 0.0 && p.x > shapeSize.x-r && p.y > shapeSize.y-r) {
        float2 delta = float2(shapeSize.x-r, shapeSize.y-r) - p;
        float distance = length(delta);
        best = nearestCandidate(best, distance-r, distance > 0.0 ? delta / distance : float2(0.0), tolerance);
      }
      r = radii.w;
      if (r > 0.0 && p.x < r && p.y > shapeSize.y-r) {
        float2 delta = float2(r, shapeSize.y-r) - p;
        float distance = length(delta);
        best = nearestCandidate(best, distance-r, distance > 0.0 ? delta / distance : float2(0.0), tolerance);
      }
      return best;
    }

    float boundaryTolerance(float2 coord) {
      float scale = max(geometryScale, max(max(abs(coord.x), abs(coord.y)), max(abs(origin.x), abs(origin.y))));
      return 8.0 * 1.1920929e-7 * max(1.0, scale);
    }

    bool containsPoint(float2 p) {
      float2 local = p - boundaryBounds.xy;
      float2 shapeSize = boundaryBounds.zw - boundaryBounds.xy;
      return boundaryAt(local, shapeSize, cornerRadii, boundaryTolerance(p)).x <= 0.0;
    }

    float3 nearestBoundary(float2 p) {
      float2 local = p - boundaryBounds.xy;
      float2 shapeSize = boundaryBounds.zw - boundaryBounds.xy;
      float3 boundary = boundaryAt(local, shapeSize, cornerRadii, boundaryTolerance(p));
      return float3(max(-boundary.x, 0.0), -boundary.yz);
    }
    """
        .trimIndent()
