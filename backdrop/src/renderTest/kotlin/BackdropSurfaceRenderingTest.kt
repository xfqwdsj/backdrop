package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.OffsetEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.effects.effect

class BackdropSurfaceRenderingTest {
    @Test
    fun movingTheReceiverRetainsItsResolvedEffectChain() = runRenderTest {
        val position = mutableIntStateOf(0)
        var resolutions = 0
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            val surface =
                remember(source) {
                    Modifier.drawBackdrop(source, { RectangleShape }) {
                        surface {
                            effects { resolutions++ }
                        }
                    }
                }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                Box(Modifier.offset { IntOffset(position.intValue, 0) }.size(40.dp).then(surface))
            }
        }
        waitForIdle()
        val initial = resolutions
        repeat(3) { i ->
            runOnIdle { position.intValue = i + 1 }
            waitForIdle()
        }
        assertEquals(
            initial,
            resolutions,
            "Position changes update sampling without resolving optics",
        )
    }

    @Test
    fun serialSamplingUnionsIntermediateRegionsBeforeDeferredConstruction() = runRenderTest {
        var requested = Rect.Zero
        val built = mutableListOf<BackdropExtension>()
        setContent {
            val source = rememberCanvasBackdrop {
                requested = samplingBounds
                drawRect(Color.Blue, samplingBounds.topLeft, samplingBounds.size)
            }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                Box(
                    Modifier.size(40.dp).testTag("sampling").drawBackdrop(
                        source,
                        { RectangleShape },
                    ) {
                        surface {
                            effects {
                                effect(BackdropSampling.translated(70f, 0f)) {
                                    built += extension
                                    OffsetEffect(-70f, 0f)
                                }
                                effect(BackdropSampling.translated(-50f, 0f)) {
                                    built += extension
                                    OffsetEffect(50f, 0f)
                                }
                            }
                        }
                    },
                )
            }
        }
        waitForIdle()
        assertEquals(Rect(20f, 0f, 60f, 40f), requested)
        assertEquals(Color.Blue, onNodeWithTag("sampling").captureToImage().toPixelMap()[20, 20])
        assertTrue(built.isNotEmpty())
        assertTrue(
            built.all { it == BackdropExtension(left = 50f, right = 20f) },
            "Every deferred stage sees the union of output and intermediate sampling",
        )
    }

    @Test
    fun detachingClosesTheDecorationWithoutADisplayRefresh() = runRenderTest {
        val attached = mutableStateOf(true)
        val calls = DrawingCalls()
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                if (attached.value)
                    Box(
                        Modifier.size(40.dp).drawBackdrop(source, { RectangleShape }) {
                            surface {
                                drawSurface(TestDecoration(Color.Red, calls))
                            }
                        },
                    )
            }
        }
        waitForIdle()
        assertEquals(1, calls.created)
        runOnIdle { attached.value = false }
        waitForIdle()
        assertEquals(1, calls.released)
        assertEquals(0, calls.refreshed)
    }

    @Test
    fun surfaceChildrenAndForegroundDrawInTheirFixedPhases() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                Box(
                    Modifier.size(40.dp)
                        .testTag("surface")
                        .drawBackdrop(source, { RectangleShape }) {
                            surface {
                                paintSurface { drawRect(Color.Red, size = Size(16f, 16f)) }
                                paintForeground { drawRect(Color.White, size = Size(4f, 4f)) }
                            }
                        }
                        .drawBehind { drawRect(Color.Black, size = Size(8f, 8f)) },
                )
            }
        }
        val image = onNodeWithTag("surface").captureToImage().toPixelMap()
        assertEquals(Color.White, image[2, 2])
        assertEquals(Color.Black, image[6, 6])
        assertEquals(Color.Red, image[12, 12])
        assertEquals(Color.Blue, image[24, 24])
    }

    @Test
    fun backgroundAlphaAppliesOnceToOverlappingSurfaceDraws() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Red) }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                Box(
                    Modifier.size(40.dp).testTag("alpha").drawBackdrop(
                        source,
                        { RectangleShape },
                        backgroundAlpha = { 0.5f },
                    ) {
                        surface {
                            fill(Color.Green)
                            paintSurface { drawRect(Color.Blue, size = Size(20f, 40f)) }
                        }
                    },
                )
            }
        }
        val pixels = onNodeWithTag("alpha").captureToImage().toPixelMap()
        assertEquals(.5f, pixels[10, 20].alpha, .01f)
        assertEquals(.5f, pixels[30, 20].alpha, .01f)
        assertTrue(pixels[10, 20].blue > .98f)
        assertTrue(pixels[30, 20].green > .98f)
    }

    @Test
    fun statefulDecorationRetainsItsOwnerAndExpiresBorrowedInputs() = runRenderTest {
        val color = mutableStateOf(Color.Red)
        val shown = mutableStateOf(true)
        val epoch = mutableIntStateOf(0)
        val calls = DrawingCalls()
        var compositions = 0
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            SideEffect { compositions++ }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                    Box(
                        Modifier.size(40.dp).testTag("drawing").drawBackdrop(
                            source,
                            { RectangleShape },
                        ) {
                            surface {
                                if (shown.value) drawSurface(TestDecoration(color.value, calls))
                            }
                        },
                    )
                }
            }
        }
        fun sample() = onNodeWithTag("drawing").captureToImage().toPixelMap()[20, 20]
        assertEquals(Color.Red, sample())
        assertEquals(1, calls.created)
        val initial = compositions
        runOnIdle { color.value = Color.Green }
        waitForIdle()
        assertEquals(Color.Green, sample())
        assertEquals(1, calls.created)
        assertEquals(initial, compositions)
        val retainedLayer = calls.layer
        runOnIdle { color.value = Color.Transparent }
        waitForIdle()
        assertEquals(Color.Blue, sample())
        assertEquals(1, calls.created)
        assertEquals(0, calls.released)
        runOnIdle { color.value = Color.Green }
        waitForIdle()
        assertEquals(Color.Green, sample())
        assertSame(retainedLayer, calls.layer)
        assertFailsWith<IllegalStateException> { calls.input!!.bounds }
        assertFailsWith<IllegalStateException> {
            calls.context!!.recordLayer(calls.layer!!) { drawRect(Color.Red) }
        }
        val shader = calls.shader
        runOnIdle { epoch.intValue++ }
        waitForIdle()
        assertEquals(Color.Green, sample())
        assertEquals(1, calls.created)
        assertEquals(1, calls.refreshed)
        assertSame(shader, calls.shader)
        runOnIdle { shown.value = false }
        waitForIdle()
        assertEquals(Color.Blue, sample())
        assertEquals(1, calls.released)
    }

    @Test
    fun surfaceDeclarationsFreezeBeforeDrawing() = runRenderTest {
        lateinit var declaration: BackdropSurfaceScope
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            ProvideBackdropStyle(BackdropStyle.NoEffects) {
                Box(
                    Modifier.size(40.dp).drawBackdrop(source, { RectangleShape }) {
                        surface {
                            declaration = this
                        }
                    },
                )
            }
        }
        waitForIdle()
        assertFailsWith<IllegalStateException> { declaration.fill(Color.Red) }
        assertFailsWith<IllegalStateException> { declaration.effects {} }
        assertEquals(Size(40f, 40f), declaration.size)
    }

    private class DrawingCalls {
        var created = 0
        var refreshed = 0
        var released = 0
        var input: BackdropImageInput? = null
        var context: BackdropDecorationDrawContext? = null
        var layer: GraphicsLayer? = null
        var shader: RuntimeShader? = null
    }

    private class TestDecoration(val color: Color, private val calls: DrawingCalls) :
        BackdropDecoration<TestDecoration> {
        override fun sampling(geometry: BackdropGeometry): BackdropSampling =
            BackdropSampling.Identity

        override fun createRenderer(
            resources: BackdropRenderResources,
        ): BackdropDecorationRenderer<TestDecoration> {
            calls.created++
            return object : BackdropDecorationRenderer<TestDecoration> {
                private var layer: GraphicsLayer? = null

                override fun prepare(
                    decoration: TestDecoration,
                    context: BackdropDecorationContext,
                ) {
                    if (layer == null) layer = resources.createGraphicsLayer()
                    if (isRuntimeShaderSupported())
                        calls.shader =
                            resources.obtainRuntimeShader(
                                "probe",
                                "half4 main(float2 p) {return half4(1);}",
                            )
                }

                override fun draw(
                    scope: DrawScope,
                    decoration: TestDecoration,
                    context: BackdropDecorationDrawContext,
                ) {
                    val input = context.processedSource
                    calls.input = input
                    calls.context = context
                    calls.layer = layer
                    context.recordLayer(layer!!) {
                        input.draw(this)
                        drawRect(decoration.color)
                    }
                    with(scope) { drawLayer(layer!!) }
                }

                override fun refresh() {
                    calls.refreshed++
                    layer = null
                }

                override fun release() {
                    calls.released++
                }
            }
        }
    }
}
