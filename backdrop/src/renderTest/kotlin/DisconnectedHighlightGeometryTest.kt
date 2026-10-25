package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.highlight

class DisconnectedHighlightGeometryTest {
    @Test
    fun thinGenericContourKeepsPartialClipCoverageAtSubpixelEdges() = runRenderTest {
        setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(120.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                Box(
                    Modifier.offset(20.dp, 20.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { ThinSubpixelShape },
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
                                            width = 8.dp,
                                            direction = HighlightDirection.Uniform,
                                            style = HighlightStyle.Solid(Color.White),
                                            colorResponse = HighlightColorResponse(amount = 0f),
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
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        val edgeAlpha = maxOf(pixels[40, 40].alpha, pixels[41, 40].alpha)
        assertTrue(edgeAlpha > 0f, "subpixel contour retains partial clip coverage")
        assertTrue(edgeAlpha < 1f, "subpixel contour edge remains antialiased")
        assertTrue(pixels[39, 40].alpha < 0.02f, "pixels outside the clipped contour stay clear")
        assertTrue(pixels[42, 40].alpha < 0.02f, "pixels beyond the clipped contour stay clear")
    }

    @Test
    fun separateContoursAndHoleKeepTheirOwnRimsWithoutBridgingTheGap() = runRenderTest {
        setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(120.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                Box(
                    Modifier.offset(20.dp, 20.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { DisconnectedShape },
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
                                            width = 4.dp,
                                            direction = HighlightDirection.Uniform,
                                            style = HighlightStyle.Solid(Color.White),
                                            colorResponse = HighlightColorResponse(amount = 0f),
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
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        assertTrue(pixels[22, 50].alpha > 0.05f, "first contour has a visible rim")
        assertTrue(pixels[72, 50].alpha > 0.05f, "second contour has a visible rim")
        assertTrue(pixels[60, 50].alpha < 0.02f, "the gap between contours stays unlit")
        assertTrue(pixels[35, 50].alpha < 0.02f, "the hole stays clear")
        assertTrue(
            pixels[28, 50].alpha > 0.05f || pixels[29, 50].alpha > 0.05f,
            "filled side of hole boundary should have a rim: x28=${pixels[28, 50]}, x29=${pixels[29, 50]}, inside=${pixels[35, 50]}",
        )
    }
}

private object ThinSubpixelShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(Path().apply { addRect(Rect(20.25f, 10f, 20.75f, 70f)) })
}

private object DisconnectedShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Generic(
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, 30f, 60f))
                addRect(Rect(50f, 0f, 80f, 60f))
                addRect(Rect(10f, 20f, 20f, 40f))
            },
        )
}
