package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class SurfaceIsolationRenderingTest {
    @Test
    fun epochChangeReplacesTheLayerThatRecordsDescendantDrawing() = runComposeUiTest {
        val epoch = mutableIntStateOf(0)
        val redraw = mutableIntStateOf(0)
        var observedLayer: GraphicsLayer? = null
        var drawCount = 0
        var compositionToken: Any? = null
        setContent {
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                val token = androidx.compose.runtime.remember { Any() }
                compositionToken = token
                val backdrop = rememberCanvasBackdrop {}
                Box(Modifier.size(80.dp)) {
                    Box(
                        Modifier.size(60.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                effects = {},
                                layerBlock = { alpha = 0.5f },
                                highlight = { Highlight.None },
                                shadow = { Shadow.None },
                                innerShadow = { InnerShadow.None },
                            )
                    ) {
                        Box(
                            Modifier.size(20.dp).drawWithContent {
                                check(redraw.intValue >= 0)
                                observedLayer = drawContext.graphicsLayer
                                drawCount++
                                drawContent()
                            }
                        )
                    }
                }
            }
        }
        waitForIdle()
        val initialLayer = requireNotNull(observedLayer)
        val initialToken = requireNotNull(compositionToken)
        val initialDrawCount = drawCount
        assertEquals(CompositingStrategy.Offscreen, initialLayer.compositingStrategy)
        assertEquals(0.5f, initialLayer.alpha)

        runOnIdle { redraw.intValue++ }
        waitForIdle()
        assertTrue(drawCount > initialDrawCount, "The fixed-epoch redraw must record child content")
        assertSame(initialLayer, observedLayer)

        val fixedEpochDrawCount = drawCount
        runOnIdle {
            epoch.intValue++
            redraw.intValue++
        }
        waitForIdle()

        val refreshedLayer = requireNotNull(observedLayer)
        assertTrue(drawCount > fixedEpochDrawCount, "The new epoch must record child content")
        assertSame(initialToken, compositionToken, "The composition must survive the layer refresh")
        assertNotSame(initialLayer, refreshedLayer)
        assertTrue(initialLayer.isReleased)
        assertFalse(refreshedLayer.isReleased)
    }

    @Test
    fun groupAlphaCompositesOverlappingContentOnce() = runComposeUiTest {
        val epoch = mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                IsolationScene()
            }
        }
        waitForIdle()

        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        val overlap = pixels[25, 15]
        assertTrue(overlap.red in 0.47f..0.53f && overlap.green in 0.47f..0.53f)
        assertTrue(overlap.blue in 0.47f..0.53f)
        assertTrue(pixels[65, 15].red < 0.02f, "Pixels outside the isolated group stay black")

        runOnIdle { epoch.intValue++ }
        waitForIdle()
        val refreshed = onNodeWithTag("root").captureToImage().toPixelMap()[25, 15]
        assertTrue(refreshed.red in 0.47f..0.53f && refreshed.green in 0.47f..0.53f)
        assertTrue(refreshed.blue in 0.47f..0.53f)
    }

    @androidx.compose.runtime.Composable
    private fun IsolationScene() {
        val backdrop = rememberCanvasBackdrop {}
        Box(Modifier.size(80.dp).background(Color.Black).testTag("root")) {
            Box(
                Modifier.size(60.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {},
                        layerBlock = { alpha = 0.5f },
                        highlight = { Highlight.None },
                        shadow = { Shadow.None },
                        innerShadow = { InnerShadow.None },
                    )
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(5.dp, 5.dp).size(30.dp).background(Color.White))
                    Box(Modifier.offset(20.dp, 5.dp).size(30.dp).background(Color.White))
                }
            }
        }
    }
}
