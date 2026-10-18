package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry
import top.ltfan.backdrop.internal.HighlightCurveShader
import top.ltfan.backdrop.internal.highlightCurveVariant
import top.ltfan.backdrop.internal.staticHighlightTileShader

class HighlightCurveShaderParityTest : SkikoGraphicsTest() {
    @Test
    fun falloffVariantsDoNotReplaceTheIndependentDirectionCurve() {
        val geometry =
            FlattenedBoundaryGeometry(Path().apply { addRect(ComposeRect(0f, 0f, 20f, 20f)) })
        val general = BackdropCurve.CubicBezier(.2f, .3f, .7f, .9f)

        val linearFalloff =
            staticHighlightTileShader(
                geometry,
                HighlightStyle.Solid(Color.White),
                exclusionCount = 0,
                falloff = BackdropCurve.Linear,
                sharedDirectionCurve = true,
            )
        assertEquals("linear", highlightCurveVariant(BackdropCurve.Linear))
        assertTrue(linearFalloff.contains("float backdropCurve(float x, float4 controls)"))
        assertTrue(linearFalloff.contains("float backdropFalloffCurve(float x, float4 controls)"))
        assertTrue(linearFalloff.contains("float sharedDirectionLevel = backdropCurve(magnitude"))
        assertTrue(
            linearFalloff.contains("float error = backdropCurveAxis(t, controls.x, controls.z) - x")
        )

        val independentDirection =
            staticHighlightTileShader(
                geometry,
                HighlightStyle.Solid(Color.White),
                exclusionCount = 0,
                falloff = BackdropCurve.CubicBezier(0f, 0f, 1f, 1f),
                sharedDirectionCurve = false,
            )
        assertEquals("linear", highlightCurveVariant(BackdropCurve.CubicBezier(0f, 0f, 1f, 1f)))
        assertTrue(independentDirection.contains("uniform float4 directionWidthCurve;"))
        assertTrue(independentDirection.contains("response(magnitude, directionStrengthRange"))
        assertTrue(independentDirection.contains("backdropFalloffCurve(progress, curve)"))
        assertEquals("general", highlightCurveVariant(general))
    }

    @Test
    fun optimizedHighlightCurveMatchesCpuAcrossExtremeAndDefaultControls() {
        val curves =
            listOf(
                BackdropCurve.Linear,
                BackdropCurve.CubicBezier(0f, 0f, 0f, 0f),
                BackdropCurve.CubicBezier(0f, 1f, 0f, 1f),
                BackdropCurve.CubicBezier(0f, 0f, 1f, 1f),
                BackdropCurve.CubicBezier(1f, 1f, 1f, 1f),
                BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
                BackdropCurve.Default,
                BackdropCurve.CubicBezier(.9f, 0f, 1f, .1f),
                BackdropCurve.CubicBezier(.6f, 0f, 1f, .2f),
            )
        val samples = buildList {
            add(0f)
            add(.000001f)
            repeat(1001) { add(it / 1000f) }
            add(.999999f)
            add(1f)
        }

        for (curve in curves) {
            val rendered = renderCurve(curve, samples.size)
            for (index in samples.indices) {
                assertEquals(
                    curve.transform(samples[index]),
                    rendered[index],
                    .001f,
                    "curve=$curve sample=${samples[index]} index=$index",
                )
            }
        }
    }

    private fun renderCurve(curve: BackdropCurve, sampleCount: Int): FloatArray {
        val shader =
            RuntimeShader(
                """
                uniform float4 controls;
                $HighlightCurveShader
                half4 main(float2 p) {
                  float index = floor(p.x);
                  float x = index == 0.0 ? 0.0
                    : index == 1.0 ? 0.000001
                    : index < 1003.0 ? (index - 2.0) / 1000.0
                    : index == 1003.0 ? 0.999999
                    : 1.0;
                  float value = backdropCurve(x, controls);
                  return half4(value, value, value, 1.0);
                }
                """
                    .trimIndent()
            )
        check(sampleCount == 1005)
        shader.setFloatUniform("controls", curve.shaderControls())
        val runtimeShader = shader.asSkikoRuntimeShader().makeShader()
        val info =
            ImageInfo(sampleCount, 1, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            Paint().use { paint ->
                paint.shader = runtimeShader
                surface.canvas.drawRect(Rect.makeWH(sampleCount.toFloat(), 1f), paint)
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(surface.readPixels(bitmap, 0, 0))
                val bytes = checkNotNull(bitmap.readPixels(info, sampleCount * 8))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(sampleCount) { index ->
                    val offset = index * 8
                    buffer.position(offset)
                    buffer.short.toInt().and(0xffff).toHalfFloat()
                }
            }
        }
    }

    private fun Int.toHalfFloat(): Float {
        val sign = if (this and 0x8000 == 0) 1f else -1f
        val exponent = (this ushr 10) and 31
        val mantissa = this and 1023
        return sign *
            when (exponent) {
                0 -> Math.scalb(mantissa.toFloat(), -24)
                31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Math.scalb(1f + mantissa / 1024f, exponent - 15)
            }
    }
}
