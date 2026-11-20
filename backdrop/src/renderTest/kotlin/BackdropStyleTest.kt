package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

class BackdropRenderStyleTest {
    @Test
    fun rootDefaultsAndNestedProvidersKeepOmittedProducers() = runRenderTest {
        lateinit var root: BackdropRenderStyle
        lateinit var child: BackdropRenderStyle
        val render: BackdropRenderScope.() -> Unit = { effects { blur(2f) } }
        val parent = BackdropRenderStyle(render = render)
        val insets: () -> BackdropInsets = { BackdropInsets.None }
        setContent {
            root = LocalBackdropRenderStyle.current
            ProvideBackdropRenderStyle(parent) {
                ProvideBackdropRenderStyle(insets = insets) {
                    child = LocalBackdropRenderStyle.current
                    Panel("panel")
                }
            }
        }
        waitForIdle()
        assertSame(BackdropRenderStyle.Default, root)
        assertSame(BackdropRenderStyle.NoEffects, root.render)
        assertEquals(BackdropInsets.None, root.insets())
        assertSame(parent.render, child.render)
        assertSame(insets, child.insets)
    }

    @Test
    fun sharedModifierReadsStyleAtItsUsePositionAndNoEffectsSkipsDecorations() = runRenderTest {
        val shared = Modifier.drawBackdrop(TestBackdrop, { RectangleShape })
        setContent {
            Column {
                ProvideBackdropRenderStyle(render = { highlight(solidHighlight()) }) {
                    Panel("red", shared)
                }
                ProvideBackdropRenderStyle(render = { highlight(Highlight.None) }) {
                    Panel("green", shared)
                    Panel(
                        "plain",
                        Modifier.drawBackdrop(
                            TestBackdrop,
                            { RectangleShape },
                            render = BackdropRenderStyle.NoEffects,
                        ),
                    )
                }
            }
        }
        waitForIdle()
        assertColor(Color.White, onNodeWithTag("red").captureToImage().toPixelMap()[0, 20])
        assertColor(Color.Blue, onNodeWithTag("green").captureToImage().toPixelMap()[0, 20])
        assertColor(Color.Blue, onNodeWithTag("plain").captureToImage().toPixelMap()[0, 20])
    }

    @Test
    fun explicitOverridesDoNotInvokeDefaultsOrAppendEffects() = runRenderTest {
        val unused = BackdropRenderStyle(render = { error("Inherited rendering must be replaced") })
        setContent {
            ProvideBackdropRenderStyle(unused) {
                Panel(
                    "panel",
                    Modifier.drawBackdrop(
                        TestBackdrop,
                        { RectangleShape },
                        render = BackdropRenderStyle.NoEffects,
                    ),
                )
            }
        }
        waitForIdle()
        assertColor(Color.Blue, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
    }

    @Test
    fun inheritedHighlightAnimationAndNoneSwitchesDrawWithoutRecomposition() = runRenderTest {
        val highlight = mutableStateOf<Highlight?>(null)
        var compositions = 0
        setContent {
            ProvideBackdropRenderStyle(
                render = { highlight.value?.let { highlight(it) } },
            ) {
                Panel("panel", onComposition = { compositions++ })
            }
        }
        waitForIdle()
        val baseline = compositions
        assertColor(Color.Blue, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
        for (enabled in listOf(true, false, true)) {
            highlight.value = if (enabled) solidHighlight() else Highlight.None
            waitForIdle()
            val edge = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
            if (enabled) assertTrue(edge.red > .9f && edge.blue > .9f)
            else assertColor(Color.Blue, edge)
        }
        assertEquals(baseline, compositions)
    }

    @Test
    fun providerReplacementEnablesDefaultInnerShadowAndRestoresItsBlur() = runRenderTest {
        val config = InnerShadow(radius = 3.dp, offset = DpOffset(0.dp, 5.dp), color = Color.Red)
        val style =
            mutableStateOf(
                BackdropRenderStyle(),
            )
        setContent {
            ProvideBackdropRenderStyle(style.value) { Panel("panel") }
        }
        waitForIdle()
        val blank = onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1]
        assertColor(Color.Blue, blank)
        var enabled: Color? = null
        repeat(3) {
            style.value = style.value.copy(render = { innerShadow(config) })
            waitForIdle()
            val pixel = onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1]
            assertTrue(pixel.red > blank.red + 0.1f)
            enabled?.let { assertColor(it, pixel) }
            enabled = pixel
            style.value = style.value.copy(render = BackdropRenderStyle.NoEffects)
            waitForIdle()
            assertColor(blank, onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1])
        }
    }

    @Test
    fun highlightBrightnessUpdatesReachTheDrawingLayer() = runRenderTest {
        val highlight =
            mutableStateOf(
                Highlight(
                    width = 20.dp,
                    baseBrightness = .2f,
                    environmentGain = 0f,
                    direction = HighlightDirection(angle = 0f),
                ),
            )
        setContent {
            ProvideBackdropRenderStyle(
                render = { highlight(highlight.value) },
            ) {
                Panel("panel")
            }
        }
        waitForIdle()
        val faint = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        assertEquals(.2f, faint.red, .03f)
        highlight.value = highlight.value.copy(baseBrightness = .8f)
        waitForIdle()
        val bright = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        assertEquals(.8f, bright.red, .03f)
    }

    @Test
    fun inheritedInnerShadowAnimationDrawsWithoutRecomposition() = runRenderTest {
        val innerShadow = mutableStateOf<InnerShadow?>(null)
        var compositions = 0
        setContent {
            ProvideBackdropRenderStyle(
                render = { innerShadow.value?.let { innerShadow(it) } },
            ) {
                Panel("panel", onComposition = { compositions++ })
            }
        }
        waitForIdle()
        val baseline = compositions
        val blank = onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1]
        for (radius in listOf(1.dp, 3.dp, 1.dp)) {
            innerShadow.value =
                InnerShadow(
                    radius = radius,
                    offset = DpOffset(0.dp, 5.dp),
                    color = Color.Red,
                )
            waitForIdle()
            assertTrue(onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1].red > 0.1f)
            innerShadow.value = null
            waitForIdle()
            assertColor(blank, onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1])
        }
        assertEquals(baseline, compositions)
    }

    @Test
    fun shadowNoneSwitchesAndZeroAlphaConfigurationRemainDistinct() = runRenderTest {
        val shadow = mutableStateOf<Shadow?>(null)
        val config = Shadow(radius = 2.dp, offset = DpOffset(4.dp, 0.dp), color = Color.Red)
        var compositions = 0
        setContent {
            ProvideBackdropRenderStyle(
                render = {
                    val material = image {
                        source()
                        clipSurface()
                    }
                    group {
                        shadow.value?.let { shadow(it) }
                        draw(material)
                    }
                },
            ) {
                Box(Modifier.testTag("outer").background(Color.White).padding(10.dp)) {
                    Panel("panel", onComposition = { compositions++ })
                }
            }
        }
        waitForIdle()
        val baseline = compositions
        val blank = onNodeWithTag("outer").captureToImage().toPixelMap()[51, 30]
        repeat(3) {
            shadow.value = config
            waitForIdle()
            val pixel = onNodeWithTag("outer").captureToImage().toPixelMap()[51, 30]
            assertTrue(pixel.green < blank.green - 0.1f)
            shadow.value = config.copy(alpha = 0f)
            waitForIdle()
            assertColor(blank, onNodeWithTag("outer").captureToImage().toPixelMap()[51, 30])
            assertTrue(shadow.value is Shadow.Config)
            shadow.value = null
            waitForIdle()
            assertColor(blank, onNodeWithTag("outer").captureToImage().toPixelMap()[51, 30])
        }
        assertEquals(baseline, compositions)
    }

    @Test
    fun effectsAnimationResetsPaddingAndRenderEffectWithoutRecomposition() = runRenderTest {
        val enabled = mutableStateOf(false)
        lateinit var scope: BackdropEffectScopeImpl
        var compositions = 0
        setContent {
            ProvideBackdropRenderStyle(
                render = {
                    group {
                        source()
                        effects {
                            scope = this as BackdropEffectScopeImpl
                            if (enabled.value) blur(3f, TileMode.Decal)
                        }
                        clipSurface()
                    }
                },
            ) {
                Panel("panel", onComposition = { compositions++ })
            }
        }
        waitForIdle()
        val baseline = compositions
        assertTrue(scope.passes.isEmpty())
        assertEquals(BackdropExtension.None, scope.extension)
        val blank = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        enabled.value = true
        waitForIdle()
        assertTrue(scope.passes.isNotEmpty())
        assertEquals(7f, scope.extension.left)
        val blurred = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        assertTrue(blurred.alpha < blank.alpha - 0.1f)
        enabled.value = false
        waitForIdle()
        assertTrue(scope.passes.isEmpty())
        assertEquals(BackdropExtension.None, scope.extension)
        assertColor(blank, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
        assertEquals(baseline, compositions)
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.02f)
        assertEquals(expected.green, actual.green, 0.02f)
        assertEquals(expected.blue, actual.blue, 0.02f)
        assertEquals(expected.alpha, actual.alpha, 0.02f)
    }

    @Composable
    private fun Panel(tag: String, onComposition: () -> Unit = {}) {
        Panel(tag, Modifier.drawBackdrop(TestBackdrop, { RectangleShape }), onComposition)
    }

    @Composable
    private fun Panel(
        tag: String,
        modifier: Modifier,
        onComposition: () -> Unit = {},
    ) {
        SideEffect(onComposition)
        Box(
            Modifier.testTag(tag).size(40.dp).then(modifier),
        )
    }

    private fun solidHighlight(): Highlight =
        Highlight(
            width = 20.dp,
            direction = HighlightDirection.Uniform,
            baseBrightness = 1f,
            environmentGain = 0f,
        )
}

private val TestBackdrop =
    object : Backdrop {
        override val isCoordinatesDependent: Boolean = false

        override fun BackdropDrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?,
        ) {
            drawRect(Color.Blue)
        }
    }
