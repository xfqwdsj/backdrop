package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackdropRendererTest {
    private fun declaration() =
        BackdropRenderScope(
            Density(1f),
            Size(40f, 40f),
            RectangleShape,
            LayoutDirection.Ltr,
        )

    @Test
    fun resolvedHandlesHaveDeclarationOwnershipAndLifecycle() {
        val renderer = BackdropRenderer { error("Geometry resolution needs no layers") }
        val graph = declaration()
        val foreign = declaration()
        assertFailsWith<IllegalStateException> { renderer.beginFrame() }
        assertFailsWith<IllegalStateException> { renderer.outputBounds() }
        renderer.resolve(graph, Rect(0f, 0f, 40f, 40f))
        assertEquals(Rect(0f, 0f, 40f, 40f), renderer.outputBounds(graph.current))
        assertFailsWith<IllegalArgumentException> { renderer.outputBounds(foreign.current) }
        assertFailsWith<IllegalStateException> { graph.source() }
        renderer.close()
        renderer.close()
        assertFailsWith<IllegalStateException> { renderer.beginFrame() }
        assertFailsWith<IllegalStateException> { renderer.outputBounds(graph.current) }
        renderer.resolve(foreign, Rect(0f, 0f, 40f, 40f))
        renderer.beginFrame()
        assertEquals(Rect(0f, 0f, 40f, 40f), renderer.outputBounds())
        assertFailsWith<IllegalArgumentException> {
            renderer.resolve(foreign, Rect(Float.NaN, 0f, 40f, 40f))
        }
        assertFailsWith<IllegalStateException> { renderer.outputBounds() }
        renderer.close()
    }

    @Test
    fun independentGeometryRejectsUnspecifiedOrInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            BackdropRenderScope(Density(0f), Size(40f, 40f), RectangleShape, LayoutDirection.Ltr)
        }
        assertFailsWith<IllegalArgumentException> {
            BackdropRenderScope(Density(1f), Size.Unspecified, RectangleShape, LayoutDirection.Ltr)
        }
    }

    @Test
    fun drawingRequiresAFrameMatchingGeometryAndSelectedOutputs() {
        val renderer = BackdropRenderer { error("Invalid calls must not allocate layers") }
        val graph =
            BackdropRenderScope(
                Density(2f),
                Size(40f, 40f),
                RectangleShape,
                LayoutDirection.Ltr,
            )
        val unused = graph.image { paint { drawRect(Color.Red) } }
        renderer.resolve(graph, Rect(0f, 0f, 40f, 40f))
        assertFailsWith<IllegalArgumentException> { renderer.outputBounds(unused) }
        val target = CanvasDrawScope()
        assertFailsWith<IllegalStateException> { renderer.draw(target) {} }
        renderer.beginFrame()
        assertFailsWith<IllegalArgumentException> { renderer.draw(target) {} }
        renderer.close()
    }

    @Test
    fun emptyRequestsDoNotRecordOrAllocateLayers() {
        BackdropRenderer { error("Empty requests must not allocate layers") }
            .use { renderer ->
                for (size in listOf(Size.Zero, Size(40f, 40f))) {
                    val graph =
                        BackdropRenderScope(Density(1f), size, RectangleShape, LayoutDirection.Ltr)
                    renderer.resolve(graph, Rect.Zero)
                    renderer.beginFrame()
                    renderer.prepare(CanvasDrawScope()) {
                        error("Empty requests must not draw sources")
                    }
                    renderer.draw(CanvasDrawScope()) {
                        error("Empty requests must not draw sources")
                    }
                }
            }
    }

    @Test
    fun publicDrawingSharesRecordingsAndClosesOwnedLayers() = runRenderTest {
        val color = mutableStateOf(Color.Red)
        val active = mutableSetOf<GraphicsLayer>()
        var sourceCalls = 0
        var failRelease = false
        val releaseFailure = IllegalStateException("Synthetic release failure")
        lateinit var renderer: BackdropRenderer
        setContent {
            val native = LocalGraphicsContext.current
            val graphics =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer() =
                            native.createGraphicsLayer().also { active += it }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            assertTrue(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                            if (failRelease) throw releaseFailure
                        }
                    }
                }
            renderer = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            Canvas(Modifier.size(40.dp).testTag("public-renderer")) {
                val selected = color.value
                val graph = declaration()
                graph.foreground { paint { drawRect(Color.Transparent) } }
                graph.export(graph.image { paint { drawRect(Color.Transparent) } })
                renderer.resolve(graph, Rect(Offset.Zero, size))
                renderer.beginFrame()
                val source: DrawScope.(Rect) -> Unit = {
                    sourceCalls++
                    drawRect(selected)
                }
                renderer.prepare(this, source = source)
                renderer.prepare(this, checkNotNull(graph.foregroundImage), source)
                renderer.prepare(this, checkNotNull(graph.exportedImage), source)
                renderer.draw(this, source = source)
                renderer.draw(this, source = source)
                assertFailsWith<IllegalArgumentException> {
                    renderer.draw(this, declaration().current, source)
                }
            }
        }
        fun pixel() = onNodeWithTag("public-renderer").captureToImage().toPixelMap()[20, 20]
        assertEquals(Color.Red, pixel())
        val initial = sourceCalls
        assertTrue(initial > 0)
        runOnIdle { color.value = Color.Blue }
        assertEquals(Color.Blue, pixel())
        assertEquals(initial + 1, sourceCalls)
        runOnIdle {
            assertTrue(active.size >= 3)
            failRelease = true
            val failure = assertFailsWith<IllegalStateException> { renderer.close() }
            assertSame(releaseFailure, failure)
            assertTrue(failure.suppressedExceptions.isEmpty())
            renderer.close()
            assertFailsWith<IllegalStateException> { renderer.outputBounds() }
        }
        assertTrue(active.isEmpty())
    }
}
