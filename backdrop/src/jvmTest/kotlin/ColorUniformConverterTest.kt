package top.ltfan.backdrop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.internal.ColorUniformConverter
import top.ltfan.backdrop.internal.colorUniformComponents

class ColorUniformConverterTest {
    @Test
    fun reusesConversionStorageAcrossColorSpacesWithoutChangingColorValues() {
        val colors =
            listOf(
                Color(0.2f, 0.4f, 0.6f, 0.7f),
                Color(4f, 3f, 2f, colorSpace = ColorSpaces.LinearExtendedSrgb),
                Color(-0.25f, 2f, 0.5f, colorSpace = ColorSpaces.LinearExtendedSrgb),
                Color(2f, -0.2f, 0.3f, colorSpace = ColorSpaces.ExtendedSrgb),
                Color(1.2f, -0.1f, 0.4f, alpha = 0f, colorSpace = ColorSpaces.DisplayP3),
                Color(0.1f, 0.3f, 0.5f, colorSpace = ColorSpaces.Srgb),
            )
        val converter = ColorUniformConverter()
        val savedCallerOwned = colorUniformComponents(colors[1])
        val savedValues = savedCallerOwned.copyOf()

        repeat(3) {
            for (color in colors) {
                assertContentEquals(colorUniformComponents(color), converter.write(color))
            }
        }
        assertContentEquals(savedValues, savedCallerOwned)
        assertNotSame(savedCallerOwned, colorUniformComponents(colors[1]))

        val linearExtended = converter.write(colors[1])
        assertTrue(linearExtended.all { it > 1f })
        val linearNegative = converter.write(colors[2])
        assertTrue(linearNegative[0] < 0f)
        assertTrue(linearNegative[1] > 1f)
        val extended = converter.write(colors[3])
        assertTrue(extended[0] > 1f)
        assertTrue(extended[1] < 0f)
        assertTrue(converter.write(colors[4]).all { it.isFinite() })
        assertEquals(0f, colors[4].alpha)
    }

    @Test
    fun layoutColorUniformsCopyScalarValuesBeforeScratchStorageIsReused() {
        val shader =
            RuntimeShader(
                """
                layout(color) uniform float4 firstColor;
                layout(color) uniform float4 secondColor;
                float4 main(float2 coord) {
                    return float4(
                        (firstColor.rgb * firstColor.a + secondColor.rgb * secondColor.a) * 0.5,
                        (firstColor.a + secondColor.a) * 0.5
                    );
                }
                """
            )
        shader.setColorUniform("firstColor", Color(1f, 0f, 0f, 0.4f))
        shader.setColorUniform("secondColor", Color(0f, 0f, 1f, 0.8f))

        assertPixel(
            floatArrayOf(0.2f, 0f, 0.4f, 0.6f),
            render(shader.asSkikoRuntimeShader().makeShader()),
        )

        val transparentShader =
            RuntimeShader(
                """
                layout(color) uniform float4 color;
                float4 main(float2 coord) { return float4(color.rgb * color.a, color.a); }
                """
            )
        transparentShader.setColorUniform(
            "color",
            Color(1.2f, -0.1f, 0.4f, alpha = 0f, colorSpace = ColorSpaces.DisplayP3),
        )
        assertPixel(
            floatArrayOf(0f, 0f, 0f, 0f),
            render(transparentShader.asSkikoRuntimeShader().makeShader()),
        )
    }

    private fun render(shader: Shader): FloatArray {
        val info = ImageInfo(1, 1, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            Paint().use { paint ->
                paint.shader = shader
                surface.canvas.drawRect(Rect.makeWH(1f, 1f), paint)
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(surface.readPixels(bitmap, 0, 0))
                val bytes = requireNotNull(bitmap.readPixels(info, 8))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(4) { halfToFloat(buffer.short.toInt() and 0xffff) }
            }
        }
    }

    private fun assertPixel(expected: FloatArray, actual: FloatArray) {
        for (index in expected.indices) {
            assertTrue(abs(expected[index] - actual[index]) < 0.01f)
        }
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = if (bits and 0x8000 == 0) 1f else -1f
        val exponent = (bits ushr 10) and 31
        val mantissa = bits and 1023
        return sign *
            when (exponent) {
                0 -> Math.scalb(mantissa.toFloat(), -24)
                31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Math.scalb(1f + mantissa / 1024f, exponent - 15)
            }
    }
}
