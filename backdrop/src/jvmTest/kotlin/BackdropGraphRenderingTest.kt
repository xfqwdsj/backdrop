package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.internal.BackdropGraphRenderer

class BackdropGraphRenderingTest {
    @Test
    fun unusedTransformDoesNotResolveOrAllocateResources() = runComposeUiTest {
        val scope = BackdropRenderScope(1f, 1f, Size(80f, 80f), RectangleShape, LayoutDirection.Ltr)
        with(scope) {
            image {
                source()
                transform(Matrix(FloatArray(16)))
            }
        }
        val renderer = BackdropGraphRenderer {
            error("Unused branches must not allocate rendering resources")
        }
        renderer.resolve(scope, Rect(0f, 0f, 80f, 80f))
        assertEquals(BackdropExtension.None, renderer.extension)
    }

    @Test
    fun clearIsIsolatedAndMixIsPremultiplied() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Green) }
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val a = image { paint { drawRect(Color.Red) } }
                            val b = image { paint { drawRect(Color.Blue) } }
                            mix(a, b, .5f)
                            clear(Rect(0f, 0f, 20f, 80f))
                            val cut = current
                            replace(input)
                            draw(cut)
                        },
                    ),
            )
        }
        val p = onNodeWithTag("scene").captureToImage().toPixelMap()
        assertTrue(p[10, 40].green > .98f, "cleared result must reveal source: ${p[10,40]}")
        assertTrue(
            abs(p[60, 40].red - .5f) < .02f && abs(p[60, 40].blue - .5f) < .02f,
            "mix: ${p[60,40]}",
        )
    }

    @Test
    fun groupAlphaLeavesContentAndRecoversWithoutRecordingAtZero() = runComposeUiTest {
        val alpha = mutableFloatStateOf(1f)
        var records = 0
        setContent {
            val source = rememberCanvasBackdrop {
                records++
                drawRect(Color.Red)
            }
            Box(Modifier.size(80.dp).background(Color.Black).testTag("scene")) {
                Box(
                    Modifier.size(80.dp)
                        .renderBackdrop(
                            source,
                            { RectangleShape },
                            backgroundAlpha = { alpha.floatValue },
                            render = {
                                fill(Color.Blue.copy(alpha = .5f))
                            },
                        ),
                ) {
                    Box(Modifier.size(20.dp).background(Color.Green))
                }
            }
        }
        alpha.floatValue = .5f
        waitForIdle()
        val p = onNodeWithTag("scene").captureToImage().toPixelMap()
        assertTrue(p[10, 10].green > .98f)
        assertTrue(
            abs(p[40, 40].red - .25f) < .02f && abs(p[40, 40].blue - .25f) < .02f,
            "group opacity: ${p[40,40]}",
        )
        alpha.floatValue = 0f
        waitForIdle()
        val before = records
        val invisible = onNodeWithTag("scene").captureToImage().toPixelMap()
        assertTrue(invisible[40, 40].red < .01f && invisible[10, 10].green > .98f)
        assertEquals(before, records, "invisible source must not be recorded")
        alpha.floatValue = 1f
        waitForIdle()
        assertTrue(onNodeWithTag("scene").captureToImage().toPixelMap()[40, 40].blue > .45f)
    }

    @Test
    fun branchesAndGeneratedLayerDslPreserveIdentityAlpha() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red.copy(alpha = .4f)) }
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val identity = image {
                                source()
                                effects { blur(0f) }
                            }
                            image {
                                source()
                                effects { error("unused branch must not execute") }
                            }
                            layers { backdropLayer(identity) }
                        },
                    ),
            )
        }
        val p = onNodeWithTag("scene").captureToImage().toPixelMap()[40, 40]
        assertTrue(abs(p.alpha - .4f) < .02f, "identity must not double-composite: $p")
    }

    @Test
    fun spatialMasksAndForegroundHaveExplicitScope() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red) }
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val coverage = image {
                                paint {
                                    drawRect(
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, Color.White),
                                        ),
                                    )
                                }
                            }
                            group {
                                replace(image { paint { drawRect(Color.Blue) } })
                                mask(coverage)
                            }
                            foreground { fill(Color.Green.copy(alpha = .5f)) }
                        },
                        backgroundAlpha = { 0f },
                    ),
            )
        }
        val p = onNodeWithTag("scene").captureToImage().toPixelMap()[40, 40]
        assertTrue(p.green > .98f && abs(p.alpha - .5f) < .02f, "foreground is independent: $p")
    }

    @Test
    fun verticalWeightsMixIndependentBlurBranches() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop {
                var x = Math.floorDiv(samplingBounds.left.toInt(), 8) * 8 - 8
                while (x < samplingBounds.right + 8) {
                    drawRect(
                        if (Math.floorDiv(x, 8) % 2 == 0) Color.White else Color.Black,
                        Offset(x.toFloat(), samplingBounds.top),
                        Size(8f, samplingBounds.height),
                    )
                    x += 8
                }
            }
            Box(
                Modifier.size(80.dp)
                    .testTag("progressive")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val sharp = image {
                                source()
                                clipSurface()
                            }
                            val blurred = image {
                                source()
                                effects { blur(16f) }
                                clipSurface()
                            }
                            val weight = image {
                                paint {
                                    drawRect(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.White),
                                        ),
                                    )
                                }
                            }
                            mix(sharp, blurred, weight)
                        },
                    ),
            )
        }
        val p = onNodeWithTag("progressive").captureToImage().toPixelMap()
        val topContrast = abs(p[4, 4].red - p[12, 4].red)
        val bottomContrast = abs(p[4, 75].red - p[12, 75].red)
        assertTrue(topContrast > .8f, "Top retains the independent sharp branch: $topContrast")
        assertTrue(
            bottomContrast < .15f,
            "Bottom selects the independently blurred branch: $bottomContrast",
        )
        assertTrue(p[4, 40].alpha > .98f, "Mixing opaque branches preserves opacity")
    }

    @Test
    fun scalarMixPreservesPremultipliedEndpointsAndMidpoint() = runComposeUiTest {
        val weight = mutableFloatStateOf(0f)
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(
                Modifier.size(80.dp)
                    .testTag("mixed")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val red = image { paint { drawRect(Color.Red.copy(alpha = .4f)) } }
                            val blue = image { paint { drawRect(Color.Blue.copy(alpha = .8f)) } }
                            mix(red, blue, weight.floatValue)
                        },
                    ),
            )
        }
        for (w in listOf(0f, .5f, 1f)) {
            weight.floatValue = w
            waitForIdle()
            val p = onNodeWithTag("mixed").captureToImage().toPixelMap()[40, 40]
            val alpha = .4f * (1f - w) + .8f * w
            assertTrue(abs(p.alpha - alpha) < .02f, "Weighted alpha at $w: $p")
            assertTrue(abs(p.red - .4f * (1f - w) / alpha) < .02f, "Premultiplied red at $w: $p")
            assertTrue(abs(p.blue - .8f * w / alpha) < .02f, "Premultiplied blue at $w: $p")
        }
    }

    @Test
    fun maskingBeforeAndAfterBlurHaveDifferentBoundaries() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop {
                drawRect(Color.Red, samplingBounds.topLeft, samplingBounds.size)
            }
            Column {
                for (before in listOf(false, true)) {
                    Box(
                        Modifier.size(80.dp)
                            .testTag(if (before) "before" else "after")
                            .renderBackdrop(
                                source,
                                { RectangleShape },
                                render = {
                                    val weight = image {
                                        paint {
                                            drawRect(Color.White, size = Size(40f, 80f))
                                        }
                                    }
                                    replace(
                                        if (before)
                                            image {
                                                source()
                                                mask(weight)
                                                effects { blur(3f) }
                                            }
                                        else
                                            image {
                                                source()
                                                effects { blur(3f) }
                                                mask(weight)
                                            },
                                    )
                                },
                            ),
                    )
                }
            }
        }
        val before = onNodeWithTag("before").captureToImage().toPixelMap()
        val after = onNodeWithTag("after").captureToImage().toPixelMap()
        assertTrue(before[42, 40].alpha > .05f, "Blur spreads masked coverage")
        assertTrue(after[42, 40].alpha < .01f, "Final mask clips processed coverage")
        assertTrue(before[20, 40].red > .98f && after[20, 40].red > .98f)
    }

    @Test
    fun explicitExportSelectsItsOwnImageAndExcludesForeground() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop {}
            val exported = rememberLayerBackdrop()
            Column {
                Box(
                    Modifier.size(80.dp)
                        .testTag("local")
                        .renderBackdrop(
                            source,
                            { RectangleShape },
                            exportedBackdrop = exported,
                            render = {
                                group { paint { drawRect(Color.Red) } }
                                export(image { paint { drawRect(Color.Blue) } })
                                foreground { fill(Color.Green) }
                            },
                        ),
                )
                Box(
                    Modifier.size(80.dp)
                        .testTag("exported")
                        .renderBackdrop(exported, { RectangleShape }),
                )
            }
        }
        val local = onNodeWithTag("local").captureToImage().toPixelMap()[40, 40]
        val exported = onNodeWithTag("exported").captureToImage().toPixelMap()[40, 40]
        assertTrue(local.green > .98f, "Local foreground draws above its red background")
        assertTrue(
            exported.blue > .98f && exported.green < .01f,
            "Export uses the selected blue image",
        )
    }

    @Test
    fun imagePaintAppendsWhileDrawWithContentCanReplaceInput() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop {}
            Column {
                for (mode in listOf("original", "painted", "replaced")) {
                    Box(
                        Modifier.size(80.dp)
                            .testTag(mode)
                            .renderBackdrop(
                                source,
                                { RectangleShape },
                                render = {
                                    val original = image {
                                        paint { drawRect(Color.Red.copy(alpha = .4f)) }
                                    }
                                    val painted = image {
                                        replace(original)
                                        paint {
                                            drawRect(Color.Blue.copy(alpha = .5f))
                                        }
                                    }
                                    val replaced = image {
                                        replace(original)
                                        drawWithContent { _, _ ->
                                            drawRect(Color.Green.copy(alpha = .6f))
                                        }
                                    }
                                    replace(
                                        when (mode) {
                                            "painted" -> painted
                                            "replaced" -> replaced
                                            else -> original
                                        },
                                    )
                                },
                            ),
                    )
                }
            }
        }
        val original = onNodeWithTag("original").captureToImage().toPixelMap()[40, 40]
        val painted = onNodeWithTag("painted").captureToImage().toPixelMap()[40, 40]
        val replaced = onNodeWithTag("replaced").captureToImage().toPixelMap()[40, 40]
        assertTrue(
            abs(original.alpha - .4f) < .02f && original.red > .98f,
            "Painting preserves the referenced input",
        )
        assertTrue(abs(painted.alpha - .7f) < .02f, "Paint composites input and new drawing")
        assertTrue(
            abs(painted.red - 2f / 7f) < .02f && abs(painted.blue - 5f / 7f) < .02f,
            "Paint uses SrcOver premultiplied colors",
        )
        assertTrue(
            abs(replaced.alpha - .6f) < .02f && replaced.green > .98f && replaced.red < .01f,
            "Omitting drawContent replaces input drawing",
        )
    }

    @Test
    fun imagePaintSurfaceClipsOnlyTheAppendedDrawing() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(
                Modifier.size(80.dp)
                    .testTag("painted")
                    .renderBackdrop(
                        source,
                        { RoundedCornerShape(20.dp) },
                        render = {
                            group {
                                replace(image { paint { drawRect(Color.Blue) } })
                                paintSurface { drawRect(Color.Green) }
                            }
                        },
                    ),
            )
        }
        val p = onNodeWithTag("painted").captureToImage().toPixelMap()
        assertTrue(
            p[0, 0].blue > .98f && p[0, 0].green < .01f,
            "The input retains pixels outside the surface outline",
        )
        assertTrue(
            p[40, 40].green > .98f && p[40, 40].blue < .01f,
            "The appended paint fills the surface interior",
        )
    }
}
