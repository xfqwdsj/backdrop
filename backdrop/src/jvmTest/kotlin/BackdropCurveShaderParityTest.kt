package top.ltfan.backdrop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

class BackdropCurveShaderParityTest {
    @Test
    fun runtimeShaderMatchesCpuCurveAcrossTheUnitInterval() {
        val curves =
            listOf(
                BackdropCurve.Linear,
                BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
                BackdropCurve.CubicBezier(0f, 0f, 1f, 1f),
                BackdropCurve.CubicBezier(0.2f, 0.1f, 0.8f, 0.9f),
            )
        val samples = listOf(0f, 0.01f, 0.2f, 0.5f, 0.707f, 0.99f, 1f)

        for (curve in curves) {
            val shader =
                RuntimeShader(
                    """
                uniform float x;
                uniform float4 controls;
                $BackdropCurveShader
                half4 main(float2 p) {
                    float value = backdropCurve(x, controls);
                    return half4(value, value, value, 1.0);
                }
                """
                        .trimIndent(),
                )
            shader.setFloatUniform("controls", curve.shaderControls())
            for (sample in samples) {
                shader.setFloatUniform("x", sample)
                assertEquals(
                    curve.transform(sample),
                    render(shader.asSkikoRuntimeShader().makeShader()),
                    0.004f,
                    "curve=$curve sample=$sample",
                )
            }
        }
    }

    private fun render(shader: Shader): Float {
        val info = ImageInfo(8, 8, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            Paint().use { paint ->
                paint.shader = shader
                surface.canvas.drawRect(Rect.makeWH(8f, 8f), paint)
            }
            Bitmap().use { bitmap ->
                check(
                    bitmap.allocPixels(
                        ImageInfo(1, 1, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB),
                    ),
                )
                check(surface.readPixels(bitmap, 4, 4))
                val bytes =
                    checkNotNull(
                        bitmap.readPixels(
                            ImageInfo(
                                1,
                                1,
                                ColorType.RGBA_F16,
                                ColorAlphaType.PREMUL,
                                ColorSpace.sRGB,
                            ),
                            8,
                        ),
                    )
                return ByteBuffer.wrap(bytes)
                    .order(ByteOrder.nativeOrder())
                    .short
                    .toInt()
                    .and(0xffff)
                    .toHalfFloat()
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
