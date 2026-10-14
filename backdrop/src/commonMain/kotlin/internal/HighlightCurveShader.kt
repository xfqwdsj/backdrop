package top.ltfan.backdrop.internal

import kotlin.math.abs
import top.ltfan.backdrop.BackdropCurve

/** Bracketed Newton inversion with a bisection fallback for highlight response curves. */
internal fun highlightCurveShader(curve: BackdropCurve): String =
    when {
        isLinearHighlightCurve(curve) ->
            """
            float backdropCurve(float x, float4 controls) {
              if (x <= 0.0) return 0.0;
              if (x >= 1.0) return 1.0;
              return x;
            }
            """
                .trimIndent()
        curve is BackdropCurve.CubicBezier &&
            abs(curve.x1 - 0.33333334f) < 0.0000001f &&
            abs(curve.x2 - 0.6666667f) < 0.0000001f ->
            """
            float backdropCurveAxis(float t, float first, float second) {
              float inverse = 1.0 - t;
              return 3.0 * inverse * inverse * t * first + 3.0 * inverse * t * t * second + t * t * t;
            }
            float backdropCurve(float x, float4 controls) {
              if (x <= 0.0) return 0.0;
              if (x >= 1.0) return 1.0;
              return backdropCurveAxis(x, controls.y, controls.w);
            }
            """
                .trimIndent()
        else -> GeneralHighlightCurveShader
    }

internal fun highlightCurveVariant(curve: BackdropCurve): String =
    when {
        isLinearHighlightCurve(curve) -> "linear"
        curve is BackdropCurve.CubicBezier &&
            abs(curve.x1 - 0.33333334f) < 0.0000001f &&
            abs(curve.x2 - 0.6666667f) < 0.0000001f -> "linear-x-cubic-y"
        else -> "general"
    }

private fun isLinearHighlightCurve(curve: BackdropCurve): Boolean =
    curve == BackdropCurve.Linear ||
        (curve is BackdropCurve.CubicBezier &&
            curve.x1 == 0f &&
            curve.y1 == 0f &&
            curve.x2 == 1f &&
            curve.y2 == 1f)

private val GeneralHighlightCurveShader: String =
    """
    float backdropCurveAxis(float t, float first, float second) {
      float inverse = 1.0 - t;
      return 3.0 * inverse * inverse * t * first + 3.0 * inverse * t * t * second + t * t * t;
    }

    float backdropCurve(float x, float4 controls) {
      if (x <= 0.0) return 0.0;
      if (x >= 1.0) return 1.0;
      if (controls.x == 0.0 && controls.y == 0.0 && controls.z == 1.0 && controls.w == 1.0) return x;
      if (abs(controls.x - 0.333333333) < 0.0000001 && abs(controls.z - 0.666666667) < 0.0000001) {
        return backdropCurveAxis(x, controls.y, controls.w);
      }
      float low = 0.0;
      float high = 1.0;
      float t = x;
      for (int i = 0; i < 8; ++i) {
        float error = backdropCurveAxis(t, controls.x, controls.z) - x;
        float inverse = 1.0 - t;
        float derivative = 3.0 * inverse * inverse * controls.x
          + 6.0 * inverse * t * (controls.z - controls.x)
          + 3.0 * t * t * (1.0 - controls.z);
        if (derivative > 0.01 && abs(error) < 0.0000001) {
          return backdropCurveAxis(t, controls.y, controls.w);
        }
        if (error < 0.0) low = t; else high = t;
        float next = derivative > 0.01 ? t - error / derivative : (low + high) * 0.5;
        t = next > low && next < high ? next : (low + high) * 0.5;
      }

      for (int i = 0; i < 16; ++i) {
        t = (low + high) * 0.5;
        if (backdropCurveAxis(t, controls.x, controls.z) < x) low = t; else high = t;
      }
      return backdropCurveAxis(t, controls.y, controls.w);
    }
    """
        .trimIndent()

internal val HighlightCurveShader: String = GeneralHighlightCurveShader
