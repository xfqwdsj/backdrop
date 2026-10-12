package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.EnvironmentHighlight
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class EnvironmentHighlightContributionTest {
    @Test
    fun brightnessIsNeutralTintRetainsHueAndThicknessExtendsTheEmittingBand() {
        val brightness =
            capture(
                EnvironmentHighlight(
                    sampleDistance = 16.dp,
                    blurRadius = 0.dp,
                    brightness = EnvironmentHighlight.Contribution(1f, 0.1f),
                    tint = EnvironmentHighlight.Contribution(0f, 0f),
                    thickness = EnvironmentHighlight.Thickness(0.dp, 0f),
                ),
                style = HighlightStyle.Plain(Color.Black, BlendMode.Plus),
                width = 3.dp,
            )
        val tint =
            capture(
                EnvironmentHighlight(
                    sampleDistance = 16.dp,
                    blurRadius = 0.dp,
                    brightness = EnvironmentHighlight.Contribution(0f, 0f),
                    tint = EnvironmentHighlight.Contribution(1f, 0.1f),
                    thickness = EnvironmentHighlight.Thickness(0.dp, 0f),
                ),
                style = HighlightStyle.Plain(Color.Black, BlendMode.Plus),
                width = 3.dp,
            )
        val brightnessRim = brightness[118, 80]
        val tintRim = tint[118, 80]
        assertTrue(brightnessRim.red > 0.02f, "brightness rim=$brightnessRim")
        assertTrue(
            abs(brightnessRim.red - brightnessRim.green) < 0.03f,
            "brightness contribution is neutral",
        )
        assertTrue(tintRim.red > tintRim.green + 0.08f, "tint contribution preserves sampled hue")

        val thickness =
            capture(
                EnvironmentHighlight(
                    sampleDistance = 16.dp,
                    blurRadius = 0.dp,
                    brightness = EnvironmentHighlight.Contribution(1f, 0.1f),
                    tint = EnvironmentHighlight.Contribution(0f, 0f),
                    thickness = EnvironmentHighlight.Thickness(3.dp, 0.1f),
                ),
                style = HighlightStyle.Plain(Color.Black, BlendMode.Plus),
                width = 3.dp,
            )
        assertTrue(thickness[115, 80].red > 0.02f, "thickness extends the brightness band")
        assertTrue(
            thickness[111, 80].red < 0.02f,
            "thickness does not extend beyond its configured width",
        )
    }

    @Test
    fun thicknessAloneDoesNotEmitLight() {
        val pixels =
            capture(
                EnvironmentHighlight(
                    sampleDistance = 16.dp,
                    blurRadius = 0.dp,
                    brightness = EnvironmentHighlight.Contribution(0f, 0f),
                    tint = EnvironmentHighlight.Contribution(0f, 0f),
                    thickness = EnvironmentHighlight.Thickness(3.dp, 0f),
                ),
                style = HighlightStyle.Plain(Color.Black, BlendMode.Plus),
                width = 3.dp,
            )

        assertTrue(
            pixels[115, 80].red < 0.002f,
            "a width extension with zero strengths emits no light",
        )
    }

    private fun capture(
        environment: EnvironmentHighlight,
        style: HighlightStyle,
        width: Dp,
    ): PixelMap {
        lateinit var pixels: PixelMap
        runComposeUiTest {
            val source = mutableStateOf(Color.Red)
            setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                source.value,
                                Offset(122.dp.toPx(), 72.dp.toPx()),
                                Size(22.dp.toPx(), 16.dp.toPx()),
                            )
                        }
                    }
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop,
                                { RectangleShape },
                                highlight = {
                                    Highlight(
                                        width = width,
                                        blurRadius = 0.dp,
                                        style = style,
                                        environment = environment,
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
}
