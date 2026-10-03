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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class BackdropStyleTest {
    @Test
    fun rootDefaultsAndNestedProvidersKeepOmittedProducers() = runComposeUiTest {
        lateinit var root: BackdropStyle
        lateinit var child: BackdropStyle
        val parent = BackdropStyle(highlight = { Highlight.None }, effects = { blur(2f) })
        val inner: () -> InnerShadow = { InnerShadow.Default }
        setContent {
            root = LocalBackdropStyle.current
            ProvideBackdropStyle(parent) {
                ProvideBackdropStyle(innerShadow = inner) {
                    child = LocalBackdropStyle.current
                    Panel("panel")
                }
            }
        }
        waitForIdle()
        assertSame(BackdropStyle.Default, root)
        assertSame(Highlight.Default, root.highlight())
        assertSame(Shadow.Default, root.shadow())
        assertSame(InnerShadow.None, root.innerShadow())
        assertSame(BackdropStyle.NoEffects, root.effects)
        assertSame(parent.effects, child.effects)
        assertSame(parent.highlight, child.highlight)
        assertSame(parent.shadow, child.shadow)
        assertSame(inner, child.innerShadow)
    }

    @Test
    fun sharedModifierReadsStyleAtItsUsePositionAndPlainIgnoresDecorations() = runComposeUiTest {
        val shared =
            Modifier.drawBackdrop(
                backdrop = TestBackdrop,
                shape = { RectangleShape },
                shadow = { Shadow.None },
                innerShadow = { InnerShadow.None },
            )
        setContent {
            Column {
                ProvideBackdropStyle(highlight = { solidHighlight(Color.Red) }) {
                    Panel("red", shared)
                }
                ProvideBackdropStyle(highlight = { solidHighlight(Color.Green) }) {
                    Panel("green", shared)
                    Panel("plain", Modifier.drawPlainBackdrop(TestBackdrop, { RectangleShape }))
                }
            }
        }
        waitForIdle()
        assertColor(Color.Red, onNodeWithTag("red").captureToImage().toPixelMap()[0, 20])
        assertColor(Color.Green, onNodeWithTag("green").captureToImage().toPixelMap()[0, 20])
        assertColor(Color.Blue, onNodeWithTag("plain").captureToImage().toPixelMap()[0, 20])
    }

    @Test
    fun explicitOverridesDoNotInvokeDefaultsOrAppendEffects() = runComposeUiTest {
        val unused =
            BackdropStyle(
                effects = { error("Inherited effects must be replaced") },
                highlight = { error("Inherited highlight must be replaced") },
                shadow = { error("Inherited shadow must be replaced") },
                innerShadow = { error("Inherited inner shadow must be replaced") },
            )
        setContent {
            ProvideBackdropStyle(unused) {
                Panel(
                    "panel",
                    Modifier.drawBackdrop(
                        TestBackdrop,
                        { RectangleShape },
                        effects = BackdropStyle.NoEffects,
                        highlight = { Highlight.None },
                        shadow = { Shadow.None },
                        innerShadow = { InnerShadow.None },
                    ),
                )
            }
        }
        waitForIdle()
        assertColor(Color.Blue, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
    }

    @Test
    fun inheritedHighlightAnimationAndNoneSwitchesDrawWithoutRecomposition() = runComposeUiTest {
        val highlight = mutableStateOf<Highlight>(Highlight.None)
        var compositions = 0
        setContent {
            ProvideBackdropStyle(
                highlight = { highlight.value },
                shadow = { Shadow.None },
            ) {
                Panel("panel", onComposition = { compositions++ })
            }
        }
        waitForIdle()
        val baseline = compositions
        assertColor(Color.Blue, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
        for (color in listOf(Color.Red, Color.Green, Color.Red)) {
            highlight.value = solidHighlight(color)
            waitForIdle()
            assertColor(color, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
            highlight.value = Highlight.None
            waitForIdle()
            assertColor(Color.Blue, onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20])
        }
        assertEquals(baseline, compositions)
    }

    @Test
    fun providerReplacementEnablesDefaultInnerShadowAndRestoresItsBlur() = runComposeUiTest {
        val config = InnerShadow(radius = 3.dp, offset = DpOffset(0.dp, 5.dp), color = Color.Red)
        val style =
            mutableStateOf(
                BackdropStyle(
                    highlight = { Highlight.None },
                    shadow = { Shadow.None },
                )
            )
        setContent {
            ProvideBackdropStyle(style.value) { Panel("panel") }
        }
        waitForIdle()
        val blank = onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1]
        assertColor(Color.Blue, blank)
        var enabled: Color? = null
        repeat(3) {
            style.value = style.value.copy(innerShadow = { config })
            waitForIdle()
            val pixel = onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1]
            assertTrue(pixel.red > blank.red + 0.1f)
            enabled?.let { assertColor(it, pixel) }
            enabled = pixel
            style.value = style.value.copy(innerShadow = { InnerShadow.None })
            waitForIdle()
            assertColor(blank, onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1])
        }
    }

    @Test
    fun ambientColorAlphaAndBlendModeReachTheDrawingLayer() = runComposeUiTest {
        val style =
            mutableStateOf(
                HighlightStyle.Ambient(
                    color = Color.White.copy(alpha = 0.2f),
                    angle = 0f,
                )
            )
        setContent {
            ProvideBackdropStyle(
                highlight = { Highlight(width = 2.dp, blurRadius = 0.dp, style = style.value) },
                shadow = { Shadow.None },
            ) {
                Panel("panel")
            }
        }
        waitForIdle()
        val faint = onNodeWithTag("panel").captureToImage().toPixelMap()[39, 20]
        assertEquals(0.2f, faint.red, 0.03f)
        style.value = style.value.copy(color = Color.White.copy(alpha = 0.8f))
        waitForIdle()
        val bright = onNodeWithTag("panel").captureToImage().toPixelMap()[39, 20]
        assertEquals(0.8f, bright.red, 0.03f)
        val srcOver = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        style.value = style.value.copy(blendMode = BlendMode.Plus)
        waitForIdle()
        val plus = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        assertTrue(plus.blue > srcOver.blue + 0.5f)
    }

    @Test
    fun inheritedInnerShadowAnimationDrawsWithoutRecomposition() = runComposeUiTest {
        val innerShadow = mutableStateOf<InnerShadow>(InnerShadow.None)
        var compositions = 0
        setContent {
            ProvideBackdropStyle(
                highlight = { Highlight.None },
                shadow = { Shadow.None },
                innerShadow = { innerShadow.value },
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
            innerShadow.value = InnerShadow.None
            waitForIdle()
            assertColor(blank, onNodeWithTag("panel").captureToImage().toPixelMap()[20, 1])
        }
        assertEquals(baseline, compositions)
    }

    @Test
    fun shadowNoneSwitchesAndZeroAlphaConfigurationRemainDistinct() = runComposeUiTest {
        val shadow = mutableStateOf<Shadow>(Shadow.None)
        val config = Shadow(radius = 2.dp, offset = DpOffset(4.dp, 0.dp), color = Color.Red)
        var compositions = 0
        setContent {
            ProvideBackdropStyle(highlight = { Highlight.None }, shadow = { shadow.value }) {
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
            shadow.value = Shadow.None
            waitForIdle()
            assertColor(blank, onNodeWithTag("outer").captureToImage().toPixelMap()[51, 30])
        }
        assertEquals(baseline, compositions)
    }

    @Test
    fun effectsAnimationResetsPaddingAndRenderEffectWithoutRecomposition() = runComposeUiTest {
        val enabled = mutableStateOf(false)
        lateinit var scope: BackdropEffectScope
        var compositions = 0
        setContent {
            ProvideBackdropStyle(
                effects = {
                    scope = this
                    if (enabled.value) blur(3f, TileMode.Decal)
                },
                highlight = { Highlight.None },
                shadow = { Shadow.None },
            ) {
                Panel("panel", onComposition = { compositions++ })
            }
        }
        waitForIdle()
        val baseline = compositions
        assertNull(scope.renderEffect)
        assertEquals(0f, scope.padding)
        val blank = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        enabled.value = true
        waitForIdle()
        assertNotNull(scope.renderEffect)
        assertEquals(3f, scope.padding)
        val blurred = onNodeWithTag("panel").captureToImage().toPixelMap()[0, 20]
        assertTrue(blurred.alpha < blank.alpha - 0.1f)
        enabled.value = false
        waitForIdle()
        assertNull(scope.renderEffect)
        assertEquals(0f, scope.padding)
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
    private fun Panel(
        tag: String,
        modifier: Modifier? = null,
        onComposition: () -> Unit = {},
    ) {
        SideEffect(onComposition)
        Box(
            Modifier.testTag(tag)
                .size(40.dp)
                .then(modifier ?: Modifier.drawBackdrop(TestBackdrop, { RectangleShape }))
        )
    }

    private fun solidHighlight(color: Color): Highlight.Config =
        Highlight(
            width = 2.dp,
            blurRadius = 0.dp,
            style = HighlightStyle.Plain(color, BlendMode.SrcOver),
        )
}

private val TestBackdrop =
    object : Backdrop {
        override val isCoordinatesDependent: Boolean = false

        override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(
            density: androidx.compose.ui.unit.Density,
            coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
            layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?,
        ) {
            drawRect(Color.Blue)
        }
    }
