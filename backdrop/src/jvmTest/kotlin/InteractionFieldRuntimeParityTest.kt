package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.interaction.interactionFieldResponse
import top.ltfan.backdrop.interaction.interactionFieldShader
import top.ltfan.backdrop.interaction.interactionInteriorShader
import top.ltfan.backdrop.interaction.interactionLightWeight

class InteractionFieldRuntimeParityTest {
    @Test
    fun cachedProgramsMatchCpuForChangingCountsAndRetainCapacityVariants() {
        val cache = RuntimeShaderCacheImpl()
        var shaderBuilds = 0
        fun obtain(capacity: Int): RuntimeShader =
            cache.obtainRuntimeShader("interactionField:$capacity") {
                shaderBuilds++
                interactionFieldShader(capacity) + fieldProbeMain()
            }

        val lights =
            listOf(
                TestLight(
                    0f,
                    .5f,
                    2f,
                    1.2f,
                    .5f,
                    1f,
                    0f,
                    0f,
                    BackdropCurve.CubicBezier(.25f, .1f, .75f, .9f),
                ),
                TestLight(1f, .5f, 1.5f, .9f, .75f, 0f, 0f, 1f, BackdropCurve.Linear),
                TestLight(.5f, .5f, 20f, 100f, 0f, 0f, 1f, 0f, BackdropCurve.Linear),
                TestLight(10f, 10f, .1f, 1f, 1f, 1f, 1f, 1f, BackdropCurve.Linear),
            )
        val shader4 = obtain(4)
        for (count in listOf(0, 1, 2, 4, 0)) {
            upload(shader4, lights, count, 4)
            val actual = render(shader4, 4)
            for (x in 0 until 4) {
                val expected = cpuField(Offset(x + .5f, .5f), lights.take(count))
                for (channel in 0..3) {
                    assertEquals(
                        expected[channel],
                        actual[x * 4 + channel],
                        .002f,
                        "capacity=4 count=$count x=$x channel=$channel",
                    )
                }
            }
        }

        val eightLights =
            List(8) { TestLight(.5f, .5f, 4f, .1f, 1f, 1f, .25f, .05f, BackdropCurve.Linear) }
        val shader8 = obtain(8)
        upload(shader8, eightLights, 8, 8)
        val eightResult = render(shader8, 1)
        val eightExpected = cpuField(Offset(.5f, .5f), eightLights)
        for (channel in 0..3) {
            assertEquals(
                eightExpected[channel],
                eightResult[channel],
                .002f,
                "capacity=8 channel=$channel",
            )
        }
        assertTrue(
            eightResult[3] > 1f - exp(-.4f) + .1f,
            "all eight sources contribute; a four-source truncation would have a lower response",
        )

        assertSame(
            shader4,
            obtain(4),
            "returning to capacity four reuses the earlier shader instance",
        )
        assertSame(shader8, obtain(8), "capacity eight remains cached")
        assertEquals(2, shaderBuilds, "the 4 -> 8 -> 4 sequence builds one program per capacity")
    }

    @Test
    fun extendedLinearSourceValuesRemainExtendedInShaderAndCpuMath() {
        val light = TestLight(.5f, .5f, 2f, 1f, 1f, 2.5f, -.25f, 0f, BackdropCurve.Linear)
        val expected = cpuField(Offset(.5f, .5f), listOf(light))
        assertEquals(2.5f, expected[0], .0001f)
        assertEquals(-.25f, expected[1], .0001f)

        val shader = RuntimeShader(interactionFieldShader(4) + fieldProbeMain())
        upload(shader, listOf(light), 1, 4)
        val actual = render(shader, 1)
        assertEquals(expected[0], actual[0], .002f)
        assertEquals(expected[1], actual[1], .002f)
        // RGBA_F16 raster readback checks shader numerics only; it does not validate display HDR
        // output.
    }

    @Test
    fun actualInteriorShaderPreservesLinearOutputAcrossWorkingSpacesAndAlphaTypes() {
        for (workingSpace in listOf(ColorSpace.sRGB, ColorSpace.sRGBLinear)) {
            for (alphaType in listOf(ColorAlphaType.PREMUL, ColorAlphaType.UNPREMUL)) {
                for (sourceValue in listOf(1f, 4f)) {
                    for (opacity in listOf(.15f, 1f)) {
                        val shader = RuntimeShader(interactionInteriorShader(4))
                        val light =
                            TestLight(
                                .5f,
                                .5f,
                                8f,
                                1f,
                                1f,
                                sourceValue,
                                sourceValue,
                                sourceValue,
                                BackdropCurve.Linear,
                            )
                        upload(shader, listOf(light), 1, 4)
                        shader.setFloatUniform("opacity", opacity)

                        val actual = render(shader, 1, workingSpace, alphaType)
                        val response = interactionFieldResponse(1f)
                        val expectedAlpha = opacity * response
                        val workingSpaceValue =
                            if (workingSpace == ColorSpace.sRGB) encodeSrgb(sourceValue)
                            else sourceValue
                        val expectedChannel =
                            if (alphaType == ColorAlphaType.PREMUL) {
                                workingSpaceValue * expectedAlpha
                            } else {
                                workingSpaceValue
                            }
                        for (channel in 0..2) {
                            assertEquals(
                                expectedChannel,
                                actual[channel],
                                .015f,
                                "space=$workingSpace alphaType=$alphaType source=$sourceValue opacity=$opacity channel=$channel",
                            )
                        }
                        assertEquals(
                            expectedAlpha,
                            actual[3],
                            .003f,
                            "alpha remains the interaction response times opacity",
                        )
                    }
                }
            }
        }
    }

    private fun encodeSrgb(linear: Float): Float =
        if (linear <= .0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - .055f

    private fun upload(shader: RuntimeShader, lights: List<TestLight>, count: Int, capacity: Int) {
        shader.setFloatUniform("interactionRowX", floatArrayOf(1f, 0f, 0f, 0f))
        shader.setFloatUniform("interactionRowY", floatArrayOf(0f, 1f, 0f, 0f))
        shader.setFloatUniform("interactionRowW", floatArrayOf(0f, 0f, 1f, 0f))
        val positions = FloatArray(capacity * 4)
        val colors = FloatArray(capacity * 4)
        val parameters = FloatArray(capacity * 4)
        val curves = FloatArray(capacity * 4)
        lights.forEachIndexed { index, light ->
            val offset = index * 4
            positions[offset] = light.x
            positions[offset + 1] = light.y
            colors[offset] = light.red
            colors[offset + 1] = light.green
            colors[offset + 2] = light.blue
            colors[offset + 3] = light.alpha
            parameters[offset] = light.radius
            parameters[offset + 1] = light.intensity
            when (val curve = light.curve) {
                BackdropCurve.Linear -> {
                    curves[offset + 2] = 1f
                    curves[offset + 3] = 1f
                }
                is BackdropCurve.CubicBezier -> {
                    curves[offset] = curve.x1
                    curves[offset + 1] = curve.y1
                    curves[offset + 2] = curve.x2
                    curves[offset + 3] = curve.y2
                }
            }
        }
        shader.setFloatUniform("interactionPositions", positions)
        shader.setFloatUniform("interactionColors", colors)
        shader.setFloatUniform("interactionParameters", parameters)
        shader.setFloatUniform("interactionCurves", curves)
        shader.setIntUniform("interactionLightCount", count)
    }

    private fun cpuField(point: Offset, lights: List<TestLight>): FloatArray {
        var totalWeight = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        for ((x, y, radius, intensity, a, r, g, b, curve) in lights) {
            val dx = point.x - x
            val dy = point.y - y
            val weight =
                interactionLightWeight(
                    sqrt(dx * dx + dy * dy),
                    radius,
                    a,
                    intensity,
                    curve,
                )
            totalWeight += weight
            red += r * weight
            green += g * weight
            blue += b * weight
        }
        if (totalWeight <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            red / totalWeight,
            green / totalWeight,
            blue / totalWeight,
            interactionFieldResponse(totalWeight),
        )
    }

    private fun render(
        shader: RuntimeShader,
        width: Int,
        colorSpace: ColorSpace? = null,
        alphaType: ColorAlphaType = ColorAlphaType.UNPREMUL,
    ): FloatArray {
        val info =
            if (colorSpace == null) {
                ImageInfo(width, 1, ColorType.RGBA_F16, alphaType)
            } else {
                ImageInfo(width, 1, ColorType.RGBA_F16, alphaType, colorSpace)
            }
        Surface.makeRaster(info).use { surface ->
            SkiaPaint().use { paint ->
                paint.shader = shader.asSkikoRuntimeShader().makeShader()
                surface.canvas.drawRect(Rect.makeWH(width.toFloat(), 1f), paint)
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(surface.readPixels(bitmap, 0, 0))
                val bytes = checkNotNull(bitmap.readPixels(info, width * 8))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(width * 4) { halfToFloat(buffer.short.toInt() and 0xffff) }
            }
        }
    }

    private fun fieldProbeMain(): String =
        """
        half4 main(float2 coord) {
          float4 field = interactionField(coord);
          return half4(field.rgb * field.a, field.a);
        }
        """
            .trimIndent()

    private fun halfToFloat(bits: Int): Float {
        val sign = if (bits and 0x8000 == 0) 1f else -1f
        val exponent = bits ushr 10 and 31
        val mantissa = bits and 1023
        return sign *
            when (exponent) {
                0 -> Math.scalb(mantissa.toFloat(), -24)
                31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Math.scalb(1f + mantissa / 1024f, exponent - 15)
            }
    }

    private data class TestLight(
        val x: Float,
        val y: Float,
        val radius: Float,
        val intensity: Float,
        val alpha: Float,
        val red: Float,
        val green: Float,
        val blue: Float,
        val curve: BackdropCurve,
    )
}
