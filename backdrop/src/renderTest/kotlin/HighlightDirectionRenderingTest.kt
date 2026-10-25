package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightResponse
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.highlight

class HighlightDirectionRenderingTest {
    @Test
    fun exclusionMasksOnlyItsBoundaryAndUpdatesWithoutRecomposition() = runRenderTest {
        val exclusions = mutableStateOf(emptySet<ClosedFloatingPointRange<Float>>())
        var compositions = 0
        setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(160.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                SideEffect { compositions++ }
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
                                foreground {
                                    highlight(
                                        Highlight(
                                            width = 6.dp,
                                            style = HighlightStyle.Solid(Color.White),
                                            direction = HighlightDirection.Uniform,
                                            colorResponse = HighlightColorResponse(amount = 0f),
                                            disabledAngles = exclusions.value,
                                        ),
                                        colorSource = material,
                                    )
                                }
                            },
                        ),
                )
            }
        }
        waitForIdle()
        val before = onNodeWithTag("root").captureToImage().toPixelMap()
        val compositionCount = compositions

        exclusions.value = setOf(225f..315f, 135f..225f)
        waitForIdle()
        val after = onNodeWithTag("root").captureToImage().toPixelMap()

        assertTrue(before[80, 41].alpha > 0.05f, "top boundary is initially highlighted")
        assertTrue(
            after[80, 41].alpha < before[80, 41].alpha * 0.2f,
            "top exclusion suppresses its rim",
        )
        assertTrue(
            after[118, 80].alpha > 0.05f,
            "right boundary remains highlighted: before=${before[118, 80]}, after=${after[118, 80]}, row=${(115..121).map { after[it, 80].alpha }}",
        )
        assertTrue(
            after[41, 80].alpha < before[41, 80].alpha * 0.2f,
            "left exclusion suppresses its rim",
        )
        assertEquals(
            compositionCount,
            compositions,
            "snapshot-driven exclusions update drawing without recomposition",
        )
    }

    @Test
    fun directionalStrengthSelectsTheFacingBoundary() {
        val top =
            captureCorner(
                Highlight(
                    width = 6.dp,
                    style = HighlightStyle.Solid(Color.White),
                    direction =
                        HighlightDirection(
                            angle = 270f,
                            mode = HighlightDirection.Mode.Single,
                            strength = HighlightResponse(0f, 1f),
                        ),
                    colorResponse = HighlightColorResponse(amount = 0f),
                ),
            )
        assertTrue(top[80, 41].alpha > top[80, 118].alpha + 0.1f)
    }

    private fun captureCorner(config: Highlight): PixelMap {
        lateinit var pixels: PixelMap
        runRenderTest {
            setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(20.dp) },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)
                                    }
                                    foreground {
                                        highlight(config, colorSource = material)
                                    }
                                },
                            ),
                    )
                }
            }
            waitForIdle()
            pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        }
        return pixels
    }
}
