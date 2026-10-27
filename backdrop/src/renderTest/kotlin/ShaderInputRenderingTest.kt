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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.progressiveBlur
import top.ltfan.backdrop.effects.runtimeShaderEffect
import top.ltfan.backdrop.internal.requiresSampledEffectInputImage

class ShaderInputRenderingTest {
    @Test
    fun progressiveBlurRetainsOpaqueAndTranslucentInputAcrossEdgesAndRadii() = runRenderTest {
        val revision = mutableIntStateOf(0)
        val cases = listOf(.01f, .5f, 1.01f, 2f, 4f, 24f, 48f, 0f)
        setContent {
            val graphics = LocalGraphicsContext.current
            val renderer = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            Canvas(Modifier.size(80.dp, 160.dp).testTag("progressive-input")) {
                val value = revision.intValue
                val alpha = if (value / (cases.size * 4) == 0) 1f else .5f
                val edge = BackdropEdge.entries[(value / cases.size) % 4]
                val graph =
                    BackdropRenderScope(this, Size(63f, 147f), RectangleShape, layoutDirection)
                graph.effects { progressiveBlur(cases[value % cases.size], edge) }
                renderer.resolve(graph, Rect(0f, 0f, 63f, 147f))
                renderer.beginFrame()
                drawRect(Color.Green)
                renderer.draw(this) { bounds ->
                    drawRect(Color.Red.copy(alpha = alpha), bounds.topLeft, bounds.size)
                }
            }
        }
        repeat(cases.size * 4 * 2) { value ->
            runOnIdle { revision.intValue = value }
            val pixels = onNodeWithTag("progressive-input").captureToImage().toPixelMap()
            val expected = if (value / (cases.size * 4) == 0) 1f else .5f
            for (x in listOf(0, 1, 31, 61, 62)) for (y in listOf(0, 1, 73, 145, 146)) {
                val color = pixels[x, y]
                assertTrue(
                    abs(color.red - expected) <= 2f / 255f &&
                        abs(color.green - (1f - expected)) <= 2f / 255f,
                    "case=$value ($x,$y): $color; expected red=$expected",
                )
            }
        }
    }

    @Test
    fun sampledStagesRetainDisjointIntermediateRegionsWithinAndAcrossEffectBlocks() =
        runRenderTest {
            val split = mutableIntStateOf(0)
            setContent {
                val graphics = LocalGraphicsContext.current
                val renderer = remember(graphics) { BackdropRenderer { graphics } }
                DisposableEffect(renderer) { onDispose { renderer.close() } }
                Canvas(Modifier.size(80.dp).testTag("translated-input")) {
                    val graph =
                        BackdropRenderScope(this, Size(40f, 40f), RectangleShape, layoutDirection)
                    val first: BackdropEffectScope.() -> Unit = { translatedShader(60f, -55f) }
                    val second: BackdropEffectScope.() -> Unit = { translatedShader(-60f, 55f) }
                    if (split.intValue == 0)
                        graph.effects {
                            first()
                            second()
                        }
                    else {
                        graph.effects(first)
                        graph.effects(second)
                    }
                    renderer.resolve(graph, Rect(0f, 0f, 40f, 40f))
                    renderer.beginFrame()
                    renderer.draw(this) { bounds ->
                        drawRect(Color.Blue, bounds.topLeft, bounds.size)
                        drawRect(Color.Red, Offset(0f, 0f), Size(20f, 20f))
                    }
                }
            }
            for (value in listOf(0, 1, 0, 1)) {
                runOnIdle { split.intValue = value }
                val pixels = onNodeWithTag("translated-input").captureToImage().toPixelMap()
                assertEquals(Color.Red, pixels[1, 1])
                assertEquals(Color.Red, pixels[19, 19])
                assertEquals(Color.Blue, pixels[21, 21])
                assertEquals(Color.Blue, pixels[39, 39])
            }
        }

    @Test
    fun sampledPassesReuseAndReleaseLayersWhileNativeFiltersRemainFused() = runRenderTest {
        val revision = mutableIntStateOf(0)
        val active = mutableSetOf<GraphicsLayer>()
        var created = 0
        var sourceCalls = 0
        lateinit var renderer: BackdropRenderer
        setContent {
            val native = LocalGraphicsContext.current
            val graphics =
                remember(native) {
                    object : GraphicsContext by native {
                        override fun createGraphicsLayer() =
                            native.createGraphicsLayer().also {
                                active += it
                                created++
                            }

                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            assertTrue(active.remove(layer))
                            native.releaseGraphicsLayer(layer)
                        }
                    }
                }
            renderer = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            Canvas(Modifier.size(80.dp).testTag("retained-input")) {
                val value = revision.intValue
                val graph =
                    BackdropRenderScope(this, Size(40f, 40f), RectangleShape, layoutDirection)
                graph.effects { blur(2f) }
                graph.effects {
                    if (value < 3) progressiveBlur(4f + value, BackdropEdge.Start) else blur(3f)
                }
                graph.effects { blur(2f) }
                renderer.resolve(graph, Rect(0f, 0f, 40f, 40f))
                renderer.beginFrame()
                sourceCalls = 0
                renderer.draw(this) { bounds ->
                    sourceCalls++
                    drawRect(Color.Red, bounds.topLeft, bounds.size)
                }
            }
        }
        val node = onNodeWithTag("retained-input")
        node.captureToImage()
        val initialCreated = created
        val sampledLayers = if (requiresSampledEffectInputImage) 3 else 1
        assertEquals(sampledLayers, active.size)
        for (value in listOf(1, 2)) {
            runOnIdle { revision.intValue = value }
            node.captureToImage()
            assertEquals(sampledLayers, active.size)
            assertEquals(initialCreated, created)
            assertEquals(1, sourceCalls)
        }
        runOnIdle { revision.intValue = 3 }
        node.captureToImage()
        assertEquals(1, active.size)
        runOnIdle { revision.intValue = 0 }
        node.captureToImage()
        assertEquals(sampledLayers, active.size)
        runOnIdle { renderer.refresh() }
        assertTrue(active.isEmpty())
        runOnIdle { revision.intValue = 1 }
        node.captureToImage()
        assertEquals(sampledLayers, active.size)
        runOnIdle { renderer.close() }
        assertTrue(active.isEmpty())
    }

    private fun BackdropEffectScope.translatedShader(dx: Float, dy: Float) {
        runtimeShaderEffect(
            key = "translation",
            shaderString =
                """
                uniform shader content;
                uniform float2 offset;
                half4 main(float2 p) { return content.eval(p + offset); }
                """
                    .trimIndent(),
            uniformShaderName = "content",
            sampling = BackdropSampling.translated(dx, dy),
        ) {
            setFloatUniform("offset", dx, dy)
        }
    }
}
