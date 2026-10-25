package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

class BackdropPrimitiveRenderingTest {
    @Test
    fun shadowSeparatesInteriorAndExterior() = runRenderTest {
        val config =
            Shadow(
                radius = 6.dp,
                offset = DpOffset(3.dp, 2.dp),
                color = Color.Black.copy(alpha = .4f),
            )
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(Modifier.size(120.dp).testTag("sample")) {
                Box(
                    Modifier.size(80.dp)
                        .offset(20.dp, 20.dp)
                        .drawBackdrop(
                            source,
                            { RectangleShape },
                            render = { shadow(config) },
                        ),
                )
            }
        }
        val b = onNodeWithTag("sample").captureToImage().toPixelMap()
        assertTrue(b[60, 60].alpha < .01f, "Outer shadow excludes its interior")
        assertTrue(b[102, 60].alpha > .01f, "Outer shadow reaches outside the receiving outline")
        assertTrue(b[0, 0].alpha < .01f, "Outer shadow has finite support")
    }

    @Test
    fun innerShadowClipsToItsOutline() = runRenderTest {
        val config =
            InnerShadow(
                radius = 5.dp,
                offset = DpOffset(4.dp, 7.dp),
                color = Color.Blue.copy(alpha = .7f),
            )
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(Modifier.size(120.dp).testTag("sample")) {
                Box(
                    Modifier.size(80.dp)
                        .offset(20.dp, 20.dp)
                        .drawBackdrop(
                            source,
                            { RectangleShape },
                            render = { innerShadow(config) },
                        ),
                )
            }
        }
        val b = onNodeWithTag("sample").captureToImage().toPixelMap()
        assertTrue(b[60, 20].alpha > .1f, "Inner shadow covers the offset-facing edge")
        assertTrue(b[60, 60].alpha < .01f, "Inner shadow leaves the distant interior transparent")
        for (y in 0 until 120) for (x in 0 until 120) {
            if (x !in 20 until 100 || y !in 20 until 100)
                assertTrue(b[x, y].alpha < .01f, "Inner shadow stays within its outline ($x,$y)")
        }
    }

    @Test
    fun contourEmissionStaysWithinItsOutline() = runRenderTest {
        val config =
            Highlight(
                width = 8.dp,
                direction = HighlightDirection.Uniform,
                colorResponse = HighlightColorResponse(0f),
            )
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(Modifier.size(120.dp).testTag("sample")) {
                Box(
                    Modifier.size(80.dp)
                        .offset(20.dp, 20.dp)
                        .drawBackdrop(
                            source,
                            { RectangleShape },
                            render = { highlight(config) },
                        ),
                )
            }
        }
        val b = onNodeWithTag("sample").captureToImage().toPixelMap()
        assertTrue(b[20, 60].alpha > .1f, "Highlight emits at the contour")
        assertTrue(b[60, 60].alpha < .01f, "Highlight leaves the distant interior transparent")
        for (y in 0 until 120) for (x in 0 until 120) {
            if (x !in 20 until 100 || y !in 20 until 100)
                assertTrue(b[x, y].alpha < .01f, "Highlight stays within its outline ($x,$y)")
        }
    }

    @Test
    fun serialBlurRetainsSamplingBeyondVisibleOutput() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop {
                var x = samplingBounds.left.toInt() - 8
                while (x < samplingBounds.right + 8) {
                    drawRect(
                        if ((x / 8) % 2 == 0) Color.White else Color.Black,
                        Offset(x.toFloat(), samplingBounds.top),
                        Size(8f, samplingBounds.height),
                    )
                    x += 8
                }
            }
            Column {
                for (split in listOf(false, true)) {
                    Box(Modifier.size(120.dp).testTag(if (split) "split" else "chain")) {
                        Box(
                            Modifier.size(80.dp)
                                .offset(20.dp, 20.dp)
                                .drawBackdrop(
                                    source,
                                    { RectangleShape },
                                    render = {
                                        if (split) {
                                            effects { blur(3f) }
                                            effects { blur(4f) }
                                        } else {
                                            effects {
                                                blur(3f)
                                                blur(4f)
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }
            }
        }
        val a = onNodeWithTag("chain").captureToImage().toPixelMap()
        val b = onNodeWithTag("split").captureToImage().toPixelMap()
        for (y in 0 until 120) for (x in 0 until 120) {
            assertTrue(abs(a[x, y].red - b[x, y].red) < .025f, "Serial blur red ($x,$y)")
            assertTrue(abs(a[x, y].alpha - b[x, y].alpha) < .015f, "Serial blur alpha ($x,$y)")
        }
    }

    @Test
    fun luminanceMaskAndTransformRemainOnRenderingPath() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .drawBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val white = image {
                                paint {
                                    drawRect(Color.White, Offset(0f, 0f), Size(20f, 80f))
                                }
                            }
                            val gray = image { paint { drawRect(Color(.5f, .5f, .5f)) } }
                            group {
                                replace(white)
                                mask(gray, BackdropMaskChannel.Luminance)
                                transform(Matrix().apply { translate(30f, 0f) })
                            }
                        },
                    ),
            )
        }
        val p = onNodeWithTag("scene").captureToImage().toPixelMap()
        assertTrue(p[5, 40].alpha < .01f)
        assertTrue(abs(p[40, 40].alpha - .5f) < .03f, "luminance transformed alpha: ${p[40,40]}")
    }
}
