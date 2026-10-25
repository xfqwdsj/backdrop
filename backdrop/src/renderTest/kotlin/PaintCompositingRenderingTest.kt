package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

class PaintCompositingRenderingTest {
    @Test
    fun streamingPaintPreservesGroupAlphaSurfaceClipAndIndependentExport() = runRenderTest {
        val strategy = mutableStateOf(CompositingStrategy.Offscreen)
        val tint = mutableStateOf(Color.Red)
        setContent {
            val source = rememberCanvasBackdrop {}
            val exported = rememberLayerBackdrop()
            Column {
                Box(
                    Modifier.size(80.dp)
                        .testTag("paint")
                        .drawBackdrop(
                            source,
                            { RoundedCornerShape(20.dp) },
                            exportedBackdrop = exported,
                            render = {
                                val original = image {
                                    paint { drawRect(Color.Blue.copy(alpha = .6f)) }
                                }
                                export(original)
                                group(alpha = .5f) {
                                    replace(original)
                                    paintSurface(strategy.value) {
                                        drawRect(tint.value.copy(alpha = .4f))
                                    }
                                }
                            },
                        ),
                )
                Canvas(Modifier.size(80.dp).testTag("export")) { drawLayer(exported.graphicsLayer) }
            }
        }
        for ((mode, color) in
            listOf(
                CompositingStrategy.Offscreen to Color.Red,
                CompositingStrategy.Auto to Color.Red,
                CompositingStrategy.Auto to Color.Green,
                CompositingStrategy.Offscreen to Color.Green,
            )) {
            runOnIdle {
                strategy.value = mode
                tint.value = color
            }
            val pixels = onNodeWithTag("paint").captureToImage().toPixelMap()
            val center = pixels[40, 40]
            assertTrue(abs(center.alpha - .38f) < .02f, "Group alpha applies once: $center")
            assertTrue(abs(center.blue - .36f / .76f) < .02f, "Retain input color: $center")
            val channel = if (color == Color.Red) center.red else center.green
            assertTrue(abs(channel - .4f / .76f) < .02f, "Observe appended tint: $center")
            val corner = pixels[0, 0]
            assertTrue(
                corner.blue > .98f && abs(corner.alpha - .3f) < .02f,
                "Surface clipping affects only appended paint: $corner",
            )
            val original = onNodeWithTag("export").captureToImage().toPixelMap()[40, 40]
            assertTrue(
                original.blue > .98f && abs(original.alpha - .6f) < .02f,
                "Painting preserves the independently exported input: $original",
            )
        }
    }

    @Test
    fun defaultPaintClearErasesItsInputWithoutErasingTheDestination() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red) }
            Canvas(
                Modifier.size(80.dp)
                    .testTag("clear")
                    .drawBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            foreground {
                                source()
                                paint {
                                    drawRect(
                                        Color.Transparent,
                                        Offset(20f, 20f),
                                        Size(40f, 40f),
                                        blendMode = BlendMode.Clear,
                                    )
                                }
                            }
                            group {}
                        },
                    ),
            ) {
                drawRect(Color.Blue)
            }
        }
        val pixels = onNodeWithTag("clear").captureToImage().toPixelMap()
        assertTrue(pixels[40, 40].blue > .98f, "Clear preserves separately drawn child content")
        assertTrue(pixels[5, 5].red > .98f, "Paint retains its source outside the erased region")
    }
}
