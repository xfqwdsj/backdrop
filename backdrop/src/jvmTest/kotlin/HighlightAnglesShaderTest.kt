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
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.highlight.HighlightAngleDomain
import top.ltfan.backdrop.highlight.angularDirectionShader
import top.ltfan.backdrop.highlight.highlightAngles

class HighlightAnglesShaderTest : SkikoGraphicsTest() {
    @Test
    fun angularShaderMatchesCpuForRotationFoldingSeamsAndClearing() {
        val count = 1441
        for (domain in HighlightAngleDomain.entries) {
            val limit = if (domain == HighlightAngleDomain.Unsigned) 180f else 360f
            val profile =
                highlightAngles(domain) {
                    widthScale(0f, limit, .2f, 2f, BackdropCurve.CubicBezier(.1f, .3f, .7f, .8f))
                    brightness(0f, limit, 2f, .5f)
                    clear(setOf(40f..50f, 90f..110f))
                    widthScale(45f, 46f, 3f)
                }
            for (rotation in listOf(0f, 37.25f, 270f)) {
                val shader =
                    RuntimeShader(
                        """
                    uniform float2 directionVector;
                    uniform float rotation;
                    $BackdropCurveShader
                    ${angularDirectionShader(profile.capacity)}
                    half4 main(float2 p) {
                        float angle = radians(floor(p.x) * 0.25 + rotation);
                        float2 value = angularResponse(float2(cos(angle), sin(angle)));
                        return half4(value, 0.0, 1.0);
                    }
                """
                            .trimIndent(),
                    )
                val radians = Math.toRadians(rotation.toDouble())
                shader.setFloatUniform(
                    "directionVector",
                    kotlin.math.cos(radians).toFloat(),
                    kotlin.math.sin(radians).toFloat(),
                )
                shader.setFloatUniform("rotation", rotation)
                profile.upload(shader)
                val info =
                    ImageInfo(count, 1, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
                Surface.makeRaster(info).use { surface ->
                    Paint().use { paint ->
                        paint.shader = shader.asSkikoRuntimeShader().makeShader()
                        surface.canvas.drawRect(Rect.makeWH(count.toFloat(), 1f), paint)
                    }
                    Bitmap().use { bitmap ->
                        check(bitmap.allocPixels(info))
                        check(surface.readPixels(bitmap, 0, 0))
                        val buffer =
                            ByteBuffer.wrap(checkNotNull(bitmap.readPixels(info, count * 8)))
                                .order(ByteOrder.nativeOrder())
                        repeat(count) { i ->
                            val angle = i * .25f
                            val width = buffer.getShort(i * 8).toInt().and(0xffff).toHalfFloat()
                            val brightness =
                                buffer.getShort(i * 8 + 2).toInt().and(0xffff).toHalfFloat()
                            assertEquals(
                                profile.widthScaleAt(angle),
                                width,
                                .003f,
                                "width domain=$domain rotation=$rotation angle=$angle",
                            )
                            assertEquals(
                                profile.brightnessAt(angle),
                                brightness,
                                .003f,
                                "brightness domain=$domain rotation=$rotation angle=$angle",
                            )
                        }
                    }
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
