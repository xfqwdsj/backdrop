package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalGraphicsContext
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
import top.ltfan.backdrop.effects.layeredBlurColors
import top.ltfan.backdrop.effects.layeredBlurStops
import top.ltfan.backdrop.effects.layeredBlurWeight
import top.ltfan.backdrop.effects.layeredProgressiveBlur
import top.ltfan.backdrop.internal.BackdropGraphRenderer

class LayeredProgressiveBlurCostTest {
    @Test
    fun weightStorageGrowsWithSamplesAndLevelsInsteadOfTheirProduct() {
        for (levels in listOf(4, 8, 16, 64)) for (samples in listOf(2, 16, 256)) for (reverse in
            listOf(false, true)) {
            val ramp =
                BackdropRamp(
                    from = if (reverse) 1f else 0f,
                    to = if (reverse) 0f else 1f,
                    stops = samples,
                )
            val stops = layeredBlurStops(ramp, levels)
            val weights = List(levels) { layeredBlurColors(stops, it, levels) }
            assertTrue(
                stops.size <= samples + levels,
                "Ramp storage grew beyond sample and crossing counts",
            )
            assertTrue(
                weights.sumOf { it.size } <= 2 * samples + 8 * levels,
                "Gradient storage grew beyond the linear budget: samples=$samples levels=$levels",
            )
            for (level in weights.indices) {
                val spans = weights[level].zipWithNext()
                for ((a, b) in stops.zipWithNext()) for (fraction in
                    listOf(0f, .25f, .5f, .75f, 1f)) {
                    val position = a.first + (b.first - a.first) * fraction
                    val expected =
                        Color.White.copy(alpha = layeredBlurWeight(a.second, level, levels)).alpha *
                            (1f - fraction) +
                            Color.White.copy(alpha = layeredBlurWeight(b.second, level, levels))
                                .alpha * fraction
                    val span =
                        spans.firstOrNull { position <= it.second.first }
                            ?: error("Missing gradient endpoint")
                    val share =
                        ((position - span.first.first) / (span.second.first - span.first.first))
                            .coerceIn(0f, 1f)
                    val actual =
                        span.first.second.alpha +
                            (span.second.second.alpha - span.first.second.alpha) * share
                    assertTrue(
                        abs(expected - actual) < .00002f,
                        "Compaction changed a weight: level=$level position=$position expected=$expected actual=$actual",
                    )
                }
            }
        }
    }

    @Test
    fun constantStrengthHasBoundedDeclarationStorageRegardlessOfLevelCount() {
        for (levels in listOf(4, 8, 16, 64, 1024)) {
            val ramp = BackdropRamp(from = .5f, to = .5f)
            val stops = layeredBlurStops(ramp, levels)
            assertEquals(2, stops.size)
            val scope =
                BackdropRenderScope(1f, 1f, Size(96f, 96f), RectangleShape, LayoutDirection.Ltr)
            scope.layeredProgressiveBlur(16f, BackdropEdge.Top, ramp, levels)
            assertTrue(scope.graph.size <= 14, "Constant strength allocated unused branches")
            assertTrue(scope.graph.count { it is RenderImageNode.Process } <= 2)
        }
    }

    @Test
    fun recordedWorkReusesLayersAndSharesOneSourceRecordingPerFrame() = runComposeUiTest {
        val levels = mutableIntStateOf(4)
        val revision = mutableIntStateOf(0)
        val active = mutableSetOf<GraphicsLayer>()
        var created = 0
        var sourceCalls = 0
        var recordedPixels = 0L
        setContent {
            val native = LocalGraphicsContext.current
            val tracked =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer(): GraphicsLayer =
                            native.createGraphicsLayer().also {
                                created++
                                check(active.add(it))
                            }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            check(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                        }
                    }
                }
            if (levels.intValue >= 0) {
                val renderer = remember(tracked) { BackdropGraphRenderer { tracked } }
                DisposableEffect(renderer) { onDispose { renderer.release() } }
                Canvas(Modifier.size(96.dp).testTag("cost")) {
                    val frame = revision.intValue
                    val scope =
                        BackdropRenderScope(
                            1f,
                            1f,
                            Size(96f, 96f),
                            RectangleShape,
                            LayoutDirection.Ltr,
                        )
                    if (levels.intValue > 0)
                        scope.layeredProgressiveBlur(
                            16f,
                            BackdropEdge.Top,
                            levels = levels.intValue,
                        )
                    renderer.resolve(scope, Rect(Offset.Zero, scope.size))
                    renderer.beginFrame()
                    var calls = 0
                    renderer.draw(this) { bounds ->
                        calls++
                        drawRect(
                            if (frame % 2 == 0) Color.Red else Color.Blue,
                            bounds.topLeft,
                            bounds.size,
                        )
                    }
                    sourceCalls = calls
                    recordedPixels = active.sumOf { it.size.width.toLong() * it.size.height }
                }
            }
        }
        for (count in listOf(4, 8, 16)) {
            runOnIdle { levels.intValue = count }
            onNodeWithTag("cost").captureToImage()
            assertEquals(1, sourceCalls, "Branches re-recorded their shared source")
            // One source and one group base, plus at most five recorded nodes per blur level.
            val layerBudget = 5 * count + 2
            assertTrue(active.size <= layerBudget, "Layer budget exceeded for $count levels")
            // Radius 16 needs at most 30px support per side: each layer fits within 156 x 156.
            // This bounds logical recording pixels, not native texture allocation or GPU memory.
            assertTrue(recordedPixels <= layerBudget.toLong() * 156 * 156)
            val allocations = created
            repeat(3) {
                runOnIdle { revision.intValue++ }
                onNodeWithTag("cost").captureToImage()
                assertEquals(
                    allocations,
                    created,
                    "Unchanged geometry allocated more graphics layers",
                )
                assertEquals(1, sourceCalls)
            }
        }
        runOnIdle { levels.intValue = 0 }
        onNodeWithTag("cost").captureToImage()
        assertEquals(1, active.size, "Omitted blur retained intermediate layers")
        runOnIdle { levels.intValue = -1 }
        waitForIdle()
        assertTrue(active.isEmpty())
    }
}
