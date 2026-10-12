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
import kotlin.math.PI
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
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.effects.colorFilter
import top.ltfan.backdrop.effects.vibrancy
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightExclusion
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.shaderValues
import top.ltfan.backdrop.internal.LightingShaderString
import top.ltfan.backdrop.internal.SourceBoundaryShader
import top.ltfan.backdrop.internal.TintShaderString
import top.ltfan.backdrop.internal.colorUniformComponents
import top.ltfan.backdrop.internal.genericEnvironmentHighlightShader
import top.ltfan.backdrop.internal.highlightExclusionMaskShader
import top.ltfan.backdrop.internal.setHdrColor
import top.ltfan.backdrop.internal.staticHighlightShader

class HdrRenderingTest {
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
        """
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
        """
                )
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
                    )
                )
            },
        )
    }

    @Test
    fun environmentHighlightPreservesHdrAndSourceAlpha() {
        assertPixel(
            floatArrayOf(1.1763f, 1.1763f, 1.1763f, 0.5f),
            renderEnvironmentHighlight(floatArrayOf(4f, 2f, 1f, 1f)),
        )
        assertPixel(
            floatArrayOf(0.58815f, 0.58815f, 0.58815f, 0.25f),
            renderEnvironmentHighlight(floatArrayOf(2f, 1f, 0.5f, 0.5f)),
        )
        assertPixel(
            floatArrayOf(0f, 0f, 0f, 0f),
            renderEnvironmentHighlight(floatArrayOf(0f, 0f, 0f, 0f)),
        )
    }

    private fun renderEnvironmentHighlight(
        input: FloatArray,
        distance: Float = 0.5f,
        width: Float = 1f,
        thicknessWidth: Float = 0f,
        depthCurve: BackdropCurve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
        brightnessStrength: Float = 1f,
        tintStrength: Float = 0f,
        staticEnabled: Float = 1f,
    ): FloatArray {
        val runtime = RuntimeShader(genericEnvironmentHighlightShader(4))
        runtime.setFloatUniform("origin", -40f, 8.5f - distance)
        runtime.setFloatUniform("geometryScale", 100f)
        runtime.setFloatUniform(
            "segments",
            floatArrayOf(
                0f,
                0f,
                100f,
                0f,
                100f,
                0f,
                100f,
                100f,
                100f,
                100f,
                0f,
                100f,
                0f,
                100f,
                0f,
                0f,
            ),
        )
        runtime.setFloatUniform("width", width)
        runtime.setFloatUniform("staticEnabled", staticEnabled)
        runtime.setFloatUniform("angle", 0f)
        runtime.setFloatUniform("widthCurve", 0f, 0f, 1f, 1f)
        runtime.setFloatUniform("directional", 0f)
        runtime.setFloatUniform("sampleDistance", 3f)
        runtime.setFloatUniform("brightnessStrength", brightnessStrength)
        runtime.setFloatUniform("brightnessThreshold", 0.2f)
        runtime.setFloatUniform("tintStrength", tintStrength)
        runtime.setFloatUniform("tintThreshold", 0.2f)
        runtime.setFloatUniform("thicknessWidth", thicknessWidth)
        runtime.setFloatUniform("thicknessThreshold", 0.2f)
        runtime.setFloatUniform("depthCurve", depthCurve.shaderControls())
        val builder = runtime.asSkikoRuntimeShader()
        builder.child("content", constantShader(input))
        return renderShader(builder.makeShader())
    }

    @Test
    fun environmentBrightnessAndTintFadeMonotonicallyAcrossExtendedBands() {
        val curves =
            listOf(
                BackdropCurve.Linear,
                BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
                BackdropCurve.CubicBezier(0.5f, 0f, 0.8f, 0.3f),
            )
        data class Case(
            val thickness: Float,
            val brightness: Float,
            val tint: Float,
            val staticEnabled: Float,
        )
        val cases =
            listOf(
                Case(thickness = 0f, brightness = 1f, tint = 0f, staticEnabled = 1f),
                Case(thickness = 4f, brightness = 1f, tint = 0f, staticEnabled = 1f),
                Case(thickness = 4f, brightness = 1f, tint = 0f, staticEnabled = 0f),
                Case(thickness = 0f, brightness = 0f, tint = 1f, staticEnabled = 1f),
                Case(thickness = 4f, brightness = 0f, tint = 1f, staticEnabled = 1f),
                Case(thickness = 4f, brightness = 0f, tint = 1f, staticEnabled = 0f),
                Case(thickness = 0f, brightness = 0f, tint = 0f, staticEnabled = 1f),
                Case(thickness = 4f, brightness = 0f, tint = 0f, staticEnabled = 1f),
                Case(thickness = 0f, brightness = 0f, tint = 0f, staticEnabled = 0f),
                Case(thickness = 4f, brightness = 0f, tint = 0f, staticEnabled = 0f),
            )
        for (depthCurve in curves) {
            for (case in cases) {
                val baseWidth = if (case.staticEnabled > 0.5f) 4f else 0f
                val totalWidth = baseWidth + case.thickness
                val bandWidth = if (case.tint > 0f) minOf(totalWidth, 4f) else totalWidth
                val samples =
                    generateSequence(0.5f) { previous -> previous + 1f }
                        .takeWhile { it <= bandWidth + 0.5f }
                        .map { distance ->
                            renderEnvironmentHighlight(
                                input = floatArrayOf(4f, 2f, 1f, 1f),
                                distance = distance,
                                width = 4f,
                                thicknessWidth = case.thickness,
                                depthCurve = depthCurve,
                                brightnessStrength = case.brightness,
                                tintStrength = case.tint,
                                staticEnabled = case.staticEnabled,
                            )
                        }
                        .toList()
                if (case.brightness == 0f && case.tint == 0f) {
                    samples.forEach { pixel ->
                        assertPixel(
                            floatArrayOf(0f, 0f, 0f, 0f),
                            pixel,
                            "thickness with zero strengths (curve=$depthCurve)",
                        )
                    }
                    continue
                }
                val sourceBrightness = 4f * 0.2126f + 2f * 0.7152f + 0.0722f
                val outputPerProfile = if (case.brightness > 0f) sourceBrightness else 4f
                samples.forEachIndexed { index, pixel ->
                    val distance = 0.5f + index
                    val expectedProfile = highlightBandProfile(distance, bandWidth, depthCurve)
                    val expected =
                        if (case.brightness > 0f) {
                            floatArrayOf(
                                expectedProfile * outputPerProfile,
                                expectedProfile * outputPerProfile,
                                expectedProfile * outputPerProfile,
                                expectedProfile,
                            )
                        } else {
                            floatArrayOf(
                                expectedProfile * 4f,
                                expectedProfile * 2f,
                                expectedProfile,
                                expectedProfile,
                            )
                        }
                    assertPixel(expected, pixel, "curve=$depthCurve distance=$distance case=$case")
                }
                if (case.brightness > 0f || case.tint > 0f) {
                    assertTrue(samples.first()[0] > 1f, "F16 output preserves HDR")
                }
            }
        }
    }

    @Test
    fun highlightDirectionUsesConfiguredCurvesAndVerticalDefault() {
        val direction = HighlightDirection()
        assertEquals(90f, direction.angle)
        assertEquals(0.125f, direction.widthCurve.transform(0.5f), 0.0001f)
        assertEquals(0.5f, direction.strengthCurve.transform(0.5f))
        assertEquals(450f, HighlightDirection(angle = 450f).angle)
    }

    @Test
    fun staticAmbientShaderPreservesHdrStrengthAlphaAndSignedDirection() {
        val color = Color(2f, 2f, 2f, 0.2f, ColorSpaces.ExtendedSrgb)
        val top = renderStaticHighlight(color, 270f, ambient = true)
        assertPixel(floatArrayOf(0.2f, 0.2f, 0.2f, 0.1f), top)
        assertPixel(
            floatArrayOf(0f, 0f, 0f, 0.1f),
            renderStaticHighlight(color, 90f, ambient = true),
        )
        assertPixel(
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.1f),
            renderStaticHighlight(color, 270f, ambient = false),
        )
    }

    @Test
    fun exclusionMaskUsesLocalBoundaryNormals() {
        val shader = RuntimeShader(highlightExclusionMaskShader(4, 1))
        shader.setFloatUniform("geometryScale", 100f)
        shader.setFloatUniform(
            "segments",
            floatArrayOf(
                0f,
                0f,
                100f,
                0f,
                100f,
                0f,
                100f,
                100f,
                100f,
                100f,
                0f,
                100f,
                0f,
                100f,
                0f,
                0f,
            ),
        )
        shader.setFloatUniform("exclusions", setOf(HighlightExclusion.Top).shaderValues())
        val builder = shader.asSkikoRuntimeShader()
        builder.child("content", constantShader(floatArrayOf(1f, 1f, 1f, 1f)))
        shader.setFloatUniform("origin", -40f, 8f)
        assertPixel(floatArrayOf(0f, 0f, 0f, 0f), renderShader(builder.makeShader()))
        shader.setFloatUniform("origin", -40f, -91f)
        assertPixel(floatArrayOf(1f, 1f, 1f, 1f), renderShader(builder.makeShader()))
    }

    private fun renderStaticHighlight(color: Color, angle: Float, ambient: Boolean): FloatArray {
        val style = if (ambient) HighlightStyle.Ambient(color) else HighlightStyle.Default(color)
        val runtime = RuntimeShader(staticHighlightShader(4, style))
        runtime.setFloatUniform("origin", -40f, 8f)
        runtime.setFloatUniform("geometryScale", 100f)
        runtime.setFloatUniform(
            "segments",
            floatArrayOf(
                0f,
                0f,
                100f,
                0f,
                100f,
                0f,
                100f,
                100f,
                100f,
                100f,
                0f,
                100f,
                0f,
                100f,
                0f,
                0f,
            ),
        )
        runtime.setFloatUniform("width", 1f)
        runtime.setFloatUniform("angle", (angle * (PI / 180f)).toFloat())
        runtime.setFloatUniform("widthCurve", 0f, 0f, 1f, 1f)
        runtime.setFloatUniform("strengthCurve", 0f, 0f, 1f, 1f)
        runtime.setFloatUniform("depthCurve", 1f / 3f, 0f, 2f / 3f, 1f)
        runtime.setFloatUniform("directional", 1f)
        runtime.setColorUniform("color", color)
        return renderShader(runtime.asSkikoRuntimeShader().makeShader())
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
            val builder = scope.obtainRuntimeShader("$key#$index", source).asSkikoRuntimeShader()
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
        """
                )
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
        """
                    )
                )
                .apply { uniform("color", input) }
                .makeShader()
        return renderShader(shader) { imageFilter = scope.renderEffect?.skiaImageFilter }
    }

    private fun renderShader(shader: Shader, configure: SkiaPaint.() -> Unit = {}): FloatArray {
        val info = ImageInfo(16, 16, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            SkiaPaint().use { paint ->
                paint.configure()
                surface.canvas.saveLayer(
                    Canvas.SaveLayerRec(
                        paint = paint,
                        saveLayerFlags =
                            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType),
                    )
                )
                SkiaPaint().use { sourcePaint ->
                    sourcePaint.shader = shader
                    surface.canvas.drawRect(Rect.makeWH(16f, 16f), sourcePaint)
                }
                surface.canvas.restore()
            }
            Bitmap().use { bitmap ->
                // Skiko's RGBA_F32 ordinal currently maps to native RGB_F16F16F16x; recheck when
                // Skiko is updated.
                // Read the correctly mapped RGBA_F16 format, including alpha.
                assertTrue(
                    bitmap.allocPixels(
                        ImageInfo(
                            1,
                            1,
                            ColorType.RGBA_F16,
                            ColorAlphaType.PREMUL,
                            ColorSpace.sRGB,
                        )
                    )
                )
                assertTrue(surface.readPixels(bitmap, 8, 8))
                val bytes =
                    requireNotNull(
                        bitmap.readPixels(
                            ImageInfo(
                                1,
                                1,
                                ColorType.RGBA_F16,
                                ColorAlphaType.PREMUL,
                                ColorSpace.sRGB,
                            ),
                            8,
                        )
                    )
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(4) { halfToFloat(buffer.short.toInt() and 0xffff) }
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
