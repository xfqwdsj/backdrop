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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.blur

class RenderPlanCostTest {
    @Test
    fun serialEffectsShareOneFilteredRecording() = runRenderTest {
        val mode = mutableIntStateOf(0)
        val active = mutableSetOf<GraphicsLayer>()
        var sourceCalls = 0
        var effectCalls = 0
        setContent {
            val native = LocalGraphicsContext.current
            val graphics =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer() =
                            native.createGraphicsLayer().also { active += it }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            check(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                        }
                    }
                }
            val renderer = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            Canvas(Modifier.size(80.dp).testTag("serial-recording")) {
                val graph = BackdropRenderScope(1f, 1f, size, RectangleShape, LayoutDirection.Ltr)
                graph.effects {
                    effectCalls++
                    blur(4f)
                }
                val first = graph.current
                graph.effects {
                    effectCalls++
                    blur(8f)
                }
                if (mode.intValue == 1) graph.foreground { replace(first) }
                if (mode.intValue == 2) graph.foreground { source() }
                effectCalls = 0
                renderer.resolve(graph, Rect(Offset.Zero, size))
                renderer.beginFrame()
                sourceCalls = 0
                val source: DrawScope.(Rect) -> Unit = { bounds ->
                    sourceCalls++
                    drawRect(Color.Red, bounds.topLeft, bounds.size)
                }
                renderer.draw(this, source = source)
                graph.foregroundImage?.let { renderer.draw(this, it.index, source) }
            }
        }
        for (value in listOf(0, 1, 2, 0, 2, 1, 0)) {
            runOnIdle { mode.intValue = value }
            val image = onNodeWithTag("serial-recording").captureToImage().toPixelMap()
            assertTrue(image[40, 40].red > .99f && image[40, 40].alpha > .99f)
            assertEquals(1, sourceCalls)
            assertEquals(2, effectCalls)
            assertEquals(
                if (value == 0) 1 else 2,
                active.size,
                "Keep independently read outputs while fusing private serial recordings",
            )
        }
    }

    @Test
    fun graphResolutionTracksRootsConnectionsAndCurrentEffectParameters() = runRenderTest {
        BackdropRenderer { error("Resolution must not record layers") }
            .use { renderer ->
                for (grouped in listOf(false, true)) {
                    for ((revision, selected) in listOf(0, 0, 1, 1, 0).withIndex()) {
                        val calls = IntArray(2)
                        val scope =
                            BackdropRenderScope(
                                1f,
                                1f,
                                Size(80f, 80f),
                                RectangleShape,
                                LayoutDirection.Ltr,
                            )
                        val images =
                            List(2) { branch ->
                                scope.image {
                                    source()
                                    effects {
                                        calls[branch]++
                                        blur(4f + branch * 8f + revision)
                                    }
                                }
                            }
                        if (grouped) scope.group { draw(images[selected], alpha = .5f) }
                        else scope.replace(images[selected])
                        val request = Rect(Offset.Zero, scope.size)
                        renderer.resolve(scope, request)
                        assertEquals(1, calls[selected])
                        assertEquals(0, calls[1 - selected], "Disconnected effects were evaluated")
                        BackdropRenderer { error("Resolution must not record layers") }
                            .use { fresh ->
                                fresh.resolve(scope, request)
                                assertEquals(fresh.extension, renderer.extension)
                                assertEquals(fresh.visible, renderer.visible)
                            }
                    }
                }
            }
    }

    @Test
    fun distantBranchesKeepLocalStorageAndSurfaceCoordinates() = runRenderTest {
        val displacement = mutableIntStateOf(200)
        val active = mutableSetOf<GraphicsLayer>()
        setContent {
            val native = LocalGraphicsContext.current
            val graphics =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer(): GraphicsLayer =
                            native.createGraphicsLayer().also { active += it }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            check(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                        }
                    }
                }
            val renderer = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            Canvas(Modifier.size(400.dp, 96.dp).testTag("local-storage")) {
                val scope =
                    BackdropRenderScope(1f, 1f, Size(48f, 48f), RectangleShape, LayoutDirection.Ltr)
                val material = scope.image {
                    source()
                    effects { blur(4f) }
                    clipSurface()
                }
                val distant = scope.image {
                    source()
                    clipSurface()
                    transform(Matrix().apply { translate(displacement.intValue + .25f, 0f) })
                }
                scope.group {
                    draw(material)
                    draw(distant)
                }
                renderer.resolve(scope, Rect(Offset.Zero, scope.size))
                renderer.beginFrame()
                translate(120f, 24f) {
                    renderer.draw(this) { bounds ->
                        assertEquals(scope.size, size)
                        assertEquals(scope.size, drawContext.size)
                        drawRect(Color.Red, bounds.topLeft, bounds.size)
                    }
                }
            }
        }
        for (offset in listOf(200, -80, 80, -80, 200)) {
            runOnIdle { displacement.intValue = offset }
            val pixels = onNodeWithTag("local-storage").captureToImage().toPixelMap()
            for (x in listOf(144, 144 + offset)) {
                assertTrue(pixels[x, 48].red > .99f)
                assertTrue(pixels[x, 48].alpha > .99f)
            }
            val gap = if (offset > 0) (48 + offset) / 2 else (offset + 48) / 2
            assertEquals(0f, pixels[120 + gap, 48].alpha)
            val areas = active.map { it.size.width.toLong() * it.size.height }
            assertTrue(
                areas.sum() < areas.max() * areas.size,
                "Every branch retained the full graph storage: ${active.map { it.size }}",
            )
        }
    }

    @Test
    fun identityStagesAndTransparentBasesReuseInputStorageAcrossOutputs() = runRenderTest {
        val mode = mutableIntStateOf(0)
        val active = mutableSetOf<GraphicsLayer>()
        var sourceCalls = 0
        setContent {
            val native = LocalGraphicsContext.current
            val graphics =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer(): GraphicsLayer =
                            native.createGraphicsLayer().also { active += it }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            check(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                        }
                    }
                }
            val renderer = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            Canvas(Modifier.size(48.dp).testTag("plan")) {
                val scope =
                    BackdropRenderScope(1f, 1f, Size(48f, 48f), RectangleShape, LayoutDirection.Ltr)
                val image = scope.image {
                    source()
                    effects { if (mode.intValue == 0) blur(8f) }
                    effects {}
                }
                scope.group { draw(image) }
                scope.foreground { draw(image) }
                renderer.resolve(scope, Rect(Offset.Zero, scope.size))
                renderer.beginFrame()
                var calls = 0
                val source: DrawScope.(Rect) -> Unit = { bounds ->
                    calls++
                    drawRect(Color.Red, bounds.topLeft, bounds.size)
                }
                renderer.draw(this, source = source)
                renderer.draw(this, checkNotNull(scope.foregroundImage).index, source)
                sourceCalls = calls
            }
        }
        onNodeWithTag("plan").captureToImage()
        assertEquals(1, active.size, "The source and active filter share one recording")
        assertEquals(1, sourceCalls)
        for (value in listOf(1, 2, 0, 1)) {
            runOnIdle { mode.intValue = value }
            onNodeWithTag("plan").captureToImage()
            assertEquals(
                1,
                active.size,
                "Identity stages retained intermediate storage",
            )
            assertEquals(1, sourceCalls, "Shared foreground and background re-recorded the source")
        }
    }
}
