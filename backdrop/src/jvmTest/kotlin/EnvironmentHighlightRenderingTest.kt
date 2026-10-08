package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.highlight.EnvironmentHighlight
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.internal.environmentHighlightSamplingOutset
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class EnvironmentHighlightRenderingTest {
    @Test
    fun outsideSourceChangesEnvironmentRimWithoutRecompositionOrChangingStaticHighlight() =
        runComposeUiTest {
            val sourceColor = mutableStateOf(Color.Red)
            val environment =
                mutableStateOf<EnvironmentHighlight?>(
                    EnvironmentHighlight(
                        sampleDistance = 16.dp,
                        strength = 0.5f,
                        threshold = 0.01f,
                        blendMode = BlendMode.Plus,
                    )
                )
            val highlight =
                mutableStateOf<Highlight>(
                    Highlight(
                        width = 3.dp,
                        blurRadius = 0.dp,
                        style = HighlightStyle.Plain(Color.Green, BlendMode.SrcOver),
                        environment = environment.value,
                    )
                )
            var compositions = 0
            setContent {
                val rawBackdrop = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                sourceColor.value,
                                Offset(122.dp.toPx(), 72.dp.toPx()),
                                Size(22.dp.toPx(), 16.dp.toPx()),
                            )
                        }
                    }
                    SideEffect { compositions++ }
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                rawBackdrop,
                                { RectangleShape },
                                highlight = {
                                    (highlight.value as? Highlight.Config)?.copy(
                                        environment = environment.value
                                    ) ?: highlight.value
                                },
                                shadow = { Shadow.None },
                                innerShadow = { InnerShadow.None },
                            )
                    )
                }
            }
            waitForIdle()
            val initialCompositionCount = compositions
            val initial = onNodeWithTag("root").captureToImage().toPixelMap()
            val rimBefore = initial[118, 80]
            val centerBefore = initial[80, 80]

            sourceColor.value = Color.Blue
            waitForIdle()
            val updated = onNodeWithTag("root").captureToImage().toPixelMap()
            val rimAfter = updated[118, 80]
            val centerAfter = updated[80, 80]
            val otherEdge = updated[41, 80]

            assertTrue(rimBefore.red > rimBefore.blue + 0.15f, "red source should light the rim")
            assertTrue(rimAfter.blue > rimAfter.red + 0.15f, "blue source should update the rim")
            assertEquals(centerBefore.red, centerAfter.red, 0.02f)
            assertEquals(centerBefore.green, centerAfter.green, 0.02f)
            assertEquals(centerBefore.blue, centerAfter.blue, 0.02f)
            assertTrue(
                otherEdge.green > 0.5f,
                "static green highlight remains visible on another edge",
            )

            highlight.value =
                requireNotNull(highlight.value as? Highlight.Config)
                    .copy(
                        style = HighlightStyle.None,
                        environment = environment.value,
                    )
            waitForIdle()
            val environmentOnly = onNodeWithTag("root").captureToImage().toPixelMap()
            assertTrue(
                environmentOnly[41, 80].green < 0.05f,
                "HighlightStyle.None removes the static highlight",
            )
            assertTrue(
                environmentOnly[118, 80].blue > 0.15f,
                "HighlightStyle.None leaves environment emission active",
            )
            highlight.value =
                requireNotNull(highlight.value as? Highlight.Config)
                    .copy(
                        style = HighlightStyle.Plain(Color.Green, BlendMode.SrcOver),
                        environment = environment.value,
                    )

            environment.value = requireNotNull(environment.value).copy(threshold = 0.3f)
            waitForIdle()
            val filtered = onNodeWithTag("root").captureToImage().toPixelMap()[118, 80]
            assertTrue(
                filtered.blue < rimAfter.blue - 0.15f,
                "threshold updates the environment rim",
            )
            environment.value = requireNotNull(environment.value).copy(threshold = 0.01f)
            waitForIdle()
            val enabledAgain = onNodeWithTag("root").captureToImage().toPixelMap()[118, 80]
            environment.value = null
            waitForIdle()
            val disabled = onNodeWithTag("root").captureToImage().toPixelMap()[118, 80]
            assertTrue(
                disabled.blue < enabledAgain.blue - 0.15f,
                "null environment disables its light",
            )
            val staticOtherEdge = onNodeWithTag("root").captureToImage().toPixelMap()[41, 80]
            assertTrue(
                staticOtherEdge.green > 0.5f,
                "static highlight survives environment disable",
            )
            highlight.value = Highlight.None
            waitForIdle()
            val noneOtherEdge = onNodeWithTag("root").captureToImage().toPixelMap()[41, 80]
            assertTrue(noneOtherEdge.green < staticOtherEdge.green - 0.3f)
            highlight.value =
                Highlight(
                    width = 3.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Plain(Color.Green, BlendMode.SrcOver),
                )
            waitForIdle()
            val restoredOtherEdge = onNodeWithTag("root").captureToImage().toPixelMap()[41, 80]
            assertTrue(restoredOtherEdge.green > 0.5f)
            assertEquals(initialCompositionCount, compositions)
        }

    @Test
    fun transparentBrightSourceDoesNotEmitEnvironmentLight() = runComposeUiTest {
        val visibleSource = mutableStateOf(false)
        setContent {
            val rawBackdrop = rememberLayerBackdrop()
            Box(Modifier.size(160.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            Color.Red.copy(alpha = if (visibleSource.value) 1f else 0f),
                            Offset(122.dp.toPx(), 72.dp.toPx()),
                            Size(22.dp.toPx(), 16.dp.toPx()),
                        )
                    }
                }
                Box(
                    Modifier.offset(40.dp, 40.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            rawBackdrop,
                            { RectangleShape },
                            highlight = {
                                Highlight(
                                    width = 3.dp,
                                    blurRadius = 0.dp,
                                    style = HighlightStyle.Plain(Color.Black, BlendMode.SrcOver),
                                    environment =
                                        EnvironmentHighlight(
                                            sampleDistance = 16.dp,
                                            strength = 1f,
                                            threshold = 0f,
                                            blendMode = BlendMode.Plus,
                                        ),
                                )
                            },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        waitForIdle()
        val transparent = onNodeWithTag("root").captureToImage().toPixelMap()[118, 80]
        visibleSource.value = true
        waitForIdle()
        val opaque = onNodeWithTag("root").captureToImage().toPixelMap()[118, 80]

        assertTrue(opaque.red > transparent.red + 0.15f)
    }

    @Test
    fun directionalEnvironmentLightTracksAnAsymmetricRoundedEdge() = runComposeUiTest {
        setContent {
            val rawBackdrop = rememberLayerBackdrop()
            Box(Modifier.size(160.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            Color.Red,
                            Offset(98.dp.toPx(), 22.dp.toPx()),
                            Size(22.dp.toPx(), 8.dp.toPx()),
                        )
                    }
                }
                ProvideBackdropStyle(
                    highlight = {
                        Highlight(
                            width = 3.dp,
                            blurRadius = 0.dp,
                            style = HighlightStyle.Plain(Color.Black, BlendMode.SrcOver),
                            environment =
                                EnvironmentHighlight(
                                    sampleDistance = 16.dp,
                                    strength = 1f,
                                    threshold = 0f,
                                ),
                        )
                    },
                    shadow = { Shadow.None },
                    innerShadow = { InnerShadow.None },
                ) {
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(rawBackdrop, { RoundedCornerShape(topStart = 60.dp) })
                    )
                }
            }
        }
        waitForIdle()
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        assertTrue(pixels[110, 41].red > pixels[110, 119].red + 0.15f, "top edge faces the source")
        assertTrue(pixels[41, 110].red < pixels[110, 41].red, "left edge stays dim")
        assertTrue(pixels[119, 80].red < pixels[110, 41].red, "right edge stays dim")
        assertTrue(pixels[41, 41].alpha < 0.1f, "the rounded corner stays outside the surface")
    }

    @Test
    fun environmentLightFollowsTheCurvedOutwardNormalAtARoundedCorner() = runComposeUiTest {
        setContent {
            val rawBackdrop = rememberLayerBackdrop()
            Box(Modifier.size(160.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            Color.Red,
                            Offset(44.dp.toPx(), 44.dp.toPx()),
                            Size(12.dp.toPx(), 12.dp.toPx()),
                        )
                    }
                }
                Box(
                    Modifier.offset(40.dp, 40.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            rawBackdrop,
                            { RoundedCornerShape(topStart = 60.dp) },
                            highlight = {
                                Highlight(
                                    width = 3.dp,
                                    blurRadius = 0.dp,
                                    style = HighlightStyle.Plain(Color.Black, BlendMode.SrcOver),
                                    environment =
                                        EnvironmentHighlight(
                                            sampleDistance = 16.dp,
                                            strength = 1f,
                                            threshold = 0f,
                                        ),
                                )
                            },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        waitForIdle()
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        assertTrue(pixels[58, 58].red > 0.15f, "the arc facing the source receives light")
        assertTrue(pixels[65, 65].red < 0.05f, "the surface center stays unlit")
        assertTrue(pixels[110, 41].red < 0.05f, "the straight top edge faces no source")
        assertTrue(pixels[41, 41].alpha < 0.1f, "the outside corner remains clear")
    }

    @Test
    fun rawEnvironmentEmissionIsIndependentOfSurfaceBlurAndLensEffects() {
        fun capture(effectsEnabled: Boolean, environmentEnabled: Boolean): PixelMap {
            lateinit var pixels: PixelMap
            runComposeUiTest {
                val sourceColor = mutableStateOf(Color.Red)
                setContent {
                    val rawBackdrop = rememberLayerBackdrop()
                    Box(Modifier.size(220.dp).testTag("root")) {
                        Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color.Black)
                                drawRect(
                                    Color.White,
                                    Offset(74.dp.toPx(), 74.dp.toPx()),
                                    Size(12.dp.toPx(), 12.dp.toPx()),
                                )
                                drawRect(
                                    sourceColor.value,
                                    Offset(158.dp.toPx(), 72.dp.toPx()),
                                    Size(32.dp.toPx(), 16.dp.toPx()),
                                )
                            }
                        }
                        Box(
                            Modifier.offset(40.dp, 40.dp)
                                .size(80.dp)
                                .drawBackdrop(
                                    rawBackdrop,
                                    { RoundedCornerShape(0.dp) },
                                    effects = {
                                        if (effectsEnabled) {
                                            blur(8.dp.toPx())
                                            lens(8.dp.toPx(), 8.dp.toPx())
                                        }
                                    },
                                    highlight = {
                                        Highlight(
                                            width = 3.dp,
                                            blurRadius = 0.dp,
                                            style =
                                                HighlightStyle.Plain(
                                                    Color.Black,
                                                    BlendMode.SrcOver,
                                                ),
                                            environment =
                                                if (environmentEnabled)
                                                    EnvironmentHighlight(
                                                        sampleDistance = 60.dp,
                                                        strength = 1f,
                                                        threshold = 0f,
                                                    )
                                                else null,
                                        )
                                    },
                                    shadow = { Shadow.None },
                                    innerShadow = { InnerShadow.None },
                                )
                        )
                    }
                }
                waitForIdle()
                pixels = onNodeWithTag("root").captureToImage().toPixelMap()
            }
            return pixels
        }

        val plainBase = capture(effectsEnabled = false, environmentEnabled = false)
        val plainEnvironment = capture(effectsEnabled = false, environmentEnabled = true)
        val filteredBase = capture(effectsEnabled = true, environmentEnabled = false)
        val filteredEnvironment = capture(effectsEnabled = true, environmentEnabled = true)
        assertEquals(
            plainEnvironment[119, 80].red - plainBase[119, 80].red,
            filteredEnvironment[119, 80].red - filteredBase[119, 80].red,
            0.03f,
        )
        assertTrue(
            plainBase[80, 80].red > filteredBase[80, 80].red + 0.1f,
            "surface effects change its center",
        )
    }

    @Test
    fun environmentHighlightSupportsGenericAndEllipticalOutlines() {
        renderEnvironmentShape(GenericTriangleShape)
        renderEnvironmentShape(EllipticalCornerShape)
        renderEnvironmentShape(OversizedCornerShape)
    }

    private fun renderEnvironmentShape(shape: Shape) = runComposeUiTest {
        setContent {
            val rawBackdrop = rememberLayerBackdrop()
            Box(Modifier.size(120.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop))
                Box(
                    Modifier.size(80.dp)
                        .drawBackdrop(
                            rawBackdrop,
                            { shape },
                            highlight = {
                                Highlight(
                                    width = 3.dp,
                                    blurRadius = 0.dp,
                                    environment = EnvironmentHighlight(strength = 1f),
                                )
                            },
                        )
                )
            }
        }
        waitForIdle()
        onNodeWithTag("root").captureToImage()
    }
}

class EnvironmentHighlightSamplingTest {
    @Test
    fun samplingOutsetRoundsUpTheOutwardRayAndBilinearFootprint() {
        assertEquals(17f, environmentHighlightSamplingOutset(sampleDistance = 16f))
        assertEquals(18f, environmentHighlightSamplingOutset(sampleDistance = 17f))
        assertEquals(2f, environmentHighlightSamplingOutset(sampleDistance = 1f))
    }

    @Test
    fun samplingOutsetRequiresPositiveFiniteDistance() {
        assertFailsWith<IllegalArgumentException> {
            environmentHighlightSamplingOutset(sampleDistance = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            environmentHighlightSamplingOutset(sampleDistance = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            environmentHighlightSamplingOutset(sampleDistance = Float.POSITIVE_INFINITY)
        }
    }
}

private object GenericTriangleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Generic(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
        )
}

private object EllipticalCornerShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Rounded(
            RoundRect(
                0f,
                0f,
                size.width,
                size.height,
                CornerRadius(16f, 8f),
                CornerRadius(16f, 8f),
                CornerRadius(16f, 8f),
                CornerRadius(16f, 8f),
            )
        )
}

private object OversizedCornerShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Rounded(
            RoundRect(
                0f,
                0f,
                size.width,
                size.height,
                CornerRadius(50f),
                CornerRadius(50f),
                CornerRadius(50f),
                CornerRadius(50f),
            )
        )
}
