package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
import top.ltfan.backdrop.highlight.highlight

class HighlightTilesRenderingTest {
    @Test
    fun genericTilesMatchTheAnalyticSurfaceAcrossWidthAndColorSourceUpdates() = runRenderTest {
        val generic = mutableStateOf(false)
        val width = mutableFloatStateOf(5f)
        val amount = mutableFloatStateOf(0f)
        val genericRectangle =
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline = Outline.Generic(Path().apply { addRect(Rect(Offset.Zero, size)) })
            }
        setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(120.dp).testTag("highlight-tiles")) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Red, Color.Blue, Color.Green),
                            Offset.Zero,
                            Offset(120f, 120f),
                        ),
                    )
                }
                Box(
                    Modifier.offset(20.dp, 20.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { if (generic.value) genericRectangle else RectangleShape },
                            render = {
                                val material = image {
                                    source()
                                    clipSurface()
                                }
                                draw(material)
                                foreground {
                                    highlight(
                                        Highlight(
                                            width = width.floatValue.dp,
                                            falloff = BackdropCurve.Linear,
                                            direction = HighlightDirection.Uniform,
                                            style =
                                                HighlightStyle.Solid(Color.White.copy(alpha = .7f)),
                                            colorResponse =
                                                HighlightColorResponse(amount.floatValue),
                                        ),
                                        colorSource = material,
                                    )
                                }
                            },
                        ),
                )
            }
        }
        for (response in listOf(0f, 1f)) for (band in listOf(.25f, 5f, 40f, 3f)) {
            runOnIdle {
                amount.floatValue = response
                width.floatValue = band
                generic.value = false
            }
            val expected = onNodeWithTag("highlight-tiles").captureToImage().toPixelMap()
            runOnIdle { generic.value = true }
            val actual = onNodeWithTag("highlight-tiles").captureToImage().toPixelMap()
            for (y in 20 until 100) for (x in 20 until 100) {
                val a = actual[x, y]
                val e = expected[x, y]
                val error =
                    maxOf(
                        abs(a.red - e.red),
                        abs(a.green - e.green),
                        abs(a.blue - e.blue),
                        abs(a.alpha - e.alpha),
                    )
                assertTrue(
                    error <= .02f,
                    "width=$band, response=$response, pixel=($x,$y), expected=$e, actual=$a",
                )
            }
        }
    }
}
