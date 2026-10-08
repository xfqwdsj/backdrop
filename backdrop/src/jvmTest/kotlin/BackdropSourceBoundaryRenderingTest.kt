package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberCombinedBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.effects.progressiveBlur

@OptIn(ExperimentalTestApi::class)
class BackdropSourceBoundaryRenderingTest {
    @Test
    fun finiteSourceClampsAtScreenTopAndPreservesInteriorTransparency() = runComposeUiTest {
        lateinit var firstConsumer: LayerBackdrop
        lateinit var secondConsumer: LayerBackdrop
        setContent {
            SourceBoundaryScene(alpha = 1f, tileMode = TileMode.Clamp) { source ->
                firstConsumer = rememberLayerBackdrop()
                secondConsumer = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Consumer(source, firstConsumer, Modifier.offset(x = 0.dp, y = 0.dp)) {
                        progressiveBlur(16f, BackdropEdge.Top)
                        blur(6f, TileMode.Clamp)
                        lens(4f, 0.15f)
                    }
                    // A displaced consumer asks for a different effect chain. Its larger source
                    // also keeps the full request inside the producer's recorded content.
                    Consumer(
                        source,
                        secondConsumer,
                        Modifier.offset(x = 144.dp, y = 32.dp),
                        width = 96.dp,
                    ) {
                        blur(12f, TileMode.Clamp)
                        lens(8f, 0.1f)
                    }
                }
            }
        }
        waitForIdle()

        assertSolidAlpha(firstConsumer, 1f)
        assertTransparentHole(firstConsumer)
        assertSolidAlpha(secondConsumer, 1f)
    }

    @Test
    fun finiteSourceClampPreservesFractionalAlphaThroughSerialEffects() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            SourceBoundaryScene(alpha = 0.375f, tileMode = TileMode.Clamp) { source ->
                output = rememberLayerBackdrop()
                Consumer(source, output, Modifier) {
                    progressiveBlur(16f, BackdropEdge.Top)
                    blur(8f, TileMode.Clamp)
                    lens(4f, 0.15f)
                }
            }
        }
        waitForIdle()

        assertSolidAlpha(output, 0.375f)
        assertTransparentHole(output)
    }

    @Test
    fun decalRetainsTransparentExteriorAtTheFiniteTopEdge() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            SourceBoundaryScene(alpha = 1f, tileMode = TileMode.Decal) { source ->
                output = rememberLayerBackdrop()
                Consumer(source, output, Modifier) {
                    progressiveBlur(16f, BackdropEdge.Top)
                }
            }
        }
        waitForIdle()

        val image = export(output)
        val pixels = image.toPixelMap()
        val firstSurfaceRow = output.layerOffset.y.toInt()
        // x is inside a solid source band, away from the transparent interior stripe. Decal
        // partially covers the source edge row, then reaches full alpha inside the source.
        assertTrue(
            pixels[output.layerOffset.x.toInt() + 18, firstSurfaceRow].alpha < 0.9f,
            "Decal should preserve partial transparency at the finite source edge",
        )
        assertTrue(
            pixels[output.layerOffset.x.toInt() + 18, firstSurfaceRow + 19].alpha > 0.99f,
            "The same source column should be opaque inside the finite source",
        )
    }

    @Test
    fun combinedSourcesKeepTheirOwnTileModes() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            val clamped = rememberLayerBackdrop(tileMode = TileMode.Clamp)
            val decal = rememberLayerBackdrop(tileMode = TileMode.Decal)
            val combined = rememberCombinedBackdrop(clamped, decal)
            output = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize()) {
                FiniteStripeSource(clamped)
                FiniteStripeSource(decal)
                Consumer(combined, output, Modifier) {
                    progressiveBlur(16f, BackdropEdge.Top)
                }
            }
        }
        waitForIdle()

        assertSolidAlpha(output, 1f)
        assertTransparentHole(output)
    }

    @Test
    fun exportedBackdropCanBeSampledAgainWithoutTreatingSamplingPaddingAsSource() =
        runComposeUiTest {
            lateinit var firstOutput: LayerBackdrop
            lateinit var secondOutput: LayerBackdrop
            setContent {
                SourceBoundaryScene(alpha = 1f, tileMode = TileMode.Clamp) { source ->
                    firstOutput = rememberLayerBackdrop()
                    secondOutput = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Consumer(source, firstOutput, Modifier) {
                            progressiveBlur(16f, BackdropEdge.Top)
                        }
                        Consumer(firstOutput, secondOutput, Modifier.offset(x = 0.dp, y = 0.dp)) {
                            progressiveBlur(16f, BackdropEdge.Top)
                        }
                    }
                }
            }
            waitForIdle()

            assertSolidAlpha(secondOutput, 1f)
            assertTransparentHole(secondOutput)
        }

    @Composable
    private fun SourceBoundaryScene(
        alpha: Float,
        tileMode: TileMode,
        content: @Composable (LayerBackdrop) -> Unit,
    ) {
        val source = rememberLayerBackdrop(tileMode = tileMode)
        Box(Modifier.size(width = 264.dp, height = 128.dp).layerBackdrop(source)) {
            Canvas(Modifier.fillMaxSize()) {
                // Alternating red and blue bands make edge extension and coordinate mapping
                // visible. A transparent interior stripe distinguishes real source alpha from
                // transparent exterior pixels synthesized by a tile mode.
                val stripeWidth = size.width / 8f
                for (index in 0 until 8) {
                    if (index in 2..3) continue
                    drawRect(
                        color =
                            if (index % 2 == 0) Color.Red.copy(alpha) else Color.Blue.copy(alpha),
                        topLeft = Offset(index * stripeWidth, 0f),
                        size = androidx.compose.ui.geometry.Size(stripeWidth, size.height),
                    )
                }
            }
        }
        content(source)
    }

    @Composable
    private fun FiniteStripeSource(source: LayerBackdrop) {
        Box(Modifier.size(width = 264.dp, height = 128.dp).layerBackdrop(source)) {
            Canvas(Modifier.fillMaxSize()) {
                val stripeWidth = size.width / 8f
                for (index in 0 until 8) {
                    if (index in 2..3) continue
                    drawRect(
                        color = if (index % 2 == 0) Color.Red else Color.Blue,
                        topLeft = Offset(index * stripeWidth, 0f),
                        size = androidx.compose.ui.geometry.Size(stripeWidth, size.height),
                    )
                }
            }
        }
    }

    @Composable
    private fun Consumer(
        source: Backdrop,
        output: LayerBackdrop,
        modifier: Modifier,
        width: androidx.compose.ui.unit.Dp = 160.dp,
        effects: BackdropEffectScope.() -> Unit,
    ) {
        Box(
            modifier
                .size(width = width, height = 64.dp)
                .drawBackdrop(
                    backdrop = source,
                    shape = { RoundedCornerShape(4.dp) },
                    effects = effects,
                    insets = { BackdropInsets.all(8.dp) },
                    highlight = { top.ltfan.backdrop.highlight.Highlight.None },
                    shadow = { top.ltfan.backdrop.shadow.Shadow.None },
                    innerShadow = { top.ltfan.backdrop.shadow.InnerShadow.None },
                    exportedBackdrop = output,
                )
        )
    }

    private fun assertSolidAlpha(layer: LayerBackdrop, expected: Float) {
        val pixels = export(layer).toPixelMap()
        val surfaceRow = layer.layerOffset.y.toInt()
        val surfaceColumn = layer.layerOffset.x.toInt() + 18
        for (row in surfaceRow..surfaceRow + 19) {
            val pixel = pixels[surfaceColumn, row].alpha
            assertTrue(
                pixel in (expected - 0.025f)..(expected + 0.025f),
                "Expected source alpha $expected at row $row to survive clamped sampling, got $pixel",
            )
        }
    }

    private fun assertTransparentHole(layer: LayerBackdrop) {
        val pixels = export(layer).toPixelMap()
        val pixel =
            pixels[layer.layerOffset.x.toInt() + 107, layer.layerOffset.y.toInt() + 19].alpha
        assertTrue(
            pixel < 0.05f,
            "An interior transparent source stripe must remain transparent: $pixel",
        )
    }

    private fun export(layer: LayerBackdrop) = runBlocking {
        layer.graphicsLayer.toImageBitmap()
    }
}
