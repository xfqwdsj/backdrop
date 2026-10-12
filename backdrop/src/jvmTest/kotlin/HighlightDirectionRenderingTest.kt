package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightExclusion
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class HighlightDirectionRenderingTest {
    @Test
    fun exclusionMasksOnlyItsBoundaryAndUpdatesWithoutRecomposition() = runComposeUiTest {
        val exclusions = mutableStateOf(emptySet<HighlightExclusion>())
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
                            backdrop,
                            { RectangleShape },
                            highlight = {
                                Highlight(
                                    width = 6.dp,
                                    blurRadius = 4.dp,
                                    style = HighlightStyle.Plain(Color.White, BlendMode.Plus),
                                    exclusions = exclusions.value,
                                )
                            },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        waitForIdle()
        val before = onNodeWithTag("root").captureToImage().toPixelMap()
        val compositionCount = compositions

        exclusions.value = setOf(HighlightExclusion.Top, HighlightExclusion.Left)
        waitForIdle()
        val after = onNodeWithTag("root").captureToImage().toPixelMap()

        assertTrue(before[80, 41].alpha > 0.05f, "top boundary is initially highlighted")
        assertTrue(
            after[80, 41].alpha < before[80, 41].alpha * 0.2f,
            "top exclusion suppresses its rim",
        )
        assertTrue(after[118, 80].alpha > 0.05f, "right boundary remains highlighted")
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
    fun widthCurveChangesIntegratedDiagonalCornerBand() {
        val linear =
            captureCorner(
                Highlight(
                    width = 6.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Default,
                    direction = HighlightDirection(angle = 45f, widthCurve = BackdropCurve.Linear),
                )
            )
        val cubic =
            captureCorner(
                Highlight(
                    width = 6.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Default,
                    direction =
                        HighlightDirection(
                            angle = 45f,
                            widthCurve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 0f),
                        ),
                )
            )
        val linearEnergy = cornerEnergy(linear)
        val cubicEnergy = cornerEnergy(cubic)
        assertTrue(
            linearEnergy > cubicEnergy * 1.1f,
            "linear width curve should emit a wider diagonal corner band: linear=$linearEnergy cubic=$cubicEnergy",
        )
    }

    private fun captureCorner(highlight: Highlight): PixelMap {
        lateinit var pixels: PixelMap
        runComposeUiTest {
            setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop,
                                { RoundedCornerShape(20.dp) },
                                highlight = { highlight },
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

    private fun cornerEnergy(pixels: PixelMap): Float {
        var energy = 0f
        for (y in 40 until 62) for (x in 40 until 62) {
            energy += pixels[x, y].alpha
        }
        return energy
    }
}
