package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

class ResizingDeclarationRenderingTest {
    @Test
    fun geometryIndependentDeclarationSurvivesMeasuredResize() =
        resize(readSize = false, fixedSurface = false)

    @Test
    fun declarationSizeReadsRebuildNestedImages() = resize(readSize = true, fixedSurface = false)

    @Test
    fun fixedSurfaceRetainsItsDeclarationAcrossMeasuredResize() =
        resize(readSize = false, fixedSurface = true)

    @Test
    fun fixedSurfaceSizeReadsRebuildItsDeclaration() = resize(readSize = true, fixedSurface = true)

    private fun resize(readSize: Boolean, fixedSurface: Boolean) = runRenderTest {
        val width = mutableIntStateOf(40)
        var declarations = 0
        val effectWidths = mutableListOf<Float>()
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            Box(
                Modifier.layout { child, _ ->
                        val measured = child.measure(Constraints.fixed(width.intValue, 40))
                        layout(measured.width, measured.height) { measured.place(0, 0) }
                    }
                    .testTag("receiver")
                    .drawBackdrop(source, { RectangleShape }) {
                        declarations++
                        if (fixedSurface) {
                            surface {
                                if (readSize) fill(if (size.width > 60f) Color.Red else Color.Blue)
                                else fill(Color.Red)
                                effects {
                                    effectWidths += size.width
                                    blur(1f)
                                }
                                highlight(Highlight.None)
                                shadow(Shadow.None)
                                innerShadow(InnerShadow.None)
                                interactionLight(InteractionLighting.None)
                            }
                        } else {
                            val body = image {
                                source()
                                effects {
                                    effectWidths += size.width
                                    blur(1f)
                                }
                                fill(if (!readSize || size.width > 60f) Color.Red else Color.Blue)
                            }
                            replace(body)
                        }
                    },
            )
        }
        waitForIdle()
        val initial = declarations
        runOnIdle { width.intValue = 80 }
        waitForIdle()
        val pixels = onNodeWithTag("receiver").captureToImage().toPixelMap()
        assertEquals(80, pixels.width)
        assertTrue(pixels[70, 20].red > .9f)
        assertTrue(effectWidths.any { it == 80f }, "Effects receive the measured geometry")
        assertEquals(
            if (readSize) initial + 1 else initial,
            declarations,
            "Only declaration-time size reads require rebuilding the graph",
        )
    }
}
