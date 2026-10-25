package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.shadow

class UnifiedDeclarationRenderingTest {
    @Test
    fun measuredGraphBoundsExistBeforeItsParentFirstPlacesTheSurface() = runRenderTest {
        var declarations = 0
        var readyAtFirstPlacement: Boolean? = null
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            Box(
                Modifier.layout { measurable, constraints ->
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) {
                            if (readyAtFirstPlacement == null)
                                readyAtFirstPlacement = declarations > 0
                            child.place(0, 0)
                        }
                    }
                    .size(40.dp)
                    .drawBackdrop(source, { RectangleShape }) {
                        declarations++
                        shadow(Shadow.Default)
                    },
            )
        }
        waitForIdle()
        assertEquals(true, readyAtFirstPlacement)
    }

    @Test
    fun genericHighlightRetainsItsOutlineAcrossDrawsAndTracksShapeReads() = runRenderTest {
        val radius = mutableFloatStateOf(2f)
        val edge = mutableFloatStateOf(5f)
        var outlines = 0
        val shape =
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline {
                    outlines++
                    return Outline.Generic(
                        Path().apply {
                            addRect(Rect(edge.floatValue, 0f, size.width, size.height))
                        },
                    )
                }
            }
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            Box(
                Modifier.size(40.dp).testTag("cachedOutline").drawBackdrop(source, { shape }) {
                    foreground {
                        paint {
                            drawRect(Color.Transparent, size = Size(radius.floatValue, 1f))
                        }
                        highlight(
                            Highlight.Config(colorResponse = HighlightColorResponse(amount = 0f)),
                        )
                    }
                },
            )
        }
        waitForIdle()
        onNodeWithTag("cachedOutline").captureToImage()
        val initial = outlines
        runOnIdle { radius.floatValue = 8f }
        waitForIdle()
        onNodeWithTag("cachedOutline").captureToImage()
        assertEquals(initial, outlines)
        runOnIdle { edge.floatValue = 10f }
        waitForIdle()
        onNodeWithTag("cachedOutline").captureToImage()
        assertTrue(outlines > initial)
    }

    @Test
    fun opticalParametersRetainTheDeclarationWhileStructuralReadsRebuildIt() = runRenderTest {
        val radius = mutableFloatStateOf(2f)
        val fill = mutableStateOf(false)
        var declarations = 0
        var resolutions = 0
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            Box(
                Modifier.size(40.dp).testTag("observations").drawBackdrop(
                    source,
                    { RectangleShape },
                ) {
                    declarations++
                    effects {
                        resolutions++
                        blur(radius.floatValue)
                    }
                    if (fill.value) fill(Color.Red)
                },
            )
        }
        waitForIdle()
        val initialDeclarations = declarations
        val initialResolutions = resolutions
        runOnIdle { radius.floatValue = 8f }
        waitForIdle()
        assertEquals(initialDeclarations, declarations)
        assertTrue(resolutions > initialResolutions)
        runOnIdle { fill.value = true }
        waitForIdle()
        assertTrue(declarations > initialDeclarations)
        assertEquals(Color.Red, onNodeWithTag("observations").captureToImage().toPixelMap()[20, 20])
    }

    @Test
    fun explicitLayerContainsClearDrawingAndSupportsNestedSurfaceOperations() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red) }
            Box(
                Modifier.size(40.dp).testTag("unified").drawBackdrop(source, { RectangleShape }) {
                    effects { blur(0f) }
                    fill(Color.Red)
                    val front = layer {
                        fill(Color.Blue)
                        val nested = layer {
                            surface {
                                effects {}
                                fill(Color.Green)
                                highlight(Highlight.None)
                                shadow(Shadow.None)
                                innerShadow(InnerShadow.None)
                                interactionLight(
                                    InteractionLighting.None,
                                )
                            }
                            clip(
                                Path().apply {
                                    addRect(Rect(0f, 0f, 10f, 40f))
                                },
                            )
                        }
                        draw(nested)
                        paint(CompositingStrategy.Auto) {
                            drawRect(
                                Color.Transparent,
                                size = Size(20f, 40f),
                                blendMode = BlendMode.Clear,
                            )
                        }
                        highlight(Highlight.None)
                    }
                    draw(front)
                },
            )
        }
        val pixels = onNodeWithTag("unified").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[5, 20])
        assertEquals(Color.Red, pixels[15, 20])
        assertEquals(Color.Blue, pixels[30, 20])
    }
}
