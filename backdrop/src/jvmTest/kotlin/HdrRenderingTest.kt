package top.ltfan.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint as ComposePaint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.skiaImageFilter
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorFilter as SkiaColorFilter
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.effects.colorFilter
import top.ltfan.backdrop.effects.vibrancy
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.internal.LightingShaderString
import top.ltfan.backdrop.internal.SourceBoundaryShader
import top.ltfan.backdrop.internal.TintShaderString
import top.ltfan.backdrop.internal.colorUniformComponents
import top.ltfan.backdrop.internal.setHdrColor

class HdrRenderingTest : PlatformGraphicsTest() {
    @Test
    fun floatSurfacePreservesHdrBaseline() {
        assertPixel(floatArrayOf(2f, 2f, 2f, 1f), render(floatArrayOf(2f, 2f, 2f, 1f)) {})
    }

    @Test
    fun sourceBoundaryShaderPreservesExtendedPremultipliedChannels() {
        val shader = RuntimeShader(SourceBoundaryShader)
        shader.setFloatUniform("sourceBounds", 0f, 0f, 4f, 4f)
        shader.setFloatUniform("origin", -8f, -8f)
        shader.setIntUniform("tileMode", 0)
        val input = floatArrayOf(2f, 1.25f, -0.25f, 0.375f)
        val builder = shader.asSkikoRuntimeShader()
        builder.child("content", constantShader(input))
        assertPixel(input, renderShader(builder.makeShader()))
    }

    @Test
    fun vibrancyMatchesExistingMatrixInsideSdrRange() {
        val luminance = 0.213f * 0.4f + 0.715f * 0.3f + 0.072f * 0.2f
        assertPixel(
            floatArrayOf(
                luminance + (0.4f - luminance) * 1.5f,
                luminance + (0.3f - luminance) * 1.5f,
                luminance + (0.2f - luminance) * 1.5f,
                1f,
            ),
            render(floatArrayOf(0.4f, 0.3f, 0.2f, 1f)) { vibrancy() },
        )
    }

    @Test
    fun colorUniformConversionPreservesHdrAndOutOfGamutValues() {
        val hdr =
            colorUniformComponents(Color(4f, 4f, 4f, colorSpace = ColorSpaces.LinearExtendedSrgb))
        assertTrue(hdr.all { it > 1f })
        val p3 = colorUniformComponents(Color(0f, 1f, 0f, colorSpace = ColorSpaces.DisplayP3))
        assertTrue(p3[0] < 0f)
        assertTrue(p3[1] > 1f)
        assertTrue(p3[2] < 0f)
    }

    @Test
    fun colorUniformIsNotPremultipliedTwice() {
        val shader =
            RuntimeShader(
                """
            layout(color) uniform float4 color;
            float4 main(float2 coord) { return float4(color.rgb * color.a, color.a); }
        """,
            )
        shader.setColorUniform("color", Color(0.8f, 0.4f, 0.2f, 0.5f))
        assertPixel(
            floatArrayOf(0.4f, 0.2f, 0.1f, 0.5f),
            renderShader(shader.asSkikoRuntimeShader().makeShader()),
        )
    }

    @Test
    fun nativeSkikoImageFilterCurrentlyClampsHdrWithoutBackdrop() {
        val identity =
            RuntimeShaderBuilder(
                RuntimeEffect.makeForShader(
                    """
            uniform shader content;
            float4 main(float2 p) { return content.eval(p); }
        """,
                ),
            )
        val source = constantShader(floatArrayOf(2f, 2f, 2f, 1f))
        assertPixel(
            floatArrayOf(1f, 1f, 1f, 1f),
            renderShader(source) {
                imageFilter = ImageFilter.makeRuntimeShader(identity, "content", null)
            },
        )
    }

    @Test
    fun tintModesMatchNativeSkiaForSdrInputs() {
        val composeModes =
            listOf(
                BlendMode.Clear,
                BlendMode.Src,
                BlendMode.Dst,
                BlendMode.SrcOver,
                BlendMode.DstOver,
                BlendMode.SrcIn,
                BlendMode.DstIn,
                BlendMode.SrcOut,
                BlendMode.DstOut,
                BlendMode.SrcAtop,
                BlendMode.DstAtop,
                BlendMode.Xor,
                BlendMode.Plus,
                BlendMode.Modulate,
                BlendMode.Multiply,
                BlendMode.Screen,
            )
        val nativeModes =
            listOf(
                SkiaBlendMode.CLEAR,
                SkiaBlendMode.SRC,
                SkiaBlendMode.DST,
                SkiaBlendMode.SRC_OVER,
                SkiaBlendMode.DST_OVER,
                SkiaBlendMode.SRC_IN,
                SkiaBlendMode.DST_IN,
                SkiaBlendMode.SRC_OUT,
                SkiaBlendMode.DST_OUT,
                SkiaBlendMode.SRC_ATOP,
                SkiaBlendMode.DST_ATOP,
                SkiaBlendMode.XOR,
                SkiaBlendMode.PLUS,
                SkiaBlendMode.MODULATE,
                SkiaBlendMode.MULTIPLY,
                SkiaBlendMode.SCREEN,
            )
        val tint = Color(0.2f, 0.4f, 0.6f, 0.5f)
        val input = floatArrayOf(0.3f, 0.15f, 0.1f, 0.75f)
        composeModes.indices.forEach { i ->
            // Native Skia represents the identity Dst filter as null, which Compose's
            // constructor cannot wrap; only the constructible modes are tested.
            if (composeModes[i] == BlendMode.Dst) return@forEach
            val actual =
                renderCached(input, listOf("BackdropTint0" to TintShaderString)) {
                    colorFilter(ColorFilter.tint(tint, composeModes[i]))
                }
            SkiaColorFilter.makeBlend(tint.toArgb(), nativeModes[i]).use { filter ->
                val expected = renderShader(constantShader(input)) { colorFilter = filter }
                assertPixel(expected, actual)
            }
        }
    }

    @Test
    fun tintKeepsExtendedColorAndPremultipliesOnce() {
        val hdr = Color(2f, 2f, 2f, 0.5f, ColorSpaces.ExtendedSrgb)
        assertPixel(
            floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f),
            renderCached(
                floatArrayOf(0.25f, 0.25f, 0.25f, 0.5f),
                listOf("BackdropTint0" to TintShaderString),
            ) {
                colorFilter(ColorFilter.tint(hdr))
            },
        )
    }

    @Test
    fun successiveTintsKeepSeparateUniformsAndHdrPlus() {
        assertPixel(
            floatArrayOf(5f, 5f, 5f, 1f),
            renderCached(
                floatArrayOf(2f, 2f, 2f, 1f),
                listOf("BackdropTint0" to TintShaderString, "BackdropTint1" to TintShaderString),
            ) {
                colorFilter(ColorFilter.tint(Color(1f, 1f, 1f), BlendMode.Plus))
                colorFilter(
                    ColorFilter.tint(
                        Color(2f, 2f, 2f, colorSpace = ColorSpaces.ExtendedSrgb),
                        BlendMode.Plus,
                    ),
                )
            },
        )
    }

    @Test
    fun highlightDirectionUsesConfiguredCurvesAndVerticalDefault() {
        val direction = HighlightDirection()
        assertEquals(90f, direction.angle)
        assertEquals(0.35f, direction.strength.from)
        assertEquals(1f, direction.strength.to)
        assertEquals(0.6f, direction.widthScale.from)
        assertEquals(1f, direction.widthScale.to)
        assertEquals(450f, HighlightDirection(angle = 450f).angle)
    }

    @Test
    fun lightingPreservesHdrAndSourceAlpha() {
        assertPixel(
            floatArrayOf(1.5f, 1.5f, 1.5f, 0.5f),
            renderCached(
                floatArrayOf(1f, 1f, 1f, 0.5f),
                listOf("BackdropLighting0" to LightingShaderString),
            ) {
                colorFilter(ColorFilter.lighting(Color.White, Color.White))
            },
        )
    }

    @Test
    fun plainPaintKeepsExtendedRgbAndAlphaAtRendererBoundary() {
        val paint = ComposePaint()
        paint.setHdrColor(Color(2f, 2f, 2f, 0.5f, ColorSpaces.ExtendedSrgb))
        val native = paint.skiaPaint.color4f
        assertEquals(2f, native.r, 0.004f)
        assertEquals(2f, native.g, 0.004f)
        assertEquals(2f, native.b, 0.004f)
        assertEquals(0.5f, native.a, 0.004f)
    }

    private fun renderCached(
        input: FloatArray,
        shaders: List<Pair<String, String>>,
        effects: BackdropEffectScope.() -> Unit,
    ): FloatArray {
        val scope =
            object : BackdropEffectScopeImpl() {
                override val shape: Shape = RectangleShape
            }
        scope.size = Size(16f, 16f)
        scope.resolveEffects(effects)
        var shader = constantShader(input)
        for ((index, entry) in shaders.withIndex()) {
            val (key, source) = entry
            val builder =
                scope.obtainStageRuntimeShader(index, "$key#$index", source).asSkikoRuntimeShader()
            builder.child("content", shader)
            shader = builder.makeShader()
        }
        return renderShader(shader)
    }

    // Exercise the exact cached matrix shaders populated by colorFilter(), without
    // Skiko's independently clamped ImageFilter intermediate buffers.
    private fun constantShader(input: FloatArray): Shader =
        RuntimeShaderBuilder(
                RuntimeEffect.makeForShader(
                    """
            uniform float4 color;
            float4 main(float2 coord) { return color; }
        """,
                ),
            )
            .apply { uniform("color", input) }
            .makeShader()

    private fun render(input: FloatArray, effects: BackdropEffectScope.() -> Unit): FloatArray {
        val scope =
            object : BackdropEffectScopeImpl() {
                override val shape: Shape = RectangleShape
            }
        scope.size = Size(16f, 16f)
        scope.resolveEffects(effects)
        val shader =
            RuntimeShaderBuilder(
                    RuntimeEffect.makeForShader(
                        """
            uniform float4 color;
            float4 main(float2 coord) { return color; }
        """,
                    ),
                )
                .apply { uniform("color", input) }
                .makeShader()
        return renderShader(shader) {
            imageFilter = scope.passes.singleOrNull()?.effect?.skiaImageFilter
        }
    }

    private fun renderShader(
        shader: Shader,
        wholeSurface: Boolean = false,
        configure: SkiaPaint.() -> Unit = {},
    ): FloatArray {
        val info = ImageInfo(16, 16, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            SkiaPaint().use { paint ->
                paint.configure()
                surface.canvas.saveLayer(
                    Canvas.SaveLayerRec(
                        paint = paint,
                        saveLayerFlags =
                            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType),
                    ),
                )
                SkiaPaint().use { sourcePaint ->
                    sourcePaint.shader = shader
                    surface.canvas.drawRect(SkiaRect.makeWH(16f, 16f), sourcePaint)
                }
                surface.canvas.restore()
            }
            Bitmap().use { bitmap ->
                // RGBA_F16 retains alpha in native readback; the RGBA_F32 mapping uses
                // RGB_F16F16F16x.
                val readWidth = if (wholeSurface) 16 else 1
                val readHeight = if (wholeSurface) 16 else 1
                val readX = if (wholeSurface) 0 else 8
                val readY = if (wholeSurface) 0 else 8
                assertTrue(
                    bitmap.allocPixels(
                        ImageInfo(
                            readWidth,
                            readHeight,
                            ColorType.RGBA_F16,
                            ColorAlphaType.PREMUL,
                            ColorSpace.sRGB,
                        ),
                    ),
                )
                assertTrue(surface.readPixels(bitmap, readX, readY))
                val bytes =
                    requireNotNull(
                        bitmap.readPixels(
                            ImageInfo(
                                readWidth,
                                readHeight,
                                ColorType.RGBA_F16,
                                ColorAlphaType.PREMUL,
                                ColorSpace.sRGB,
                            ),
                            readWidth * 8,
                        ),
                    )
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(readWidth * readHeight * 4) {
                    halfToFloat(buffer.short.toInt() and 0xffff)
                }
            }
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

    private fun assertPixel(expected: FloatArray, actual: FloatArray, label: String = "") {
        expected.indices.forEach { index ->
            val message = if (label.isEmpty()) "Channel $index" else "$label channel $index"
            assertEquals(expected[index], actual[index], 0.004f, message)
        }
    }
}
