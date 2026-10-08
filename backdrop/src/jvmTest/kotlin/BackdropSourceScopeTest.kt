package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalTestApi::class)
class BackdropSourceScopeTest {
    @Test
    fun finiteProceduralSourceExtendsItsOwnTexels() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        val source = procedural {
            drawSource(Rect(0f, 0f, 16f, 16f)) { drawRect(Color.Red) }
        }
        setContent {
            output = rememberLayerBackdrop()
            Box(
                Modifier.size(64.dp)
                    .drawPlainBackdrop(
                        source,
                        { RectangleShape },
                        effects = {},
                        insets = { BackdropInsets.all(8.dp) },
                        exportedBackdrop = output,
                    )
            )
        }
        waitForIdle()
        val pixels = output.graphicsLayer.toImageBitmap().toPixelMap()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            assertTrue(pixels[x, y].alpha > 0.99f, "Finite source lost alpha at $x,$y")
        }
    }

    @Test
    fun sourceTileModesMapExteriorTexelsAndKeepColor() {
        val modes = listOf(TileMode.Clamp, TileMode.Decal, TileMode.Repeated, TileMode.Mirror)
        val expected =
            listOf(
                listOf(Color.Red, Color.Red, Color.Blue, Color.Blue),
                listOf(Color.Transparent, Color.Red, Color.Transparent, Color.Transparent),
                listOf(Color.Blue, Color.Red, Color.Red, Color.Blue),
                listOf(Color.Red, Color.Red, Color.Blue, Color.Red),
            )
        for ((index, mode) in modes.withIndex()) runComposeUiTest {
            lateinit var output: LayerBackdrop
            val source = procedural {
                drawSource(Rect(0f, 0f, 4f, 4f), mode) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, topLeft = Offset(2f, 0f), size = Size(2f, 4f))
                }
            }
            setContent {
                output = rememberLayerBackdrop()
                Box(
                    Modifier.size(8.dp)
                        .drawPlainBackdrop(
                            source,
                            { RectangleShape },
                            effects = {},
                            insets = { BackdropInsets.all(4.dp) },
                            exportedBackdrop = output,
                        )
                )
            }
            waitForIdle()
            val pixels = output.graphicsLayer.toImageBitmap().toPixelMap()
            for ((point, x) in listOf(-2, 1, 4, 6).withIndex()) {
                val actual =
                    pixels[x + output.layerOffset.x.toInt(), 1 + output.layerOffset.y.toInt()]
                val wanted = expected[index][point]
                assertTrue(
                    kotlin.math.abs(actual.alpha - wanted.alpha) < 0.01f &&
                        kotlin.math.abs(actual.red - wanted.red) < 0.01f &&
                        kotlin.math.abs(actual.blue - wanted.blue) < 0.01f,
                    "$mode at source x=$x: expected $wanted, got $actual",
                )
            }
        }
    }

    @Test
    fun scopedAffineTransformMapsRequestsBeforeSourceExtension() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        val matrix =
            Matrix().apply {
                translate(20f, 20f)
                rotateZ(90f)
                scale(2f, 2f)
            }
        val source = procedural {
            withTransform(matrix) {
                drawSource(Rect(0f, 0f, 16f, 16f)) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, topLeft = Offset(8f, 0f), size = Size(8f, 16f))
                }
            }
        }
        setContent {
            output = rememberLayerBackdrop()
            Box(
                Modifier.size(64.dp)
                    .drawPlainBackdrop(
                        source,
                        { RectangleShape },
                        effects = {},
                        exportedBackdrop = output,
                    )
            )
        }
        waitForIdle()
        val pixels = output.graphicsLayer.toImageBitmap().toPixelMap()
        assertTrue(pixels[12, 24].red > 0.99f)
        assertTrue(pixels[12, 40].blue > 0.99f)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            assertTrue(pixels[x, y].alpha > 0.99f, "Affine source lost alpha at $x,$y")
        }
    }

    private fun procedural(draw: BackdropDrawScope.() -> Unit): Backdrop =
        object : Backdrop {
            override val isCoordinatesDependent: Boolean = false

            override fun BackdropDrawScope.drawBackdrop(
                density: Density,
                coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?,
            ) {
                draw()
            }
        }
}
