package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.colorControls
import top.ltfan.backdrop.effects.layeredProgressiveBlur
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.effects.progressiveBlur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

class StyledSurfaceRenderingTest {
    @Test
    fun fixedPipelineMatchesNativeCompositionAcrossBlurAlgorithms() = runRenderTest {
        val algorithm = mutableStateOf<BackdropBlur>(BackdropBlur.Uniform(3.dp))
        val contour =
            Highlight(
                width = 4.dp,
                direction = HighlightDirection.Uniform,
                colorResponse = HighlightColorResponse(.7f),
            )
        val inner = InnerShadow(radius = 3.dp, color = Color.Blue.copy(alpha = .4f))
        val outer = Shadow(radius = 2.dp)
        val shape = RoundedCornerShape(12.dp)
        val style = BackdropStyle.NoEffects {
            colorControls = { BackdropColorControls(brightness = .08f, contrast = .8f) }
            blur = { algorithm.value }
            vibrancy = { 1.5f }
            lens = { BackdropLens(5.dp, 7.dp, chromaticAberration = true) }
            fill = { BackdropFill(Color.Green, alpha = .35f) }
            highlight = { contour }
            innerShadow = { inner }
            shadow = { outer }
        }
        setContent {
            val source = rememberCanvasBackdrop {
                drawRect(Color.Red)
                drawRect(Color.Blue, Offset(40f, 0f), Size(40f, 80f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(
                    Modifier.size(80.dp).testTag("styled").drawBackdrop(source, { shape }) {
                        styledSurface(style)
                    },
                ) {
                    Box(Modifier.offset(25.dp, 25.dp).size(30.dp).background(Color.Magenta))
                }
                Box(
                    Modifier.size(80.dp).testTag("native").drawBackdrop(source, { shape }) {
                        val material = image {
                            source()
                            effects { colorControls(brightness = .08f, contrast = .8f) }
                            when (val value = algorithm.value) {
                                BackdropBlur.None -> Unit
                                is BackdropBlur.Uniform ->
                                    effects { blur(value.radius.toPx(), value.edgeTreatment) }
                                is BackdropBlur.Progressive ->
                                    effects {
                                        progressiveBlur(value.radius.toPx(), value.edge, value.ramp)
                                    }
                                is BackdropBlur.LayeredProgressive ->
                                    layeredProgressiveBlur(
                                        with(density) { value.radius.toPx() },
                                        value.edge,
                                        value.ramp,
                                        value.levels,
                                    )
                            }
                            effects {
                                colorControls(saturation = 1.5f)
                                lens(5.dp.toPx(), 7.dp.toPx(), chromaticAberration = true)
                            }
                            clipSurface()
                        }
                        group {
                            shadow(outer)
                            draw(material)
                            fill(Color.Green.copy(alpha = .35f))
                        }
                        foreground {
                            highlight(contour, colorSource = material)
                            innerShadow(inner)
                        }
                    },
                ) {
                    Box(Modifier.offset(25.dp, 25.dp).size(30.dp).background(Color.Magenta))
                }
            }
        }
        for (value in
            listOf(
                BackdropBlur.None,
                BackdropBlur.Uniform(3.dp),
                BackdropBlur.Progressive(3.dp, BackdropEdge.Top),
                BackdropBlur.LayeredProgressive(3.dp, BackdropEdge.Top, levels = 4),
                BackdropBlur.LayeredProgressive(0.dp, BackdropEdge.Top),
            )) {
            runOnIdle { algorithm.value = value }
            val actual = onNodeWithTag("styled").captureToImage().toPixelMap()
            val expected = onNodeWithTag("native").captureToImage().toPixelMap()
            for (y in 0 until 80) for (x in 0 until 80) {
                val a = actual[x, y]
                val b = expected[x, y]
                assertTrue(
                    abs(a.red - b.red) < .025f &&
                        abs(a.green - b.green) < .025f &&
                        abs(a.blue - b.blue) < .025f &&
                        abs(a.alpha - b.alpha) < .025f,
                    "$value: fixed composition differs at ($x,$y): $a versus $b",
                )
            }
            assertTrue(
                actual[40, 40].red > .98f && actual[40, 40].blue > .98f,
                "Children draw above the complete background",
            )
            assertTrue(actual[0, 0].alpha < .02f, "Processed source respects rounded clipping")
        }
    }

    @Test
    fun backgroundAlphaAndExportExcludeAppendedForegroundAndChildren() = runRenderTest {
        val alpha = mutableFloatStateOf(1f)
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red) }
            val exported = rememberLayerBackdrop()
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(
                    Modifier.size(40.dp).testTag("local").drawBackdrop(
                        source,
                        shape = { RoundedCornerShape(0.dp) },
                        backgroundAlpha = { alpha.floatValue },
                        exportedBackdrop = exported,
                    ) {
                        styledSurface(BackdropStyle.NoEffects)
                        appendForeground { fill(Color.Green) }
                        appendForeground { group {} }
                        export()
                    },
                ) {
                    Box(Modifier.offset(10.dp, 10.dp).size(20.dp).background(Color.Blue))
                }
                Box(
                    Modifier.size(40.dp)
                        .testTag("export")
                        .drawBackdrop(exported, { RoundedCornerShape(0.dp) }),
                )
            }
        }
        for (value in listOf(1f, .5f, 0f, .25f, 1f)) {
            runOnIdle { alpha.floatValue = value }
            val local = onNodeWithTag("local").captureToImage().toPixelMap()[20, 20]
            val output = onNodeWithTag("export").captureToImage().toPixelMap()[20, 20]
            assertTrue(
                local.green > .98f && local.alpha > .98f,
                "Appended foreground remains above children and independent of background alpha",
            )
            assertTrue(
                abs(output.alpha - value) < .025f,
                "Export applies background alpha exactly once",
            )
            if (value > 0f)
                assertTrue(
                    output.red > .98f && output.green < .02f && output.blue < .02f,
                    "Export excludes children and foreground",
                )
        }
    }

    @Test
    fun progressiveFillHasIndependentCoverageAndDoesNotEnableBlur() = runRenderTest {
        val ramp = BackdropRamp(anchors = { -10f..50f })
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(Modifier.size(80.dp).testTag("root")) {
                Box(
                    Modifier.offset(20.dp, 20.dp).size(40.dp).drawBackdrop(
                        source,
                        { RoundedCornerShape(0.dp) },
                    ) {
                        styledSurface(
                            BackdropStyle.NoEffects {
                                fill = {
                                    BackdropFill(Color.Red, ramp = ramp, edge = BackdropEdge.Top)
                                }
                            },
                        )
                    },
                )
            }
        }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        assertTrue(
            pixels[40, 15].alpha > .01f,
            "Fill ramp requests its own coverage outside the surface",
        )
        assertTrue(pixels[40, 40].alpha > .01f, "The fill remains present inside the surface")
        assertTrue(pixels[5, 5].alpha < .01f, "Fill coverage stays finite")
    }
}
