package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.effects.runtimeShaderEffect
import top.ltfan.backdrop.internal.BackdropGraphRenderer

class CustomPrimitiveTest : SkikoGraphicsTest() {
    @Test
    fun independentSamplingAndOutputBoundsResolveBeforePreparation() {
        val stats = Stats()
        val scope = scope()
        val output = Rect(-5f, -2f, 25f, 20f)
        with(scope) {
            group {
                primitive(
                    Probe(stats, box = output),
                    listOf(
                        BackdropPrimitiveInput(input, BackdropSampling.translated(40f, -10f)),
                        BackdropPrimitiveInput(input, BackdropSampling.outsets(2f, 3f, 4f, 5f)),
                    ),
                )
            }
        }
        val renderer = BackdropGraphRenderer {
            error("CPU-only preparation must not allocate layers")
        }
        renderer.resolve(scope, Rect(0f, 0f, 20f, 20f))
        assertEquals(output, renderer.visible)
        assertEquals(BackdropExtension(7f, 12f, 45f, 5f), renderer.extension)
        assertEquals(output, stats.context!!.outputBounds)
        assertEquals(
            listOf(Rect(35f, -12f, 65f, 10f), Rect(-7f, -5f, 29f, 25f)),
            stats.context!!.inputBounds,
        )
        assertEquals(renderer.extension, stats.context!!.extension)
        assertEquals(1, stats.prepares)
        assertFailsWith<IllegalStateException> {
            (stats.context as BackdropPrimitiveDrawContext).input(0)
        }
        renderer.release()
        assertEquals(1, stats.releases)
    }

    @Test
    fun transparentGroupsDoNotExpandPrimitiveOutputDomains() {
        val stats = Stats()
        val output = Rect(30f, 40f, 50f, 60f)
        val scope = scope().apply { group { primitive(Probe(stats, box = output)) } }
        val renderer = BackdropGraphRenderer {
            error("Bounds resolution must not acquire a graphics context")
        }
        renderer.resolve(scope, Rect(0f, 0f, 20f, 20f))
        assertEquals(output, renderer.visible)
        renderer.release()
    }

    @Test
    fun unusedEmptyAndInvalidPrimitivesDoNotConstructRenderers() {
        for (box in listOf(Rect.Zero, Rect(Float.NaN, 0f, 1f, 1f), Rect(2f, 0f, 1f, 1f))) {
            val stats = Stats()
            val scope = scope()
            scope.image { primitive(Probe(stats, box = box)) }
            val renderer = BackdropGraphRenderer {
                error("Unused primitive must not allocate resources")
            }
            renderer.resolve(scope, Rect(0f, 0f, 20f, 20f))
            assertEquals(0, stats.creates)
            renderer.release()
        }
        val emptyStats = Stats()
        val empty = scope().apply { group { primitive(Probe(emptyStats, box = Rect.Zero)) } }
        val renderer = BackdropGraphRenderer {
            error("Empty primitive must not allocate resources")
        }
        renderer.resolve(empty, Rect(0f, 0f, 20f, 20f))
        assertEquals(0, emptyStats.creates)
        assertTrue(renderer.visible.isEmpty)
        for (box in listOf(Rect(Float.NaN, 0f, 1f, 1f), Rect(2f, 0f, 1f, 1f))) {
            val stats = Stats()
            val invalid = scope().apply { group { primitive(Probe(stats, box = box)) } }
            assertFailsWith<IllegalArgumentException> {
                renderer.resolve(invalid, Rect(0f, 0f, 20f, 20f))
            }
            assertEquals(0, stats.creates)
        }
    }

    @Test
    fun resolutionClosesTheDeclarationAndRejectsLateGraphMutation() {
        val scope = scope()
        val renderer = BackdropGraphRenderer {
            error("Declaration validation must not allocate resources")
        }
        renderer.resolve(scope, Rect(0f, 0f, 20f, 20f))
        assertFailsWith<IllegalStateException> { scope.source() }
        assertFailsWith<IllegalStateException> { scope.paint { drawRect(Color.Red) } }
        assertFailsWith<IllegalStateException> { scope.export() }
        renderer.release()
    }

    @Test
    fun foreignImagesAndInvalidSamplingFailBeforeConstruction() {
        val stats = Stats()
        val first = scope()
        val second = scope()
        assertFailsWith<IllegalArgumentException> {
            second.primitive(Probe(stats), listOf(BackdropPrimitiveInput(first.input)))
        }
        first.group {
            primitive(
                Probe(stats),
                listOf(BackdropPrimitiveInput(input) { Rect(0f, 0f, Float.NaN, 1f) }),
            )
        }
        val renderer = BackdropGraphRenderer {
            error("Invalid sampling must not allocate resources")
        }
        assertFailsWith<IllegalArgumentException> {
            renderer.resolve(first, Rect(0f, 0f, 20f, 20f))
        }
        assertEquals(0, stats.creates)
    }

    @Test
    fun resourcesSurviveUpdatesAndShaderCachesSurviveRefreshButNotOmission() = runComposeUiTest {
        val stats = Stats()
        val color = mutableStateOf(Color.Red)
        val enabled = mutableStateOf(true)
        val attached = mutableStateOf(true)
        val key = mutableIntStateOf(0)
        val epoch = mutableIntStateOf(0)
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Black) }
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                if (attached.value)
                    Box(
                        Modifier.size(40.dp).testTag("surface").renderBackdrop(
                            source,
                            { RectangleShape },
                        ) {
                            group {
                                if (enabled.value)
                                    primitive(
                                        Probe(
                                            stats,
                                            color.value,
                                            key = key.intValue,
                                            ownLayer = true,
                                        ),
                                    )
                            }
                        },
                    )
            }
        }
        waitForIdle()
        assertEquals(1, stats.creates)
        val firstLayer = stats.layers.last()
        val firstShader = stats.shaders.last()
        runOnIdle { color.value = Color.Blue }
        waitForIdle()
        assertEquals(1, stats.creates)
        assertTrue(onNodeWithTag("surface").captureToImage().toPixelMap()[20, 20].blue > .98f)
        runOnIdle { epoch.intValue++ }
        waitForIdle()
        assertEquals(1, stats.refreshes)
        assertTrue(firstLayer.isReleased)
        assertSame(firstShader, stats.shaders.last())
        assertEquals(1, stats.creates)
        runOnIdle { key.intValue++ }
        waitForIdle()
        assertEquals(2, stats.creates)
        assertEquals(1, stats.releases)
        assertNotSame(firstShader, stats.shaders.last())
        runOnIdle { enabled.value = false }
        waitForIdle()
        assertEquals(2, stats.releases)
        assertTrue(stats.layers.all { it.isReleased })
        assertFailsWith<IllegalStateException> { stats.resources!!.createGraphicsLayer() }
        runOnIdle { enabled.value = true }
        waitForIdle()
        assertEquals(3, stats.creates)
        runOnIdle { attached.value = false }
        waitForIdle()
        assertEquals(3, stats.releases)
        assertTrue(stats.layers.all { it.isReleased })
    }

    @Test
    fun clearAndAlphaAreIsolatedAndOutputExtensionIsVisible() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Green) }
            Box(Modifier.size(80.dp).background(Color.Blue).testTag("scene")) {
                Box(
                    Modifier.size(40.dp).renderBackdrop(source, { RectangleShape }) {
                        primitive(
                            Probe(
                                Stats(),
                                Color.Red,
                                box = Rect(-10f, -10f, 60f, 60f),
                                erase = true,
                            ),
                            alpha = .5f,
                        )
                    },
                )
            }
        }
        val pixels = onNodeWithTag("scene").captureToImage().toPixelMap()
        assertTrue(pixels[5, 5].green > .98f, "primitive Clear cannot erase its destination")
        assertEquals(.5f, pixels[30, 30].red, .02f)
        assertEquals(.5f, pixels[30, 30].green, .02f)
        assertEquals(.5f, pixels[50, 30].red, .02f)
        assertEquals(.5f, pixels[50, 30].blue, .02f)
    }

    @Test
    fun borrowedInputsExpireAndSharedDependenciesRecordOnce() = runComposeUiTest {
        val color = mutableStateOf(Color.Red)
        val stats = Stats()
        var records = 0
        setContent {
            val source = rememberCanvasBackdrop {
                records++
                drawRect(color.value)
            }
            Box(
                Modifier.size(40.dp).testTag("surface").renderBackdrop(source, { RectangleShape }) {
                    group {
                        primitive(
                            Probe(stats, copyInputs = true),
                            listOf(BackdropPrimitiveInput(input), BackdropPrimitiveInput(input)),
                        )
                    }
                },
            )
        }
        waitForIdle()
        val before = records
        runOnIdle { color.value = Color.Blue }
        waitForIdle()
        assertEquals(before + 1, records)
        assertTrue(stats.expiredChecks > 0)
        assertTrue(onNodeWithTag("surface").captureToImage().toPixelMap()[20, 20].blue > .98f)
        assertFailsWith<IllegalStateException> { stats.previousInput!!.draw(stats.drawScope!!) }
    }

    @Test
    fun receiversOwnIndependentInstancesAndCannotReleaseEachOthersLayers() = runComposeUiTest {
        val stats = Stats()
        val attached = mutableStateOf(true)
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Black) }
            val shared =
                Modifier.size(40.dp).renderBackdrop(source, { RectangleShape }) {
                    group { primitive(Probe(stats, ownLayer = true)) }
                }
            if (attached.value) {
                Box(shared)
                Box(shared)
            }
        }
        waitForIdle()
        assertEquals(2, stats.creates)
        assertNotSame(stats.shaders[0], stats.shaders[1])
        assertFailsWith<IllegalStateException> {
            stats.resources!!.releaseGraphicsLayer(stats.layers.first())
        }
        assertTrue(stats.layers.none { it.isReleased })
        runOnIdle { attached.value = false }
        waitForIdle()
        assertEquals(2, stats.releases)
        assertTrue(stats.layers.all { it.isReleased })
    }

    @Test
    fun foregroundDrawsWhenTheBackgroundHasAnEmptyDomain() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            Box(
                Modifier.size(40.dp).testTag("surface").renderBackdrop(source, { RectangleShape }) {
                    group {}
                    foreground { primitive(Probe(Stats(), Color.Red)) }
                },
            )
        }
        val pixel = onNodeWithTag("surface").captureToImage().toPixelMap()[20, 20]
        assertTrue(pixel.red > .98f && pixel.blue < .01f)
    }

    @Test
    fun filteredInputMatchesEffectStagesWithInsetsAndPremultipliedAlpha() = runComposeUiTest {
        setContent {
            val source = rememberCanvasBackdrop {
                for (x in 0 until size.width.toInt() step 4) drawRect(
                    if (x / 4 % 2 == 0) Color.Red.copy(alpha = .6f)
                    else Color.Blue.copy(alpha = .4f),
                    Offset(x.toFloat(), 0f),
                    Size(4f, size.height),
                )
            }
            Row {
                Box(
                    Modifier.size(40.dp).testTag("primitive").renderBackdrop(
                        source,
                        { RectangleShape },
                        insets = {
                            BackdropInsets(
                                left = 3.5.dp,
                                top = 5.25.dp,
                                right = 7.75.dp,
                                bottom = 9.5.dp,
                            )
                        },
                    ) {
                        group { primitive(FilteredCopy(), listOf(BackdropPrimitiveInput(input))) }
                    },
                )
                Box(
                    Modifier.size(40.dp).testTag("effect").renderBackdrop(
                        source,
                        { RectangleShape },
                        insets = {
                            BackdropInsets(
                                left = 3.5.dp,
                                top = 5.25.dp,
                                right = 7.75.dp,
                                bottom = 9.5.dp,
                            )
                        },
                    ) {
                        effects {
                            runtimeShaderEffect(
                                "gain",
                                GainShader,
                                "content",
                                BackdropSampling.Identity,
                            ) {
                                setFloatUniform("gain", .5f)
                            }
                        }
                    },
                )
            }
        }
        val primitive = onNodeWithTag("primitive").captureToImage().toPixelMap()
        val effect = onNodeWithTag("effect").captureToImage().toPixelMap()
        for (x in 1..38) for (y in 1..38) {
            assertEquals(effect[x, y].red, primitive[x, y].red, .02f, "red ($x,$y)")
            assertEquals(effect[x, y].blue, primitive[x, y].blue, .02f, "blue ($x,$y)")
            assertEquals(effect[x, y].alpha, primitive[x, y].alpha, .02f, "alpha ($x,$y)")
        }
    }

    @Test
    fun failingFactoriesAndPreparationReleaseOwnedLayersAndPropagate() = runComposeUiTest {
        lateinit var graphics: GraphicsContext
        setContent { graphics = LocalGraphicsContext.current }
        waitForIdle()
        runOnIdle {
            for (creation in listOf(true, false)) {
                val stats = Stats()
                val scope =
                    scope().apply {
                        group {
                            primitive(
                                Probe(
                                    stats,
                                    ownLayer = true,
                                    failCreation = creation,
                                    failPrepare = !creation,
                                ),
                            )
                        }
                    }
                val renderer = BackdropGraphRenderer { graphics }
                val failure =
                    assertFailsWith<IllegalStateException> {
                        renderer.resolve(scope, Rect(0f, 0f, 20f, 20f))
                    }
                assertEquals(
                    if (creation) "creation failed" else "preparation failed",
                    failure.message,
                )
                assertTrue(stats.layers.single().isReleased)
                assertEquals(if (creation) 0 else 1, stats.releases)
                renderer.release()
                assertEquals(if (creation) 0 else 1, stats.releases)
            }
        }
    }

    private fun scope(): BackdropRenderScope =
        BackdropRenderScope(1f, 1f, Size(20f, 20f), RectangleShape, LayoutDirection.Ltr)

    private class FilteredCopy : BackdropPrimitive<FilteredCopy> {
        override fun bounds(geometry: BackdropPrimitiveGeometry): Rect = geometry.surfaceBounds

        override fun createRenderer(
            resources: BackdropPrimitiveResources,
        ): BackdropPrimitiveRenderer<FilteredCopy> =
            object : BackdropPrimitiveRenderer<FilteredCopy> {
                private var layer: GraphicsLayer? = null
                private val shader = resources.obtainRuntimeShader("gain", GainShader)

                override fun prepare(primitive: FilteredCopy, context: BackdropPrimitiveContext) {
                    if (layer == null) layer = resources.createGraphicsLayer()
                }

                override fun draw(
                    scope: DrawScope,
                    primitive: FilteredCopy,
                    context: BackdropPrimitiveDrawContext,
                ) {
                    val outputBounds = context.outputBounds
                    val bounds =
                        Rect(
                            floor(outputBounds.left),
                            floor(outputBounds.top),
                            ceil(outputBounds.right),
                            ceil(outputBounds.bottom),
                        )
                    val origin = Offset(-bounds.left, -bounds.top)
                    val input = context.input(0)
                    val output = checkNotNull(layer)
                    output.topLeft = IntOffset(bounds.left.toInt(), bounds.top.toInt())
                    shader.setFloatUniform("gain", .5f)
                    output.renderEffect = input.runtimeShaderEffect(shader, "content", origin)
                    output.record(
                        context.density,
                        context.layoutDirection,
                        IntSize(ceil(bounds.width).toInt(), ceil(bounds.height).toInt()),
                    ) {
                        translate(origin.x, origin.y) { input.draw(this) }
                    }
                    with(scope) { drawLayer(output) }
                }

                override fun refresh() {
                    layer = null
                }
            }
    }

    private class Stats {
        var creates = 0
        var prepares = 0
        var refreshes = 0
        var releases = 0
        var expiredChecks = 0
        var context: BackdropPrimitiveContext? = null
        var resources: BackdropPrimitiveResources? = null
        val layers = mutableListOf<GraphicsLayer>()
        val shaders = mutableListOf<RuntimeShader>()
        var previousInput: BackdropPrimitiveImage? = null
        var drawScope: DrawScope? = null
    }

    private class Probe(
        private val stats: Stats,
        private val color: Color = Color.Red,
        private val box: Rect? = null,
        key: Int = 0,
        private val ownLayer: Boolean = false,
        private val erase: Boolean = false,
        private val copyInputs: Boolean = false,
        private val failCreation: Boolean = false,
        private val failPrepare: Boolean = false,
    ) : BackdropPrimitive<Probe> {
        override val rendererKey: Any = key

        override fun bounds(geometry: BackdropPrimitiveGeometry): Rect =
            box ?: geometry.surfaceBounds

        override fun createRenderer(
            resources: BackdropPrimitiveResources,
        ): BackdropPrimitiveRenderer<Probe> {
            stats.creates++
            stats.resources = resources
            var layer =
                if (ownLayer) resources.createGraphicsLayer().also { stats.layers += it } else null
            if (failCreation) error("creation failed")
            return object : BackdropPrimitiveRenderer<Probe> {
                override fun prepare(primitive: Probe, context: BackdropPrimitiveContext) {
                    stats.prepares++
                    stats.context = context
                    if (ownLayer) {
                        if (layer == null)
                            layer = resources.createGraphicsLayer().also { stats.layers += it }
                        stats.shaders +=
                            resources.obtainRuntimeShader(
                                "test",
                                "half4 main(float2 p) { return half4(1.0); }",
                            )
                    }
                    if (primitive.failPrepare) error("preparation failed")
                }

                override fun draw(
                    scope: DrawScope,
                    primitive: Probe,
                    context: BackdropPrimitiveDrawContext,
                ) {
                    if (primitive.copyInputs) {
                        stats.previousInput?.let {
                            assertFailsWith<IllegalStateException> { it.draw(scope) }
                            stats.expiredChecks++
                        }
                        context.input(0).draw(scope)
                        context.input(1).draw(scope)
                        stats.previousInput = context.input(0)
                        stats.drawScope = scope
                    } else
                        with(scope) {
                            val bounds = context.outputBounds
                            drawRect(primitive.color, bounds.topLeft, bounds.size)
                            if (primitive.erase)
                                drawRect(
                                    Color.Transparent,
                                    Offset.Zero,
                                    Size(10f, 10f),
                                    blendMode = BlendMode.Clear,
                                )
                        }
                }

                override fun refresh() {
                    stats.refreshes++
                    layer = null
                }

                override fun release() {
                    stats.releases++
                    layer = null
                }
            }
        }
    }
}

private const val GainShader =
    "uniform shader content; uniform float gain; half4 main(float2 p) { half4 c = content.eval(p); return half4(c.rgb * gain, c.a); }"
