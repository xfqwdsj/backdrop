package top.ltfan.backdrop

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable

/** Iterations both this file and the progressive blur shader run to invert the curve. */
internal const val BezierSolveIterations = 16

/** One axis of a cubic bezier from 0 to 1 through [first] and [second]. */
private fun coordinate(t: Float, first: Float, second: Float): Float {
    val inverse = 1f - t
    return 3f * inverse * inverse * t * first + 3f * inverse * t * t * second + t * t * t
}

/** A bounded response curve shared by CPU and shader evaluation. */
@Immutable
public sealed interface BackdropCurve {
    public fun transform(input: Float): Float

    public fun asEasing(): Easing = Easing { transform(it) }

    public companion object {
        public val Default: BackdropCurve = CubicBezier(0.35f, 0f, 0.6f, 1f)
    }

    @Immutable
    public data object Linear : BackdropCurve {
        override fun transform(input: Float): Float = input.coerceIn(0f, 1f)
    }

    @Immutable
    public data class CubicBezier(
        public val x1: Float,
        public val y1: Float,
        public val x2: Float,
        public val y2: Float,
    ) : BackdropCurve {
        init {
            require(listOf(x1, y1, x2, y2).all { it.isFinite() && it in 0f..1f }) {
                "Curve controls must be finite and within 0..1"
            }
            require(x1 <= x2 && y1 <= y2) { "Curve controls must be ordered" }
        }

        /**
         * The curve's output for [input], solved the way the shader solves it, so a mask painted
         * from this value and a blur evaluated in a shader stay on one curve.
         */
        override fun transform(input: Float): Float {
            if (input <= 0f) return 0f
            if (input >= 1f) return 1f
            if (x1 == 0f && y1 == 0f && x2 == 1f && y2 == 1f) return input
            val x = input.coerceIn(0f, 1f)
            var low = 0f
            var high = 1f
            var t = x
            repeat(BezierSolveIterations) {
                t = (low + high) * 0.5f
                if (coordinate(t, x1, x2) < x) low = t else high = t
            }
            return coordinate(t, y1, y2)
        }
    }
}

internal fun BackdropCurve.shaderControls(): FloatArray =
    when (this) {
        BackdropCurve.Linear -> floatArrayOf(0f, 0f, 1f, 1f)
        is BackdropCurve.CubicBezier -> floatArrayOf(x1, y1, x2, y2)
    }

/** Pixel-footprint integrated falloff from a boundary to the inner edge of a band. */
internal fun highlightBandProfile(distance: Float, width: Float, curve: BackdropCurve): Float {
    if (width <= 0f) return 0f
    val low = maxOf(0f, distance - 0.5f)
    val high = minOf(width, distance + 0.5f)
    val coverage = maxOf(0f, high - low)
    if (coverage == 0f) return 0f
    val progress = (1f - (low + high) / (2f * width)).coerceIn(0f, 1f)
    return coverage * curve.transform(progress)
}

/** Shared inversion used by shaders that evaluate [BackdropCurve] controls. */
internal const val BackdropCurveShader: String =
    """
float backdropCurveAxis(float t, float first, float second) {
  float inverse = 1.0 - t;
  return 3.0 * inverse * inverse * t * first + 3.0 * inverse * t * t * second + t * t * t;
}

float backdropCurve(float x, float4 controls) {
  if (x <= 0.0) return 0.0;
  if (x >= 1.0) return 1.0;
  if (controls.x == 0.0 && controls.y == 0.0 && controls.z == 1.0 && controls.w == 1.0) return x;
  float low = 0.0;
  float high = 1.0;
  float t = x;
  for (int i = 0; i < 16; i++) {
    t = (low + high) * 0.5;
    if (backdropCurveAxis(t, controls.x, controls.z) < x) low = t;
    else high = t;
  }
  return backdropCurveAxis(t, controls.y, controls.w);
}

float highlightBandProfile(float distance, float width, float4 depthCurve) {
  if (width <= 0.0) return 0.0;
  float low = max(0.0, distance - 0.5);
  float high = min(width, distance + 0.5);
  float coverage = max(0.0, high - low);
  if (coverage <= 0.0) return 0.0;
  float progress = clamp(1.0 - (low + high) / (2.0 * width), 0.0, 1.0);
  return coverage * backdropCurve(progress, depthCurve);
}
"""
