package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.colorFilter
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightColorTransform
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightEmission
import top.ltfan.backdrop.highlight.HighlightSample
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.toHighlightLinearExtendedSrgb
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

class ProcessedBackdropColorResponseTest {
    @Test
    fun responseTracksProcessedSourceAndDisabledState() = runComposeUiTest {
        val sourceColor = mutableStateOf(Color.Red)
        val response = mutableStateOf(HighlightColorResponse(.5f))
        val useTint = mutableStateOf(false)
        val useBlur = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val epoch = mutableIntStateOf(0)
        val surfaceSize = mutableStateOf(80)
        val backdropCallbackCount = AtomicInteger()
        val recordingStyle = RecordingStyle()
        setContent {
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                val source = rememberLayerBackdrop()
                val exported = rememberLayerBackdrop()
                val secondSource = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                        Box(Modifier.fillMaxSize().background(sourceColor.value))
                    }
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(surfaceSize.value.dp)
                            .drawBackdrop(
                                backdrop = source,
                                shape = { RectangleShape },
                                effects = {
                                    if (useTint.value) colorFilter(ColorFilter.tint(Color.Blue))
                                    if (useBlur.value) blur(2f)
                                },
                                highlight = {
                                    Highlight(
                                            width = 8.dp,
                                            direction = HighlightDirection.None,
                                            style = recordingStyle,
                                            colorResponse = response.value,
                                        )
                                        .takeIf { enabled.value } ?: Highlight.None
                                },
                                shadow = { Shadow.None },
                                innerShadow = { InnerShadow.None },
                                exportedBackdrop = exported,
                                onDrawBackdrop = { drawBackdrop, _ ->
                                    backdropCallbackCount.incrementAndGet()
                                    drawBackdrop()
                                },
                                onDrawSurface = { drawRect(Color.Green) },
                            ),
                    )
                    Box(
                        Modifier.offset(120.dp, 40.dp)
                            .size(40.dp, 80.dp)
                            .layerBackdrop(secondSource),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                    }
                    Box(
                        Modifier.offset(120.dp, 40.dp)
                            .size(40.dp, 80.dp)
                            .drawBackdrop(
                                backdrop = secondSource,
                                shape = { RectangleShape },
                                highlight = {
                                    Highlight(
                                        width = 8.dp,
                                        direction = HighlightDirection.None,
                                        style =
                                            HighlightStyle.Solid(Color.White.copy(alpha = .32f)),
                                        colorResponse = HighlightColorResponse(.5f),
                                    )
                                },
                                shadow = { Shadow.None },
                                innerShadow = { InnerShadow.None },
                            ),
                    )
                }
            }
        }

        waitForIdle()
        val captureRoot = { onNodeWithTag("root").captureToImage().toPixelMap() }
        val redTinted = captureRoot()
        val redRim = redTinted[80, 41]
        assertTrue(redRim.red > redRim.blue, "the red source tints the rim over the green surface")
        val independentBlueRim = redTinted[121, 80]
        assertTrue(
            independentBlueRim.blue > independentBlueRim.red,
            "a second consumer samples its own blue backdrop frame",
        )

        response.value = HighlightColorResponse(0f)
        waitForIdle()
        val untintedRed = captureRoot()[80, 41]
        assertTrue(
            redRim.blue < untintedRed.blue,
            "amount zero keeps the base highlight color instead of sampling the source",
        )

        backdropCallbackCount.set(0)
        response.value = HighlightColorResponse(.5f)
        waitForIdle()
        backdropCallbackCount.set(0)
        sourceColor.value = Color.Blue
        waitForIdle()
        val blueTinted = captureRoot()[80, 41]
        assertTrue(blueTinted.blue > blueTinted.red, "the next source frame tints the rim blue")
        assertEquals(
            1,
            backdropCallbackCount.get(),
            "normal and exported draws record once per frame",
        )

        enabled.value = false
        waitForIdle()
        val disabled = captureRoot()[80, 41]
        assertTrue(disabled.green > .9f, "Highlight.None removes the contour output")
        enabled.value = true

        sourceColor.value = Color.Red
        waitForIdle()
        val rawRedSource = captureRoot()[80, 41]
        useTint.value = true
        useBlur.value = true
        waitForIdle()
        val processedBlue = captureRoot()[80, 41]
        assertTrue(rawRedSource.red > rawRedSource.blue)
        assertTrue(
            processedBlue.blue > processedBlue.red,
            "the processed tint effect runs before backdrop sampling",
        )

        useTint.value = false
        useBlur.value = false
        response.value = HighlightColorResponse(.5f)
        sourceColor.value = Color.Red
        waitForIdle()
        val redBeforeEpoch = captureRoot()[80, 41]
        assertTrue(redBeforeEpoch.red > redBeforeEpoch.blue)
        val shaderBeforeEpoch = assertNotNull(recordingStyle.lastShader)

        mainClock.autoAdvance = false
        sourceColor.value = Color.Blue
        epoch.intValue++
        surfaceSize.value = 88
        mainClock.advanceTimeByFrame()
        val firstFrameAfterRebuild = captureRoot()[80, 41]
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(
            firstFrameAfterRebuild.blue > firstFrameAfterRebuild.red,
            "the processed frame is republished after an epoch and size change",
        )
        assertSame(
            shaderBeforeEpoch,
            recordingStyle.lastShader,
            "epoch rebuild reuses the compiled runtime shader",
        )

        val customTransform = DynamicBlueTransform()
        customTransform.blue = 0f
        response.value = HighlightColorResponse(1f, customTransform)
        waitForIdle()
        val beforeUniformChange = captureRoot()[80, 41]
        val shaderBeforeUniformChange = assertNotNull(recordingStyle.lastShader)
        customTransform.blue = .9f
        waitForIdle()
        val afterUniformChange = captureRoot()[80, 41]
        assertTrue(
            afterUniformChange.blue > beforeUniformChange.blue + .1f,
            "snapshot-backed transform uniforms update the rendered rim",
        )
        assertSame(
            shaderBeforeUniformChange,
            recordingStyle.lastShader,
            "uniform changes reuse the compiled runtime shader",
        )

        response.value = HighlightColorResponse(0f)
        waitForIdle()
        val beforeStyleSourceChange = assertNotNull(recordingStyle.lastShader)
        val beforeStyleSourcePixel = captureRoot()[80, 41]
        recordingStyle.alternate = true
        waitForIdle()
        val afterStyleSourcePixel = captureRoot()[80, 41]
        assertNotSame(
            beforeStyleSourceChange,
            recordingStyle.lastShader,
            "a changed custom shader definition rebuilds the runtime shader",
        )
        assertTrue(
            abs(afterStyleSourcePixel.red - beforeStyleSourcePixel.red) +
                abs(afterStyleSourcePixel.green - beforeStyleSourcePixel.green) +
                abs(afterStyleSourcePixel.blue - beforeStyleSourcePixel.blue) > 0.05f,
            "the new shader definition changes the rendered contour",
        )
    }
}

@Stable
private class RecordingStyle : HighlightStyle {
    private val delegate = HighlightStyle.Solid(Color.White.copy(alpha = .32f))
    private val configuredShaders = mutableListOf<RuntimeShader>()
    var alternate by mutableStateOf(false)

    override val color: Color
        get() = if (alternate) Color.Red.copy(alpha = .32f) else delegate.color

    override val shaderSource: String
        get() =
            if (alternate)
                "half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, half4 color) { return half4(color.r, 0.0, 0.0, color.a); } float highlightStrength(float2 position, float2 outwardNormal, float projection) { return 1.0; }"
            else delegate.shaderSource

    val lastShader: RuntimeShader?
        get() = configuredShaders.lastOrNull()

    override fun shade(sample: HighlightSample): HighlightEmission =
        if (alternate) HighlightEmission(color) else delegate.shade(sample)

    override fun configureShader(shader: RuntimeShader) {
        configuredShaders += shader
    }
}

@Stable
private class DynamicBlueTransform : HighlightColorTransform {
    var blue: Float by mutableStateOf(0f)

    override val shaderSource: String =
        "uniform float customTintBlue; float3 transformHighlightColor(float3 base, float3 sampled) { return float3(base.r, base.g, customTintBlue); }"

    override fun configureShader(shader: RuntimeShader) {
        shader.setFloatUniform("customTintBlue", blue)
    }

    override fun transform(baseColor: Color, sampleColor: Color): Color {
        val base = baseColor.toHighlightLinearExtendedSrgb()
        return Color(base.red, base.green, blue, base.alpha, ColorSpaces.LinearExtendedSrgb)
    }
}
