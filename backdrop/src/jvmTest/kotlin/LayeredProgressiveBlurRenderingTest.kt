package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.coverRamp
import top.ltfan.backdrop.effects.layeredProgressiveBlur
import top.ltfan.backdrop.effects.progressiveBlur
import top.ltfan.backdrop.internal.BackdropGraphRenderer

class LayeredProgressiveBlurRenderingTest {
    // Measured across 4/8/16 levels and both directions on the production Skiko path.
    // Each row bounds mean premultiplied RGBA error, maximum channel error, and maximum
    // horizontal/vertical neighbor-difference error for one pattern. Tolerances allow for 8-bit
    // capture rounding and the different native/shader blur kernels.
    private val limits =
        listOf(
            floatArrayOf(.04f, .25f, .22f),
            floatArrayOf(.015f, .23f, .45f),
            floatArrayOf(.05f, .40f, .56f),
            floatArrayOf(.012f, .13f, .15f),
        )

    @Test
    fun approximationTracksShaderAcrossPatternsDirectionsAndCoverage() {
        for (pattern in 0..3) for (direction in LayoutDirection.entries) runComposeUiTest {
            val mode = mutableIntStateOf(0)
            val edge = BackdropEdge.entries[pattern]
            val ramp =
                BackdropRamp(
                    anchors = BackdropRampAnchors.offsets(64.dp, 16.dp),
                    from = if (pattern == 2) 1f else 0f,
                    to = if (pattern == 2) 0f else 1f,
                    curve = if (pattern % 2 == 0) BackdropCurve.Linear else BackdropCurve.Default,
                )
            val radius = if (pattern % 2 == 0) 8f else 16f
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val source = rememberCanvasBackdrop { pattern(pattern) }
                    Box(
                        Modifier.size(96.dp)
                            .testTag("scene")
                            .renderBackdrop(
                                source,
                                { RectangleShape },
                                render = {
                                    if (mode.intValue == 0)
                                        effects { progressiveBlur(radius, edge, ramp) }
                                    else layeredProgressiveBlur(radius, edge, ramp, mode.intValue)
                                    if (pattern % 2 == 0) clipSurface()
                                },
                            ),
                    )
                }
            }
            val reference = onNodeWithTag("scene").captureToImage().toPixelMap()
            for (levels in listOf(4, 8, 16)) {
                runOnIdle { mode.intValue = levels }
                val actual = onNodeWithTag("scene").captureToImage().toPixelMap()
                val error = difference(reference, actual)
                println(
                    "LAYERED_DIFF pattern=$pattern direction=$direction levels=$levels mean=${error[0]} max=${error[1]} jump=${error[2]}",
                )
                assertTrue(
                    error[0] < limits[pattern][0] &&
                        error[1] < limits[pattern][1] &&
                        error[2] < limits[pattern][2],
                    "${error.toList()}",
                )
            }
        }
    }

    @Test
    fun constantIntensityMatchesUniformBlurAndRemovalRestoresCurrentImage() = runComposeUiTest {
        val mode = mutableIntStateOf(0)
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Green) }
            Box(
                Modifier.size(96.dp)
                    .testTag("scene")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            // The algorithm must operate on current, including this preceding
                            // drawing.
                            paint { pattern(3) }
                            when (mode.intValue) {
                                1 -> effects { blur(8f) }
                                2 ->
                                    layeredProgressiveBlur(
                                        16f,
                                        BackdropEdge.Top,
                                        BackdropRamp(from = .5f, to = .5f),
                                        3,
                                    )
                                3 ->
                                    layeredProgressiveBlur(
                                        16f,
                                        BackdropEdge.End,
                                        BackdropRamp(from = 0f, to = 0f),
                                    )
                                4 -> layeredProgressiveBlur(4f, BackdropEdge.Bottom, levels = 4)
                            }
                        },
                    ),
            )
        }
        val original = onNodeWithTag("scene").captureToImage().toPixelMap()
        runOnIdle { mode.intValue = 1 }
        val uniform = onNodeWithTag("scene").captureToImage().toPixelMap()
        runOnIdle { mode.intValue = 2 }
        assertTrue(
            difference(uniform, onNodeWithTag("scene").captureToImage().toPixelMap())[1] < .02f,
        )
        runOnIdle { mode.intValue = 3 }
        assertTrue(
            difference(original, onNodeWithTag("scene").captureToImage().toPixelMap())[1] < .02f,
        )
        runOnIdle { mode.intValue = 4 }
        assertTrue(
            difference(original, onNodeWithTag("scene").captureToImage().toPixelMap())[0] > .001f,
        )
        runOnIdle { mode.intValue = 0 }
        assertTrue(
            difference(original, onNodeWithTag("scene").captureToImage().toPixelMap())[1] < .02f,
        )
    }

    @Test
    fun translucentInputKeepsAlphaAndComposesWithMasksAndMix() = runComposeUiTest {
        val mode = mutableIntStateOf(0)
        val ramp = BackdropRamp()
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red.copy(alpha = .375f)) }
            Box(
                Modifier.size(96.dp)
                    .testTag("scene")
                    .renderBackdrop(
                        source,
                        { RectangleShape },
                        render = {
                            val original = current
                            layeredProgressiveBlur(12f, BackdropEdge.Top)
                            if (mode.intValue != 0) {
                                val coverage = image {
                                    effects { coverRamp(ramp, BackdropEdge.Top) }
                                    paint { room ->
                                        val (start, end) =
                                            backdropRampEnds(
                                                ramp,
                                                BackdropEdge.Top,
                                                room.size,
                                                layoutDirection,
                                                Offset.Zero,
                                            )
                                        drawRect(
                                            ramp.brush(Color.White, start, end),
                                            room.extension.originInNode,
                                            Size(
                                                room.size.width +
                                                    room.extension.left +
                                                    room.extension.right,
                                                room.size.height +
                                                    room.extension.top +
                                                    room.extension.bottom,
                                            ),
                                        )
                                    }
                                }
                                if (mode.intValue == 1) mask(coverage)
                                else mix(original, current, coverage)
                            }
                        },
                    ),
            )
        }
        fun alphaAtCenter() = onNodeWithTag("scene").captureToImage().toPixelMap()[48, 48].alpha
        assertTrue(abs(alphaAtCenter() - .375f) < .015f)
        runOnIdle { mode.intValue = 1 }
        assertTrue(alphaAtCenter() in 0.05f..0.35f)
        runOnIdle { mode.intValue = 2 }
        assertTrue(abs(alphaAtCenter() - .375f) < .015f)
    }

    @Test
    fun extendedRegionKeepsRampCoordinatesAndAcceptsAnIndependentMask() = runComposeUiTest {
        val mode = mutableIntStateOf(0)
        val ramp =
            BackdropRamp(
                anchors = BackdropRampAnchors.offsets(16.dp, 24.dp),
                curve = BackdropCurve.Linear,
            )
        setContent {
            val source = rememberCanvasBackdrop {
                drawRect(Color.Red, samplingBounds.topLeft, samplingBounds.size)
            }
            Box(Modifier.size(160.dp).testTag("extended")) {
                Box(
                    Modifier.offset(40.dp, 40.dp)
                        .size(80.dp)
                        .renderBackdrop(
                            source,
                            { RectangleShape },
                            render = {
                                if (mode.intValue == 0)
                                    effects { progressiveBlur(12f, BackdropEdge.Top, ramp) }
                                else layeredProgressiveBlur(12f, BackdropEdge.Top, ramp)
                                if (mode.intValue == 2) {
                                    val coverage = image {
                                        paint { room ->
                                            val (start, end) =
                                                backdropRampEnds(
                                                    ramp,
                                                    BackdropEdge.Top,
                                                    room.size,
                                                    layoutDirection,
                                                    Offset.Zero,
                                                )
                                            drawRect(
                                                ramp.brush(Color.White, start, end),
                                                room.extension.originInNode,
                                                Size(
                                                    room.size.width +
                                                        room.extension.left +
                                                        room.extension.right,
                                                    room.size.height +
                                                        room.extension.top +
                                                        room.extension.bottom,
                                                ),
                                            )
                                        }
                                    }
                                    mask(coverage)
                                }
                            },
                        ),
                )
            }
        }
        fun capture() = onNodeWithTag("extended").captureToImage().toPixelMap()
        val shader = capture()
        assertTrue(shader[80, 129].alpha > .98f)
        assertTrue(shader[80, 146].alpha < .01f)
        runOnIdle { mode.intValue = 1 }
        val layered = capture()
        assertTrue(layered[80, 129].alpha > .98f)
        assertTrue(layered[80, 146].alpha < .01f)
        runOnIdle { mode.intValue = 2 }
        val masked = capture()
        // Pixel center y=129.5 is 9.5px beyond the surface: strength=(-9.5 + 24)/40.
        assertTrue(masked[80, 129].alpha in 0.34f..0.39f)
        assertTrue(masked[80, 146].alpha < .01f)
    }

    @Test
    fun changingLevelsAndOmittingOrDetachingReleasesRecordedLayers() = runComposeUiTest {
        val mode = mutableIntStateOf(0)
        val active = mutableSetOf<GraphicsLayer>()
        setContent {
            val native = LocalGraphicsContext.current
            val tracked =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer(): GraphicsLayer =
                            native.createGraphicsLayer().also {
                                check(active.add(it))
                            }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            check(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                        }
                    }
                }
            if (mode.intValue >= 0) {
                val renderer = remember(tracked) { BackdropGraphRenderer { tracked } }
                DisposableEffect(renderer) { onDispose { renderer.release() } }
                Canvas(Modifier.size(96.dp).testTag("scene")) {
                    val scope =
                        BackdropRenderScope(
                            density,
                            fontScale,
                            size,
                            RectangleShape,
                            layoutDirection,
                        )
                    if (mode.intValue > 0)
                        scope.layeredProgressiveBlur(12f, BackdropEdge.Top, levels = mode.intValue)
                    renderer.resolve(scope, Rect(Offset.Zero, size))
                    renderer.beginFrame()
                    renderer.draw(this) { bounds ->
                        drawRect(Color.Red, bounds.topLeft, bounds.size)
                    }
                }
            }
        }
        onNodeWithTag("scene").captureToImage()
        val baseline = active.size
        runOnIdle { mode.intValue = 4 }
        onNodeWithTag("scene").captureToImage()
        val four = active.size
        assertTrue(four > baseline)
        runOnIdle { mode.intValue = 16 }
        onNodeWithTag("scene").captureToImage()
        assertTrue(active.size > four)
        runOnIdle { mode.intValue = 0 }
        onNodeWithTag("scene").captureToImage()
        assertEquals(baseline, active.size)
        runOnIdle { mode.intValue = -1 }
        waitForIdle()
        assertTrue(active.isEmpty(), "Detached receiver retained ${active.size} layers")
    }

    private fun DrawScope.pattern(kind: Int) {
        drawRect(Color.Black.copy(alpha = if (kind == 3) .375f else 1f))
        for (y in -32..128 step 4) for (x in -32..128 step 4) {
            val white =
                when (kind) {
                    0 -> x % 8 == 0
                    1 -> (x / 4 + y / 4) % 2 == 0
                    else -> x % 16 == 0 || (y % 16 == 0 && x % 16 < 12)
                }
            if (white)
                drawRect(
                    Color.White.copy(alpha = if (kind == 3) .375f else 1f),
                    Offset(x.toFloat(), y.toFloat()),
                    Size(4f, 4f),
                )
        }
    }

    private fun difference(reference: PixelMap, actual: PixelMap): FloatArray {
        var sum = 0f
        var maximum = 0f
        var jump = 0f
        fun components(color: Color) =
            floatArrayOf(
                color.red * color.alpha,
                color.green * color.alpha,
                color.blue * color.alpha,
                color.alpha,
            )
        for (y in 4 until reference.height - 4) for (x in 4 until reference.width - 4) {
            val a = components(reference[x, y])
            val b = components(actual[x, y])
            val previousA = components(reference[x - 1, y])
            val previousB = components(actual[x - 1, y])
            val aboveA = components(reference[x, y - 1])
            val aboveB = components(actual[x, y - 1])
            for (channel in 0..3) {
                val error = abs(a[channel] - b[channel])
                sum += error
                maximum = maxOf(maximum, error)
                jump =
                    maxOf(
                        jump,
                        abs((b[channel] - previousB[channel]) - (a[channel] - previousA[channel])),
                        abs((b[channel] - aboveB[channel]) - (a[channel] - aboveA[channel])),
                    )
            }
        }
        return floatArrayOf(
            sum / ((reference.width - 8) * (reference.height - 8) * 4),
            maximum,
            jump,
        )
    }
}
