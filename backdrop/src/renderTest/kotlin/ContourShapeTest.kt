package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.shape.CapsuleShape
import top.ltfan.backdrop.shape.ContinuousRoundedRectangleShape
import top.ltfan.backdrop.shape.ContourSegment
import top.ltfan.backdrop.shape.ExperimentalBackdropShapeApi
import top.ltfan.backdrop.shape.toPath

@OptIn(ExperimentalBackdropShapeApi::class)
class ContourShapeTest : PlatformGraphicsTest() {
    @Test
    fun bundledCapsuleProvidesOneClosedStructuredContour() {
        val shape = CapsuleShape()
        val contour = shape.createContour(Size(120f, 80f), LayoutDirection.Ltr, Density(1f))
        val loop = contour.loops.single()
        assertTrue(loop.segments.size >= 6)
        assertEquals(loop.segments.first().start, loop.segments.last().end)
        assertTrue(loop.segments.any { it is ContourSegment.Arc })
        assertTrue(
            loop.segments.any { it is ContourSegment.Line || it is ContourSegment.Cubic },
        )
        val outline = shape.createOutline(Size(120f, 80f), LayoutDirection.Ltr, Density(1f))
        assertIs<Outline.Generic>(outline)
        val bounds = contour.toPath().getBounds()
        assertTrue(bounds.left >= -0.01f && bounds.top >= -0.01f)
        assertTrue(bounds.right <= 120.01f && bounds.bottom <= 80.01f)
    }

    @Test
    fun generatedOutlineAndHighlightUseTheStructuredShape() = runRenderTest {
        setContent {
            Box(
                Modifier.size(120.dp)
                    .testTag("contour-shape")
                    .drawBackdrop(
                        backdrop = rememberCanvasBackdrop { drawRect(Color.Blue) },
                        shape = { CapsuleShape() },
                        render = {
                            source()
                            highlight(
                                Highlight(
                                    width = 8.dp,
                                    baseBrightness = 1f,
                                    environmentGain = 0f,
                                ),
                            )
                        },
                    ),
            )
        }
        waitForIdle()
        val pixels = onNodeWithTag("contour-shape").captureToImage().toPixelMap()
        assertTrue(pixels[60, 1].red > .5f)
        assertTrue(pixels[60, 60].red < .1f)
    }

    @Test
    fun structuredAndGenericContoursMatchAcrossRtlCornersAndResize() = runRenderTest {
        val width = mutableFloatStateOf(120f)
        val height = mutableFloatStateOf(80f)
        val structured = mutableStateOf(true)
        val layoutDirection = mutableStateOf(LayoutDirection.Ltr)
        setContent {
            val contour = remember {
                ContinuousRoundedRectangleShape(
                    topStart = CornerSize(6.dp),
                    topEnd = CornerSize(24.dp),
                    bottomEnd = CornerSize(12.dp),
                    bottomStart = CornerSize(32.dp),
                )
            }
            val generic =
                remember(contour) {
                    object : Shape {
                        override fun createOutline(
                            size: Size,
                            layoutDirection: LayoutDirection,
                            density: Density,
                        ): Outline = contour.createOutline(size, layoutDirection, density)
                    }
                }
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection.value) {
                val source = rememberCanvasBackdrop {
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Red, Color.Blue),
                            Offset.Zero,
                            Offset(120f, 120f),
                        ),
                    )
                }
                Box(Modifier.size(160.dp).testTag("contour-equivalence")) {
                    Box(
                        Modifier.offset(20.dp, 20.dp)
                            .size(width.floatValue.dp, height.floatValue.dp)
                            .drawBackdrop(
                                backdrop = source,
                                shape = { if (structured.value) contour else generic },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)
                                        highlight(
                                            Highlight(
                                                width = 8.dp,
                                                baseBrightness = .6f,
                                                environmentGain = 0f,
                                            ),
                                        )
                                    }
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()

        val scenarios =
            listOf(
                Triple(120f, 80f, LayoutDirection.Ltr),
                Triple(120f, 80f, LayoutDirection.Rtl),
                Triple(80f, 120f, LayoutDirection.Rtl),
            )
        for ((targetWidth, targetHeight, targetDirection) in scenarios) {
            runOnIdle {
                width.floatValue = targetWidth
                height.floatValue = targetHeight
                layoutDirection.value = targetDirection
                structured.value = true
            }
            waitForIdle()
            val contourPixels = onNodeWithTag("contour-equivalence").captureToImage().toPixelMap()

            runOnIdle { structured.value = false }
            waitForIdle()
            val genericPixels = onNodeWithTag("contour-equivalence").captureToImage().toPixelMap()

            for (y in 20 until 140) for (x in 20 until 140) {
                val a = contourPixels[x, y]
                val e = genericPixels[x, y]
                assertTrue(
                    kotlin.math.abs(a.red * a.alpha - e.red * e.alpha) <= .06f &&
                        kotlin.math.abs(a.green * a.alpha - e.green * e.alpha) <= .06f &&
                        kotlin.math.abs(a.blue * a.alpha - e.blue * e.alpha) <= .06f &&
                        kotlin.math.abs(a.alpha - e.alpha) <= .02f,
                    "size=${targetWidth}x$targetHeight, direction=$targetDirection, " +
                        "pixel=($x,$y), structured=$a, generic=$e",
                )
            }
        }
    }
}
