package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class HighlightModifierRenderingTest {
    @Test
    fun plainKeepsFixedBandIndependentOfDirectionalResponse() {
        val plain =
            capture(
                Highlight(
                    width = 8.dp,
                    style = HighlightStyle.Solid(Color.White),
                    direction = HighlightDirection.None,
                    colorResponse = HighlightColorResponse(amount = 0f),
                )
            )
        val preset =
            capture(
                Highlight.Plain.copy(
                    width = 8.dp,
                    style = HighlightStyle.Solid(Color.White),
                    colorResponse = HighlightColorResponse(amount = 0f),
                )
            )

        assertTrue(plain[80, 41].alpha > 0.05f && plain[41, 80].alpha > 0.05f)
        assertImageAlphaClose(plain, preset)
    }

    @Test
    fun ambientAngleSelectsOppositeSignedSides() {
        val top =
            capture(
                Highlight(
                    width = 6.dp,
                    style = HighlightStyle.TwoTone(Color.White),
                    direction = HighlightDirection(angle = 270f),
                    colorResponse = HighlightColorResponse(amount = 0f),
                )
            )
        val bottom =
            capture(
                Highlight(
                    width = 6.dp,
                    style = HighlightStyle.TwoTone(Color.White),
                    direction = HighlightDirection(angle = 90f),
                    colorResponse = HighlightColorResponse(amount = 0f),
                )
            )

        assertTrue(top[80, 41].red > top[80, 118].red + 0.5f)
        assertTrue(bottom[80, 118].red > bottom[80, 41].red + 0.5f)
    }

    @Test
    fun gpuProfileRetainsEmissionAcrossTheHalfPixelOuterFootprint() {
        val pixels =
            capture(
                Highlight(
                    width = 8.25.dp,
                    falloff = BackdropCurve.CubicBezier(.35f, 1f, .6f, 1f),
                    style = HighlightStyle.Solid(Color.White),
                    direction = HighlightDirection.None,
                    colorResponse = HighlightColorResponse(amount = 0f),
                )
            )

        assertTrue(pixels[80, 48].alpha > 0f)
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
