package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur

class BackdropGraphRenderingTest {
    @Test
    fun repeatedResolutionRefreshesTransformSamplingAndRejectsInvalidMatrices() = runRenderTest {
        val bounds = Rect(0f, 0f, 80f, 80f)
        val renderer = BackdropRenderer { error("Resolution does not record layers") }
        fun declaration(matrix: Matrix, extraNode: Boolean = false): BackdropRenderScope =
            BackdropRenderScope(1f, 1f, bounds.size, RectangleShape, LayoutDirection.Ltr).apply {
                source()
                if (extraNode) transform(Matrix())
                transform(matrix)
            }
        fun checkResolution(scope: BackdropRenderScope) {
            val reference = BackdropRenderer { error("Resolution does not record layers") }
            reference.use {
                reference.resolve(scope, bounds)
                renderer.resolve(scope, bounds)
                assertEquals(reference.visible, renderer.visible)
                assertEquals(reference.extension, renderer.extension)
            }
        }
        renderer.use {
            val scope = declaration(Matrix().apply { translate(12f, -7f) })
            checkResolution(scope)
            val transform = scope.graph.filterIsInstance<RenderImageNode.Transform>().single()
            transform.matrix.reset()
            transform.matrix.scale(.5f, 2f)
            transform.matrix.translate(-25f, 9f)
            checkResolution(scope)
            transform.matrix.scale(0f, 1f)
            assertFailsWith<IllegalArgumentException> { renderer.resolve(scope, bounds) }
            transform.matrix.reset()
            checkResolution(scope)
            checkResolution(declaration(Matrix().apply { rotateZ(35f) }, extraNode = true))
            checkResolution(declaration(Matrix().apply { translate(-40f, 17f) }))
            renderer.close()
            checkResolution(scope)
        }
    }

    @Test
    fun removedOutputsReleaseTheirDrawingObservations() = runRenderTest {
        val outputs = mutableStateOf(true)
        val color = mutableStateOf(Color.Red)
        var sourceCalls = 0
        setContent {
            val source = rememberCanvasBackdrop {
                sourceCalls++
                drawRect(Color.Blue)
            }
            val exported = rememberLayerBackdrop()
            Box(
                Modifier.size(80.dp)
                    .testTag("removed-outputs")
                    .drawBackdrop(
                        source,
                        { RectangleShape },
                        exportedBackdrop = exported,
                        render = {
                            if (outputs.value) {
                                foreground { paint { drawRect(color.value) } }
                                export(image { paint { drawRect(color.value) } })
                            }
                        },
                    ),
            )
        }
        val node = onNodeWithTag("removed-outputs")
        assertEquals(Color.Red, node.captureToImage().toPixelMap()[40, 40])
        runOnIdle { outputs.value = false }
        assertEquals(Color.Blue, node.captureToImage().toPixelMap()[40, 40])
        val recorded = sourceCalls
        runOnIdle { color.value = Color.Green }
        assertEquals(Color.Blue, node.captureToImage().toPixelMap()[40, 40])
        assertEquals(recorded, sourceCalls, "Removed output observations invalidated the graph")
        runOnIdle { outputs.value = true }
        assertEquals(Color.Green, node.captureToImage().toPixelMap()[40, 40])
    }

    @Test
    fun alphaOnlyFramesRetainDrawingDependenciesAcrossOutputs() = runRenderTest {
        val alpha = mutableFloatStateOf(.5f)
        val sourceColor = mutableStateOf(Color.Red)
        val paintColor = mutableStateOf(Color.Blue.copy(alpha = .5f))
        val foregroundColor = mutableStateOf(Color.Magenta)
        val exportColor = mutableStateOf(Color.Cyan)
        var sourceCalls = 0
        var paintCalls = 0
        var foregroundCalls = 0
        var exportCalls = 0
        setContent {
            val source = rememberCanvasBackdrop {
                sourceCalls++
                drawRect(sourceColor.value)
            }
            val exported = rememberLayerBackdrop()
            Column {
                Box(
                    Modifier.size(80.dp)
                        .testTag("retained-local")
                        .drawBackdrop(
                            source,
                            { RectangleShape },
                            backgroundAlpha = { alpha.floatValue },
                            exportedBackdrop = exported,
                            render = {
                                paint {
                                    paintCalls++
                                    drawRect(paintColor.value)
                                }
                                foreground {
                                    paint {
                                        foregroundCalls++
                                        drawRect(foregroundColor.value, size = Size(12f, 12f))
                                    }
                                }
                                export(
                                    image {
                                        paint {
                                            exportCalls++
                                            drawRect(exportColor.value)
                                        }
                                    },
                                )
                            },
                        ),
                )
                Box(
                    Modifier.size(80.dp)
                        .testTag("retained-export")
                        .drawBackdrop(exported, { RectangleShape }),
                )
            }
        }
        fun assertColor(actual: Color, expected: Color) {
            for ((a, e) in
                listOf(
                    actual.red to expected.red,
                    actual.green to expected.green,
                    actual.blue to expected.blue,
                    actual.alpha to expected.alpha,
                )) assertTrue(abs(a - e) < .02f, "$actual != $expected")
        }
        fun calls() = listOf(sourceCalls, paintCalls, foregroundCalls, exportCalls)
        fun checkPixels() {
            val local = onNodeWithTag("retained-local").captureToImage().toPixelMap()
            assertColor(local[4, 4], foregroundColor.value)
            if (alpha.floatValue > 0f)
                assertColor(
                    local[40, 40],
                    paintColor.value
                        .compositeOver(sourceColor.value)
                        .copy(alpha = alpha.floatValue),
                )
            else assertEquals(0f, local[40, 40].alpha)
            val output = onNodeWithTag("retained-export").captureToImage().toPixelMap()[40, 40]
            if (alpha.floatValue > 0f)
                assertColor(output, exportColor.value.copy(alpha = alpha.floatValue))
            else assertEquals(0f, output.alpha)
        }
        checkPixels()
        val changes: List<() -> Unit> =
            listOf(
                { sourceColor.value = Color.Green },
                { paintColor.value = Color.Red.copy(alpha = .25f) },
                { foregroundColor.value = Color.Yellow },
                { exportColor.value = Color.Blue },
            )
        for (change in changes) {
            val before = calls()
            for (opacity in listOf(.6f, .7f, .4f, .3f)) {
                runOnIdle { alpha.floatValue = opacity }
                checkPixels()
                assertEquals(before, calls(), "Alpha changes re-recorded the graph")
            }
            runOnIdle(change)
            checkPixels()
        }
        runOnIdle { alpha.floatValue = 0f }
        checkPixels()
        val hiddenSourceCalls = sourceCalls
        runOnIdle {
            sourceColor.value = Color.Red
            exportColor.value = Color.Green
            foregroundColor.value = Color.Magenta
        }
        checkPixels()
        assertEquals(hiddenSourceCalls, sourceCalls)
        runOnIdle { alpha.floatValue = .5f }
        checkPixels()
    }

    @Test
    fun unusedTransformDoesNotResolveOrAllocateResources() = runRenderTest {
        val scope = BackdropRenderScope(1f, 1f, Size(80f, 80f), RectangleShape, LayoutDirection.Ltr)
        with(scope) {
            image {
                source()
                transform(Matrix(FloatArray(16)))
            }
        }
        val renderer = BackdropRenderer {
            error("Unused branches must not allocate rendering resources")
        }
        renderer.resolve(scope, Rect(0f, 0f, 80f, 80f))
        assertEquals(BackdropExtension.None, renderer.extension)
    }

    @Test
    fun clearIsIsolatedAndMixIsPremultiplied() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Green) }
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .drawBackdrop(
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
    fun groupAlphaLeavesContentAndRecoversWithoutRecordingAtZero() = runRenderTest {
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
                        .drawBackdrop(
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
    fun branchesAndGeneratedLayerDslPreserveIdentityAlpha() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red.copy(alpha = .4f)) }
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .drawBackdrop(
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
    fun spatialMasksAndForegroundHaveExplicitScope() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red) }
            Box(
                Modifier.size(80.dp)
                    .testTag("scene")
                    .drawBackdrop(
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
    fun verticalWeightsMixIndependentBlurBranches() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop {
                var x = floor(samplingBounds.left / 8f).toInt() * 8 - 8
                while (x < samplingBounds.right + 8) {
                    drawRect(
                        if (floor(x / 8f).toInt() % 2 == 0) Color.White else Color.Black,
                        Offset(x.toFloat(), samplingBounds.top),
                        Size(8f, samplingBounds.height),
                    )
                    x += 8
                }
            }
            Box(
                Modifier.size(80.dp)
                    .testTag("progressive")
                    .drawBackdrop(
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
    fun scalarMixPreservesPremultipliedEndpointsAndMidpoint() = runRenderTest {
        val weight = mutableFloatStateOf(0f)
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(
                Modifier.size(80.dp)
                    .testTag("mixed")
                    .drawBackdrop(
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
    fun maskingBeforeAndAfterBlurHaveDifferentBoundaries() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop {
                drawRect(Color.Red, samplingBounds.topLeft, samplingBounds.size)
            }
            Column {
                for (before in listOf(false, true)) {
                    Box(
                        Modifier.size(80.dp)
                            .testTag(if (before) "before" else "after")
                            .drawBackdrop(
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
    fun explicitExportPreservesAlphaTransitionsAndExcludesForeground() = runRenderTest {
        val alpha = mutableFloatStateOf(1f)
        setContent {
            val source = rememberCanvasBackdrop {}
            val exported = rememberLayerBackdrop()
            Column {
                Box(
                    Modifier.size(80.dp)
                        .testTag("local")
                        .drawBackdrop(
                            source,
                            { RectangleShape },
                            backgroundAlpha = { alpha.floatValue },
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
                        .drawBackdrop(exported, { RectangleShape }),
                )
            }
        }
        for (opacity in listOf(1f, .5f, 0f, .25f, 1f, .5f, 1f)) {
            runOnIdle { alpha.floatValue = opacity }
            val local = onNodeWithTag("local").captureToImage().toPixelMap()[40, 40]
            val exported = onNodeWithTag("exported").captureToImage().toPixelMap()[40, 40]
            assertTrue(local.green > .98f, "Foreground remains independently opaque")
            assertTrue(abs(exported.alpha - opacity) < .02f, "Export applies background alpha once")
            if (opacity > 0f)
                assertTrue(
                    exported.blue > .98f && exported.green < .01f,
                    "Export uses the selected blue image",
                )
        }
    }

    @Test
    fun imagePaintAppendsWhileDrawWithContentCanReplaceInput() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop {}
            Column {
                for (mode in listOf("original", "painted", "replaced")) {
                    Box(
                        Modifier.size(80.dp)
                            .testTag(mode)
                            .drawBackdrop(
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
    fun imagePaintSurfaceClipsOnlyTheAppendedDrawing() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop {}
            Box(
                Modifier.size(80.dp)
                    .testTag("painted")
                    .drawBackdrop(
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
