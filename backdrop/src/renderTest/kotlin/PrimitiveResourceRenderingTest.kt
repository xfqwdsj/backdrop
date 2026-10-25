package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop

class PrimitiveResourceRenderingTest {
    private class Stats {
        val layers = mutableListOf<GraphicsLayer>()
        val shaders = mutableListOf<RuntimeShader>()
        var disposed = 0
        var brushCreations = 0
        var context: BackdropPrimitiveDrawContext? = null
        var pool: BackdropResourcePool<Int, GraphicsLayer>? = null
    }

    private data class Panel(val stats: Stats, val color: Color) : BackdropPrimitive<Panel> {
        override fun bounds(geometry: BackdropGeometry): Rect = geometry.surfaceBounds

        override fun createRenderer(
            resources: BackdropRenderResources,
        ): BackdropPrimitiveRenderer<Panel> =
            object : BackdropPrimitiveRenderer<Panel> {
                var brush: Brush? = null
                var brushColor = Color.Unspecified
                val layers =
                    resources
                        .resourcePool<Int, GraphicsLayer>(dispose = { stats.disposed++ }) {
                            stats.shaders +=
                                obtainRuntimeShader(
                                    "shader",
                                    "half4 main(float2 p) { return half4(1.0); }",
                                )
                            createGraphicsLayer().also { stats.layers += it }
                        }
                        .also { stats.pool = it }

                override fun prepare(primitive: Panel, context: BackdropPrimitiveContext) {
                    layers.getOrCreate(0)
                    if (brush == null || brushColor != primitive.color) {
                        brushColor = primitive.color
                        val color = primitive.color
                        brush =
                            object : ShaderBrush() {
                                override fun createShader(size: Size): Shader {
                                    stats.brushCreations++
                                    return LinearGradientShader(
                                        Offset.Zero,
                                        Offset(size.width, 0f),
                                        listOf(color, Color.Transparent),
                                    )
                                }
                            }
                    }
                }

                override fun draw(
                    scope: DrawScope,
                    primitive: Panel,
                    context: BackdropPrimitiveDrawContext,
                ) {
                    val layer = layers.getOrCreate(0)
                    context.recordLayer(layer) {
                        assertEquals(context.size, size)
                        assertEquals(context.size, drawContext.size)
                        context.input(0).draw(this)
                        drawRect(checkNotNull(brush))
                    }
                    assertEquals(
                        Offset(-context.extension.left, -context.extension.top),
                        Offset(layer.topLeft.x.toFloat(), layer.topLeft.y.toFloat()),
                    )
                    stats.context = context
                    with(scope) { drawLayer(layer) }
                }

                override fun refresh() {
                    brush = null
                }
            }
    }

    @Test
    fun failedPoolFactoryReleasesDirectAndNestedLayers() = runRenderTest {
        lateinit var resources: PrimitiveResources
        val allocated = mutableListOf<GraphicsLayer>()
        setContent {
            val graphics = LocalGraphicsContext.current
            val owner = remember(graphics) { PrimitiveResources { graphics } }
            SideEffect { resources = owner }
        }
        waitForIdle()
        runOnIdle {
            val pool =
                resources.resourcePool<Int, GraphicsLayer> { key ->
                    createGraphicsLayer().also { allocated += it }
                    resourcePool<Int, GraphicsLayer> {
                            createGraphicsLayer().also { allocated += it }
                        }
                        .getOrCreate(key)
                    error("construction")
                }
            assertFailsWith<IllegalStateException> { pool.getOrCreate(0) }
            assertEquals(2, allocated.size)
            assertTrue(allocated.all { it.isReleased })
            assertEquals(0, pool.size)
            resources.close()
        }
    }

    @Test
    fun pooledLayersRecordInSurfaceCoordinatesAndFollowOwnerLifecycle() = runRenderTest {
        val stats = Stats()
        val epoch = mutableIntStateOf(0)
        val enabled = mutableStateOf(true)
        val attached = mutableStateOf(true)
        val color = mutableStateOf(Color.Red)
        val padding = mutableIntStateOf(3)
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Black.copy(alpha = .375f)) }
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                if (attached.value)
                    Box(
                        Modifier.size(48.dp, 32.dp).testTag("pooled").drawBackdrop(
                            source,
                            { RectangleShape },
                            insets = { BackdropInsets(4.dp, 6.dp, 8.dp, 10.dp) },
                        ) {
                            group {
                                if (enabled.value)
                                    primitive(
                                        Panel(stats, color.value),
                                        listOf(
                                            BackdropPrimitiveInput(
                                                input,
                                                BackdropSampling.outsets(
                                                    padding.intValue.toFloat(),
                                                    5f,
                                                    7f,
                                                    9f,
                                                ),
                                            ),
                                        ),
                                    )
                            }
                        },
                    )
            }
        }
        val first = onNodeWithTag("pooled").captureToImage().toPixelMap()
        assertTrue(first[2, 16].red > .9f)
        assertTrue(first[45, 16].red < .2f)
        val layer = stats.layers.single()
        val shader = stats.shaders.single()
        assertEquals(1, stats.brushCreations)
        val recordingWidths = mutableSetOf(layer.size.width)
        for (extra in listOf(6, 12, 24, 3)) {
            runOnIdle { padding.intValue = extra }
            onNodeWithTag("pooled").captureToImage()
            assertEquals(
                1,
                stats.brushCreations,
                "Sampling extension invalidated the surface-sized brush shader",
            )
            assertSame(layer, stats.layers.single())
            recordingWidths += layer.size.width
        }
        assertTrue(recordingWidths.size > 1, "The probe must exercise changing storage dimensions")
        runOnIdle { color.value = Color.Blue }
        val updated = onNodeWithTag("pooled").captureToImage().toPixelMap()
        assertTrue(updated[2, 16].blue > .9f)
        assertSame(layer, stats.layers.single())
        assertFailsWith<IllegalStateException> { checkNotNull(stats.context).recordLayer(layer) {} }
        runOnIdle { epoch.intValue++ }
        onNodeWithTag("pooled").captureToImage()
        assertTrue(layer.isReleased)
        assertEquals(2, stats.layers.size)
        assertSame(shader, stats.shaders.last())
        assertEquals(1, stats.disposed)
        runOnIdle { enabled.value = false }
        onNodeWithTag("pooled").captureToImage()
        assertTrue(stats.layers.all { it.isReleased })
        assertEquals(2, stats.disposed)
        assertFailsWith<IllegalStateException> { checkNotNull(stats.pool).getOrCreate(0) }
        runOnIdle { enabled.value = true }
        onNodeWithTag("pooled").captureToImage()
        runOnIdle { attached.value = false }
        waitForIdle()
        assertEquals(3, stats.disposed)
        assertTrue(stats.layers.all { it.isReleased })
    }
}
