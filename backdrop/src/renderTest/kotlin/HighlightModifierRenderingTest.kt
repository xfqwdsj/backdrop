package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightAngleDomain
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.highlight.highlightAngles
import top.ltfan.backdrop.highlight.highlightWithConfig

class HighlightModifierRenderingTest {
    @Test
    fun basicModeDrawsUniformStrokeAndDetachesItsEnvironment() = runRenderTest {
        val basicConfig = Highlight.Basic(baseBrightness = .5f, opacity = .5f)
        val contourConfig = Highlight(blurRadius = 4.dp, disabledAngles = setOf(0f..360f))
        val selected = mutableStateOf<Highlight>(basicConfig)
        val sourceColor = mutableStateOf(Color.Red)
        var sourceDraws = 0
        setContent {
            val source = rememberCanvasBackdrop {
                sourceDraws++
                drawRect(sourceColor.value)
            }
            Box(
                Modifier.size(64.dp).testTag("highlight-mode").drawBackdrop(
                    source,
                    { RectangleShape },
                ) {
                    val environment = image { source() }
                    replace(image {})
                    highlightWithConfig({ selected.value }, environment, 1f, BlendMode.SrcOver)
                },
            )
        }

        fun capture() = onNodeWithTag("highlight-mode").captureToImage().toPixelMap()

        val basic = capture()
        val edge = basic[0, 32]
        assertTrue(edge.alpha in 0.24f..0.26f, "Expected uniform stroke opacity, got ${edge.alpha}")
        assertEquals(1f, edge.red)
        assertEquals(1f, edge.green)
        assertEquals(1f, edge.blue)
        assertEquals(0f, basic[1, 32].alpha)
        assertEquals(edge, basic[32, 0])
        assertEquals(0f, basic[32, 32].alpha)
        runOnIdle {
            assertEquals(0, sourceDraws)
            selected.value = contourConfig
        }

        assertEquals(0f, capture()[0, 32].alpha)
        val drawsAfterContour = runOnIdle {
            assertTrue(sourceDraws > 0)
            selected.value = basicConfig
            sourceDraws
        }

        assertEquals(edge, capture()[0, 32])
        runOnIdle { sourceColor.value = Color.Blue }
        assertEquals(edge, capture()[0, 32])
        runOnIdle { assertEquals(drawsAfterContour, sourceDraws) }
    }

    @Test
    fun uniformKeepsAllSidesWhileDefaultDoubleIncidenceVaries() {
        val plain =
            capture(
                Highlight(
                    width = 8.dp,
                    direction = HighlightDirection.Uniform,
                    baseBrightness = 1f,
                    environmentGain = 0f,
                ),
            )
        val preset =
            capture(
                Highlight.Default.copy(
                    width = 8.dp,
                    baseBrightness = 1f,
                    environmentGain = 0f,
                ),
            )

        assertTrue(plain[80, 41].alpha > 0.05f && plain[41, 80].alpha > 0.05f)
        assertTrue(
            preset[80, 41].alpha > preset[41, 80].alpha + 0.05f,
            "the default vertical incidence keeps top stronger than the side",
        )
    }

    @Test
    fun singleIncidenceSelectsTheFacingSide() {
        val top =
            capture(
                Highlight(
                    width = 6.dp,
                    direction =
                        HighlightDirection(
                            angle = 270f,
                            mode = HighlightDirection.Mode.Single,
                        ),
                    baseBrightness = 1f,
                    environmentGain = 0f,
                ),
            )
        val bottom =
            capture(
                Highlight(
                    width = 6.dp,
                    direction =
                        HighlightDirection(
                            angle = 90f,
                            mode = HighlightDirection.Mode.Single,
                        ),
                    baseBrightness = 1f,
                    environmentGain = 0f,
                ),
            )

        assertTrue(
            top[80, 41].alpha > top[80, 118].alpha + 0.5f,
            "top=${top[80, 41]}, opposite=${top[80, 118]}",
        )
        assertTrue(
            bottom[80, 118].alpha > bottom[80, 41].alpha + 0.5f,
            "bottom=${bottom[80, 118]}, opposite=${bottom[80, 41]}",
        )
    }

    @Test
    fun gpuProfileRetainsEmissionAcrossTheHalfPixelOuterFootprint() {
        val pixels =
            capture(
                Highlight(
                    width = 8.25.dp,
                    direction = HighlightDirection.Uniform,
                    baseBrightness = 1f,
                    environmentGain = 0f,
                ),
            )

        assertTrue(pixels[80, 42].alpha > 0f, "inner edge pixel=${pixels[80, 42]}")
    }

    @Test
    fun drawingParameterAnimationRetainsSourceAndFilteredEnvironment() = runRenderTest {
        val brightness = mutableFloatStateOf(.1f)
        val bandWidth = mutableFloatStateOf(10f)
        val highlightDirection = mutableStateOf<HighlightDirection>(HighlightDirection.Uniform)
        var sourceCalls = 0
        var materialCalls = 0
        var compositions = 0
        setContent {
            val source = rememberCanvasBackdrop {
                sourceCalls++
                drawRect(Color.Blue)
            }
            SideEffect { compositions++ }
            Box(Modifier.size(120.dp).testTag("retained-highlight")) {
                Box(
                    Modifier.offset(20.dp, 20.dp).size(80.dp).drawBackdrop(
                        source,
                        { RectangleShape },
                    ) {
                        val environment = image {
                            source()
                            paint {
                                materialCalls++
                                drawRect(Color.Red.copy(alpha = .5f))
                            }
                        }
                        val material = image {
                            replace(environment)
                            clipSurface()
                        }
                        group {
                            draw(material)
                            highlight(environment = environment) {
                                width = bandWidth.floatValue.dp
                                blurRadius = 8.dp
                                baseBrightness = brightness.floatValue
                                environmentGain = .5f
                                direction = highlightDirection.value
                            }
                        }
                    },
                )
            }
        }
        waitForIdle()
        val initial = onNodeWithTag("retained-highlight").captureToImage().toPixelMap()
        val initialSourceCalls = sourceCalls
        val initialMaterialCalls = materialCalls
        val initialCompositions = compositions
        assertTrue(initialSourceCalls > 0)
        assertTrue(initialMaterialCalls > 0)
        assertTrue(initial[60, 32].green < .02f)

        for (value in listOf(.3f, .7f, .2f, .9f)) {
            runOnIdle {
                brightness.floatValue = value
                bandWidth.floatValue = 10f + value * 10f
            }
            waitForIdle()
            onNodeWithTag("retained-highlight").captureToImage()
            assertEquals(initialSourceCalls, sourceCalls, "brightness=$value")
            assertEquals(initialMaterialCalls, materialCalls, "brightness=$value")
        }
        val wide = onNodeWithTag("retained-highlight").captureToImage().toPixelMap()
        assertTrue(wide[60, 32].green > .05f)

        runOnIdle {
            highlightDirection.value =
                HighlightDirection.Angular(
                    highlightAngles(HighlightAngleDomain.Directed) {
                        widthScale(180f, 270f, 2f)
                        brightness(0f, 360f, 1f)
                    },
                )
        }
        waitForIdle()
        val top = onNodeWithTag("retained-highlight").captureToImage().toPixelMap()
        assertTrue(top[60, 32].green > .05f, "top angular response=${top[60, 32]}")
        assertTrue(top[60, 88].green < .02f, "bottom angular response=${top[60, 88]}")

        runOnIdle {
            highlightDirection.value =
                HighlightDirection.Angular(
                    highlightAngles(HighlightAngleDomain.Directed) {
                        widthScale(0f, 90f, 2f)
                        brightness(0f, 360f, 1f)
                    },
                )
        }
        waitForIdle()
        val bottom = onNodeWithTag("retained-highlight").captureToImage().toPixelMap()
        assertTrue(bottom[60, 88].green > .05f, "rotated angular response=${bottom[60, 88]}")

        assertEquals(initialSourceCalls, sourceCalls)
        assertEquals(initialMaterialCalls, materialCalls)
        assertEquals(initialCompositions, compositions)
    }

    @Test
    fun environmentGaussianSamplesExteriorColorAtTheInnerBand() = runRenderTest {
        val enabled = mutableStateOf(false)
        val blurRadius = mutableFloatStateOf(4f)
        setContent {
            val source = rememberLayerBackdrop()
            Box(Modifier.size(120.dp).testTag("environment-highlight")) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(source)) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, Offset(20f, 20f), Size(80f, 80f))
                }
                Box(
                    Modifier.offset(20.dp, 20.dp).size(80.dp).drawBackdrop(
                        source,
                        { RectangleShape },
                    ) {
                        val environment = image { source() }
                        val material = image {
                            replace(environment)
                            clipSurface()
                        }
                        group {
                            draw(material)
                            highlight(
                                Highlight(
                                    width = 12.dp,
                                    blurRadius = blurRadius.floatValue.dp,
                                    baseBrightness = 0f,
                                    environmentGain = if (enabled.value) 1f else 0f,
                                    direction = HighlightDirection.Uniform,
                                ),
                                environment = environment,
                            )
                        }
                    },
                )
            }
        }
        waitForIdle()
        val disabled = onNodeWithTag("environment-highlight").captureToImage().toPixelMap()
        runOnIdle { enabled.value = true }
        waitForIdle()
        val narrow = onNodeWithTag("environment-highlight").captureToImage().toPixelMap()
        runOnIdle { blurRadius.floatValue = 12f }
        waitForIdle()
        val active = onNodeWithTag("environment-highlight").captureToImage().toPixelMap()
        assertTrue(
            active[21, 60].red > disabled[21, 60].red + .05f,
            "exterior red must enter the blurred environment: " +
                "x21=${active[21, 60]}/${disabled[21, 60]}, " +
                "x25=${active[25, 60]}/${disabled[25, 60]}, " +
                "x30=${active[30, 60]}/${disabled[30, 60]}",
        )
        assertTrue(
            active[27, 60].red > narrow[27, 60].red + .01f,
            "larger blur radius must widen exterior sampling: " +
                "wide=${(24..29).map { active[it, 60] }}, " +
                "narrow=${(24..29).map { narrow[it, 60] }}",
        )
    }

    @Test
    fun environmentBlurKeepsEachFiniteSourceTilePolicy() {
        val clamped = captureFiniteEnvironment(TileMode.Clamp)
        val decal = captureFiniteEnvironment(TileMode.Decal)

        assertTrue(
            clamped[41, 30].red > .1f,
            "clamped exterior must reach the inner band: ${clamped[41, 30]}",
        )
        assertTrue(
            decal[41, 30].red < .02f,
            "decal exterior must remain transparent: ${decal[41, 30]}",
        )
    }

    private fun captureFiniteEnvironment(tileMode: TileMode): PixelMap {
        lateinit var pixels: PixelMap
        runRenderTest {
            setContent {
                val source = rememberLayerBackdrop(tileMode = tileMode)
                Box(Modifier.size(width = 140.dp, height = 60.dp).testTag("finite-environment")) {
                    Canvas(Modifier.size(width = 20.dp, height = 60.dp).layerBackdrop(source)) {
                        drawRect(Color.Red)
                    }
                    Box(
                        Modifier.offset(40.dp, 10.dp)
                            .size(width = 50.dp, height = 40.dp)
                            .drawBackdrop(source, { RectangleShape }) {
                                val environment = image { source() }
                                val material = image {
                                    paint { drawRect(Color.Black) }
                                    clipSurface()
                                }
                                group {
                                    draw(material)
                                    highlight(
                                        Highlight(
                                            width = 8.dp,
                                            blurRadius = 8.dp,
                                            baseBrightness = 0f,
                                            environmentGain = 1f,
                                            direction = HighlightDirection.Uniform,
                                        ),
                                        environment = environment,
                                    )
                                }
                            },
                    )
                }
            }
            waitForIdle()
            pixels = onNodeWithTag("finite-environment").captureToImage().toPixelMap()
        }
        return pixels
    }

    private fun capture(highlight: Highlight): PixelMap {
        lateinit var result: PixelMap
        runRenderTest {
            val selected = mutableStateOf(highlight)
            setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)
                                    }
                                    highlight(selected.value)
                                },
                            ),
                    )
                }
            }
            waitForIdle()
            result = onNodeWithTag("root").captureToImage().toPixelMap()
        }
        return result
    }
}
