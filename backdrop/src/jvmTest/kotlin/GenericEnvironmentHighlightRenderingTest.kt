package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.EnvironmentHighlight
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class GenericEnvironmentHighlightRenderingTest {
    @Test
    fun cubicGenericOutlineReceivesDirectionalEnvironmentRim() {
        val withoutEnvironment = capture(SquircleShape, false, Source(168, 92, 16, 16))
        val withEnvironment = capture(SquircleShape, true, Source(168, 92, 16, 16))

        assertLitDifference(withoutEnvironment, withEnvironment, x = 158, y = 100)
        assertUnchanged(withoutEnvironment, withEnvironment, x = 100)
        assertUnchanged(withoutEnvironment, withEnvironment, x = 164)
    }

    @Test
    fun concaveGenericOutlineLightsItsInwardFacingNotchEdge() {
        val source = Source(130, 88, 14, 24)
        val withoutEnvironment = capture(ConcaveNotchShape, false, source)
        val withEnvironment = capture(ConcaveNotchShape, true, source)

        assertLitDifference(withoutEnvironment, withEnvironment, x = 118, y = 100)
        assertUnchanged(withoutEnvironment, withEnvironment, x = 65)
    }

    @Test
    fun evenOddGenericOutlineLightsHoleBoundaryWithoutFillingTheHole() {
        val source = Source(90, 92, 16, 16)
        val withoutEnvironment = capture(EvenOddHoleShape, false, source)
        val withEnvironment = capture(EvenOddHoleShape, true, source)

        assertLitDifference(withoutEnvironment, withEnvironment, x = 78, y = 100)
        assertUnchanged(withoutEnvironment, withEnvironment, x = 100)
        assertEquals(withoutEnvironment[100, 100].alpha, withEnvironment[100, 100].alpha, 0.02f)
    }

    @Test
    fun ellipticalRoundedOutlineReceivesLightAlongItsCurvedCorner() {
        val source = Source(158, 28, 20, 18)
        val withoutEnvironment = capture(EllipticalRoundedShape, false, source)
        val withEnvironment = capture(EllipticalRoundedShape, true, source)

        assertLitDifference(withoutEnvironment, withEnvironment, x = 154, y = 48)
        assertUnchanged(withoutEnvironment, withEnvironment, x = 100)
    }

    @Test
    fun diagonalTriangleBoundaryUsesItsOutwardNormalAtPixelCenter() {
        val source = Source(80, 108, 16, 16)
        val withoutEnvironment = capture(DiagonalTriangleShape, false, source)
        val withEnvironment = capture(DiagonalTriangleShape, true, source)

        assertLitDifference(withoutEnvironment, withEnvironment, x = 100, y = 100)
    }

    @Test
    fun genericShapeAndSizeChangesRefreshTheEnvironmentBoundary() = runComposeUiTest {
        val shape = mutableStateOf<Shape>(SquircleShape)
        val size = mutableStateOf(100)
        val source = mutableStateOf(Source(148, 80, 16, 24))
        setContent {
            val rawBackdrop = rememberLayerBackdrop()
            Box(Modifier.size(240.dp, 200.dp).background(Color.Blue).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.Black)
                        val current = source.value
                        drawRect(
                            Color.Red,
                            Offset(current.left.dp.toPx(), current.top.dp.toPx()),
                            Size(current.width.dp.toPx(), current.height.dp.toPx()),
                        )
                    }
                }
                Box(
                    Modifier.offset(40.dp, 40.dp)
                        .size(size.value.dp)
                        .drawBackdrop(
                            rawBackdrop,
                            { shape.value },
                            highlight = {
                                Highlight(
                                    width = 3.dp,
                                    blurRadius = 0.dp,
                                    style = HighlightStyle.None,
                                    environment = environmentHighlight(),
                                )
                            },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        waitForIdle()
        val first = onNodeWithTag("root").captureToImage().toPixelMap()
        val rimY = (78..91).maxBy { first[139, it].red }
        assertTrue(first[139, rimY].red > 0.1f, "the initial size has a lit right edge")

        size.value = 120
        shape.value = ConcaveNotchShape
        source.value = Source(130, 88, 4, 24)
        waitForIdle()
        val changed = onNodeWithTag("root").captureToImage().toPixelMap()
        assertTrue(changed[119, 100].red > 0.1f, "the updated notch edge receives light")
        assertTrue(
            changed[139, rimY].red < first[139, rimY].red - 0.08f,
            "the previous size boundary no longer retains its environment rim",
        )
    }

    private fun capture(
        shape: Shape,
        enabled: Boolean,
        source: Source,
    ): androidx.compose.ui.graphics.PixelMap {
        lateinit var pixels: androidx.compose.ui.graphics.PixelMap
        runComposeUiTest {
            val sourceState = mutableStateOf(source)
            setContent {
                val rawBackdrop = rememberLayerBackdrop()
                Box(Modifier.size(240.dp, 200.dp).background(Color.Blue).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(rawBackdrop)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color.Black)
                            val current = sourceState.value
                            drawRect(
                                Color.Red,
                                Offset(current.left.dp.toPx(), current.top.dp.toPx()),
                                Size(current.width.dp.toPx(), current.height.dp.toPx()),
                            )
                        }
                    }
                    Box(
                        Modifier.offset(40.dp, 40.dp)
                            .size(120.dp)
                            .drawBackdrop(
                                rawBackdrop,
                                { shape },
                                highlight = {
                                    Highlight(
                                        width = 3.dp,
                                        blurRadius = 0.dp,
                                        style = HighlightStyle.None,
                                        environment = if (enabled) environmentHighlight() else null,
                                    )
                                },
                                shadow = { Shadow.None },
                                innerShadow = { InnerShadow.None },
                            )
                    )
                }
            }
            waitForIdle()
            pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        }
        return pixels
    }

    private fun environmentHighlight() =
        EnvironmentHighlight(
            sampleDistance = 20.dp,
            strength = 1f,
            threshold = 0f,
            blendMode = BlendMode.Plus,
        )

    private fun assertLitDifference(
        without: androidx.compose.ui.graphics.PixelMap,
        with: androidx.compose.ui.graphics.PixelMap,
        x: Int,
        y: Int,
    ) {
        assertTrue(
            with[x, y].red > without[x, y].red + 0.1f,
            "environment light should increase red at boundary ($x, $y): ${without[x, y]} -> ${with[x, y]}",
        )
    }

    private fun assertUnchanged(
        without: androidx.compose.ui.graphics.PixelMap,
        with: androidx.compose.ui.graphics.PixelMap,
        x: Int,
    ) {
        val y = 100
        assertEquals(without[x, y].red, with[x, y].red, 0.03f)
        assertEquals(without[x, y].green, with[x, y].green, 0.03f)
        assertEquals(without[x, y].blue, with[x, y].blue, 0.03f)
    }
}

private data class Source(val left: Int, val top: Int, val width: Int, val height: Int)

private object SquircleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path =
            Path().apply {
                moveTo(size.width * 0.45f, 0f)
                cubicTo(
                    size.width * 0.75f,
                    0f,
                    size.width,
                    size.height * 0.25f,
                    size.width,
                    size.height * 0.45f,
                )
                cubicTo(
                    size.width,
                    size.height * 0.55f,
                    size.width * 0.75f,
                    size.height,
                    size.width * 0.55f,
                    size.height,
                )
                cubicTo(
                    size.width * 0.25f,
                    size.height,
                    0f,
                    size.height * 0.75f,
                    0f,
                    size.height * 0.55f,
                )
                cubicTo(
                    0f,
                    size.height * 0.25f,
                    size.width * 0.25f,
                    0f,
                    size.width * 0.45f,
                    0f,
                )
                close()
            }
        return Outline.Generic(path)
    }
}

private object DiagonalTriangleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                close()
            }
        return Outline.Generic(path)
    }
}

private object ConcaveNotchShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Generic(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height * 0.3f)
                lineTo(size.width * 0.67f, size.height * 0.3f)
                lineTo(size.width * 0.67f, size.height * 0.7f)
                lineTo(size.width, size.height * 0.7f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        )
}

private object EvenOddHoleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Generic(
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addRect(
                    androidx.compose.ui.geometry.Rect(
                        size.width * 0.33f,
                        size.height * 0.33f,
                        size.width * 0.67f,
                        size.height * 0.67f,
                    )
                )
            }
        )
}

private object EllipticalRoundedShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Rounded(
            RoundRect(
                0f,
                0f,
                size.width,
                size.height,
                CornerRadius(size.width * 0.3f, size.height * 0.15f),
                CornerRadius(size.width * 0.3f, size.height * 0.15f),
                CornerRadius(size.width * 0.3f, size.height * 0.15f),
                CornerRadius(size.width * 0.3f, size.height * 0.15f),
            )
        )
}
