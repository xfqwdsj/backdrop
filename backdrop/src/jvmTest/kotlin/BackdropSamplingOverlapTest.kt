package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class BackdropSamplingOverlapTest {
    @Test
    fun anotherSamplerPreservesTheSurfaceDuringPartialOverlap() {
        for (checker in listOf(false, true)) {
            for (chromatic in listOf(false, true)) {
                val baseline = mutableMapOf<Int, PixelMap>()
                for (sampling in listOf(false, true)) runComposeUiTest {
                    val position = mutableIntStateOf(120)
                    setContent {
                        val background = rememberLayerBackdrop()
                        val page = rememberLayerBackdrop()
                        Box(Modifier.size(240.dp, 160.dp).testTag("root")) {
                            Box(Modifier.fillMaxSize().layerBackdrop(page)) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(background)) {
                                    for (y in 0 until size.height.toInt() step 4) {
                                        for (x in 0 until size.width.toInt() step 4) {
                                            val white = (y / 4 + if (checker) x / 4 else 0) % 2 == 0
                                            drawRect(
                                                if (white) Color.White else Color.Black,
                                                Offset(x.toFloat(), y.toFloat()),
                                                Size(4f, 4f),
                                            )
                                        }
                                    }
                                }
                                Box(
                                    Modifier.offset(40.dp, position.intValue.dp)
                                        .size(160.dp, 80.dp)
                                        .drawBackdrop(
                                            background,
                                            { RoundedCornerShape(20.dp) },
                                            effects = {
                                                blur(8.dp.toPx())
                                                lens(
                                                    24.dp.toPx(),
                                                    24.dp.toPx(),
                                                    depthEffect = true,
                                                    chromaticAberration = chromatic,
                                                )
                                            },
                                            highlight = { Highlight.None },
                                            shadow = { Shadow.None },
                                            innerShadow = { InnerShadow.None },
                                        )
                                )
                            }
                            if (sampling)
                                Box(
                                    Modifier.size(240.dp, 64.dp)
                                        .drawPlainBackdrop(page, { RectangleShape }, effects = {})
                                )
                        }
                    }
                    for (top in listOf(84, 80, 76, 72, 64, 60, 48, 32, 16, 0, -16, -40)) {
                        runOnIdle { position.intValue = top }
                        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
                        if (!sampling) baseline[top] = pixels
                        else {
                            val reference = baseline.getValue(top)
                            var maximum = 0f
                            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                                val a = reference[x, y]
                                val b = pixels[x, y]
                                maximum =
                                    maxOf(
                                        maximum,
                                        abs(a.red - b.red),
                                        abs(a.green - b.green),
                                        abs(a.blue - b.blue),
                                        abs(a.alpha - b.alpha),
                                    )
                            }
                            assertTrue(
                                maximum <= 1f / 255f + 0.00001f,
                                "checker=$checker chromatic=$chromatic top=$top difference=$maximum",
                            )
                        }
                    }
                }
            }
        }
    }
}
