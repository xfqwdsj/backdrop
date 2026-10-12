package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class DisconnectedHighlightGeometryTest {
    @Test
    fun separateContoursAndHoleKeepTheirOwnRimsWithoutBridgingTheGap() = runComposeUiTest {
        setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(120.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                Box(
                    Modifier.offset(20.dp, 20.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            backdrop,
                            { DisconnectedShape },
                            highlight = {
                                Highlight(
                                    width = 4.dp,
                                    blurRadius = 0.dp,
                                    style = HighlightStyle.Plain(Color.White, BlendMode.Plus),
                                )
                            },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
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
            }
        )
}
