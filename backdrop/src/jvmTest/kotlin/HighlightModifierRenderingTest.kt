package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class HighlightModifierRenderingTest {
    @Test
    fun plainIgnoresDirectionAndDepthCurveStillFadesInward() {
        val linearDepth =
            capture(
                Highlight(
                    width = 8.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Plain(Color.White, BlendMode.Plus),
                    direction = HighlightDirection(angle = 0f),
                    depthCurve = BackdropCurve.Linear,
                )
            )
        val cubicDepth =
            capture(
                Highlight(
                    width = 8.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Plain(Color.White, BlendMode.Plus),
                    direction = HighlightDirection(angle = 270f),
                    depthCurve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 0f),
                )
            )
        val inwardLinear = linearDepth[44, 80].alpha
        val inwardCubic = cubicDepth[44, 80].alpha

        assertTrue(inwardLinear > inwardCubic + 0.02f, "depth response reduces the inner rim")
        assertTrue(linearDepth[80, 41].alpha > 0.05f && linearDepth[41, 80].alpha > 0.05f)
        assertImageAlphaClose(
            linearDepth,
            capture(
                Highlight(
                    width = 8.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Plain(Color.White, BlendMode.Plus),
                    direction = HighlightDirection(angle = 270f),
                    depthCurve = BackdropCurve.Linear,
                )
            ),
        )
    }

    @Test
    fun ambientAngleSelectsOppositeSignedSides() {
        val top =
            capture(
                Highlight(
                    width = 6.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Ambient(Color.White, BlendMode.Plus),
                    direction = HighlightDirection(angle = 270f),
                )
            )
        val bottom =
            capture(
                Highlight(
                    width = 6.dp,
                    blurRadius = 0.dp,
                    style = HighlightStyle.Ambient(Color.White, BlendMode.Plus),
                    direction = HighlightDirection(angle = 90f),
                )
            )

        assertTrue(top[80, 41].red > top[80, 118].red + 0.5f)
        assertTrue(bottom[80, 118].red > bottom[80, 41].red + 0.5f)
    }

    private fun capture(highlight: Highlight): PixelMap {
        lateinit var result: PixelMap
        runComposeUiTest {
            val selected = mutableStateOf(highlight)
            setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop,
                                { RectangleShape },
                                highlight = { selected.value },
                                shadow = { Shadow.None },
                                innerShadow = { InnerShadow.None },
                            )
                    )
                }
            }
            waitForIdle()
            result = onNodeWithTag("root").captureToImage().toPixelMap()
        }
        return result
    }

    private fun assertImageAlphaClose(
        first: PixelMap,
        second: PixelMap,
    ) {
        var maximumDifference = 0f
        for (y in 0 until first.height) for (x in 0 until first.width) {
            maximumDifference =
                maxOf(maximumDifference, abs(first[x, y].alpha - second[x, y].alpha))
        }
        assertTrue(
            maximumDifference <= 2f / 255f,
            "Plain alpha should not depend on direction; max difference=$maximumDifference",
        )
    }
}
