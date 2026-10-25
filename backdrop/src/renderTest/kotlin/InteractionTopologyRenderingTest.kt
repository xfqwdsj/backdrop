package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.interaction.InteractionLightEntry
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.interaction.InteractionRegistry
import top.ltfan.backdrop.interaction.LocalInteractionRegistry
import top.ltfan.backdrop.interaction.interactionLight

class InteractionTopologyRenderingTest {
    private class Stats {
        var factories = 0
        var releases = 0
        var layer: GraphicsLayer? = null
    }

    private data class Marker(val stats: Stats, val color: Color) : BackdropPrimitive<Marker> {
        override fun bounds(geometry: BackdropGeometry): Rect = geometry.surfaceBounds

        override fun createRenderer(
            resources: BackdropRenderResources,
        ): BackdropPrimitiveRenderer<Marker> {
            stats.factories++
            val layers =
                resources.resourcePool<Int, GraphicsLayer> {
                    createGraphicsLayer().also { stats.layer = it }
                }
            return object : BackdropPrimitiveRenderer<Marker> {
                override fun prepare(primitive: Marker, context: BackdropPrimitiveContext) {
                    layers.getOrCreate(0)
                }

                override fun draw(
                    scope: DrawScope,
                    primitive: Marker,
                    context: BackdropPrimitiveDrawContext,
                ) {
                    val layer = layers.getOrCreate(0)
                    context.recordLayer(layer) { drawRect(primitive.color) }
                    with(scope) { drawLayer(layer) }
                }

                override fun refresh() {}

                override fun release() {
                    stats.releases++
                }
            }
        }
    }

    @Test
    fun lightingPresenceRetainsLaterRendererAndOwnedLayerUntilDetach() = runRenderTest {
        val stats = Stats()
        val registry = InteractionRegistry()
        val visible = mutableStateOf(true)
        val color = mutableStateOf(Color.Red)
        lateinit var window: Any
        setContent {
            window = LocalWindowInfo.current
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            CompositionLocalProvider(LocalInteractionRegistry provides registry) {
                if (visible.value)
                    Box(
                        Modifier.size(40.dp)
                            .testTag("marker")
                            .drawBackdrop(
                                source,
                                { RectangleShape },
                                render = {
                                    interactionLight(InteractionLighting.Config())
                                    foreground { primitive(Marker(stats, color.value)) }
                                },
                            ),
                    )
            }
        }
        fun sample() = onNodeWithTag("marker").captureToImage().toPixelMap()[20, 20]
        assertTrue(sample().red > .99f)
        val original = stats.layer
        repeat(3) { index ->
            val expected = if (index % 2 == 0) Color.Green else Color.Red
            runOnIdle {
                color.value = expected
                registry.lights +=
                    InteractionLightEntry(
                        Any(),
                        window,
                        20f,
                        20f,
                        40f,
                        Color.White,
                        BackdropCurve.Linear,
                        0f,
                    )
            }
            val lit = sample()
            assertTrue(if (expected == Color.Red) lit.red > .99f else lit.green > .99f)
            assertEquals(1, stats.factories, "A later primitive keeps its compatible renderer")
            assertSame(original, stats.layer)
            runOnIdle { registry.lights.clear() }
            val idle = sample()
            assertTrue(if (expected == Color.Red) idle.red > .99f else idle.green > .99f)
            assertEquals(1, stats.factories)
            assertEquals(0, stats.releases)
        }
        runOnIdle { visible.value = false }
        waitForIdle()
        assertEquals(1, stats.releases, "Detach releases the retained renderer")
    }
}
