package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.highlight

class CertifiedHighlightRenderingTest {
    @Test
    fun retainedShaderMatchesFreshRendererWhenTheCertificateDomainChanges() = runRenderTest {
        val width = mutableIntStateOf(80)
        val fresh = mutableStateOf(false)
        val shape =
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline =
                    Outline.Generic(
                        Path().apply {
                            moveTo(18f, 0f)
                            lineTo(62f, 0f)
                            cubicTo(80f, 0f, 80f, 0f, 80f, 18f)
                            lineTo(80f, 62f)
                            cubicTo(80f, 80f, 80f, 80f, 62f, 80f)
                            lineTo(18f, 80f)
                            cubicTo(0f, 80f, 0f, 80f, 0f, 62f)
                            lineTo(0f, 18f)
                            cubicTo(0f, 0f, 0f, 0f, 18f, 0f)
                            close()
                        },
                    )
            }
        setContent {
            val graphics = LocalGraphicsContext.current
            val retained = remember(graphics) { BackdropRenderer { graphics } }
            val reference = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(retained, reference) {
                onDispose {
                    retained.close()
                    reference.close()
                }
            }
            Canvas(Modifier.size(100.dp, 100.dp).testTag("certificate-domain")) {
                val size = Size(width.intValue.toFloat(), 80f)
                val graph = BackdropRenderScope(this, size, shape, layoutDirection)
                graph.foreground {
                    highlight(
                        Highlight(
                            style = HighlightStyle.Solid(Color.White),
                            width = 5.dp,
                            colorResponse = HighlightColorResponse(amount = 0f),
                        ),
                    )
                }
                drawRect(Color.Black)
                val renderer = if (fresh.value) reference.also { it.close() } else retained
                renderer.resolve(graph, Rect(0f, 0f, size.width, size.height))
                renderer.beginFrame()
                renderer.draw(this, image = graph.foregroundImage, source = {})
            }
        }
        for (value in listOf(80, 81, 79, 81, 80)) {
            runOnIdle {
                width.intValue = value
                fresh.value = false
            }
            // Both captures use one pixel origin, keeping Android positional dithering identical.
            val retainedPixels = onNodeWithTag("certificate-domain").captureToImage().toPixelMap()
            runOnIdle { fresh.value = true }
            val freshPixels = onNodeWithTag("certificate-domain").captureToImage().toPixelMap()
            var visible = false
            for (y in 0 until 80) for (x in 0 until 82) {
                if (retainedPixels[x, y].red > .01f) visible = true
                assertEquals(retainedPixels[x, y], freshPixels[x, y], "width=$value pixel=($x,$y)")
            }
            assertTrue(visible, "The highlight must render at width=$value")
        }
    }
}
