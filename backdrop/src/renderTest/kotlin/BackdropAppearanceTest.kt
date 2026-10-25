package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightAngleDomain
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.angles
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

class BackdropAppearanceTest {
    @Test
    fun parameterDefaultsAndNestedProvidersRetainIndependentFields() = runRenderTest {
        lateinit var root: BackdropStyle
        lateinit var child: BackdropStyle
        val parent = BackdropStyle(blurRadius = { 8.dp })
        val inner: () -> InnerShadow = { InnerShadow.Default }
        setContent {
            root = LocalBackdropStyle.current
            ProvideBackdropStyle(parent) {
                ProvideBackdropStyle(innerShadow = inner) { child = LocalBackdropStyle.current }
            }
        }
        waitForIdle()
        assertSame(BackdropStyle.Default, root)
        assertSame(Highlight.Default, root.highlight())
        assertSame(Shadow.Default, root.shadow())
        assertSame(InnerShadow.None, root.innerShadow())
        assertSame(parent.blurRadius, child.blurRadius)
        assertSame(parent.highlight, child.highlight)
        assertSame(parent.shadow, child.shadow)
        assertSame(inner, child.innerShadow)
        assertEquals(0.dp, BackdropStyle.NoEffects.blurRadius())
    }

    @Test
    fun angularPrimitiveUsesRelativeDirectionAndHardCutsTheBottomEdge() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Black) }
            Box(
                Modifier.size(80.dp).testTag("angular").drawBackdrop(source, { RectangleShape }) {
                    surface {
                        highlight {
                            width = 10.dp
                            style = HighlightStyle.Solid(Color.White)
                            falloff = BackdropCurve.Linear
                            colorResponse = HighlightColorResponse(amount = 0f)
                            angles(angle = 90f, domain = HighlightAngleDomain.Directed) {
                                widthScale(0f, 360f, 1f)
                                brightness(0f, 360f, 1f)
                            }
                            disabledAngles = setOf(0f..20f, 340f..360f)
                        }
                    }
                },
            )
        }
        val pixels = onNodeWithTag("angular").captureToImage().toPixelMap()
        assertTrue(pixels[40, 0].red > .9f, "top contour remains visible")
        assertTrue(pixels[40, 79].red < .01f, "relative zero disables the bottom contour")
        assertTrue(pixels[0, 40].red > .9f, "left contour remains visible")
    }

    @Test
    fun sharedModifierLooksUpAppearanceAtEachUsePosition() = runRenderTest {
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.White) }
            val modifier =
                Modifier.size(40.dp).drawBackdrop(source, { RectangleShape }) { surface {} }
            Row {
                ProvideBackdropStyle(BackdropStyle.NoEffects.copy(fill = { Color.Red })) {
                    Box(modifier.testTag("red"))
                }
                ProvideBackdropStyle(BackdropStyle.NoEffects.copy(fill = { Color.Blue })) {
                    Box(modifier.testTag("blue"))
                }
            }
        }
        assertTrue(onNodeWithTag("red").captureToImage().toPixelMap()[20, 20].red > .98f)
        assertTrue(onNodeWithTag("blue").captureToImage().toPixelMap()[20, 20].blue > .98f)
    }

    @Test
    fun delayedFillUpdatesWithoutRecomposingAndNoneRemainsExplicit() = runRenderTest {
        val color = mutableStateOf(Color.Red)
        var compositions = 0
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.White) }
            SideEffect { compositions++ }
            ProvideBackdropStyle(BackdropStyle.NoEffects.copy(fill = { color.value })) {
                Box(
                    Modifier.size(40.dp).testTag("surface").drawBackdrop(
                        source,
                        { RectangleShape },
                    ) {
                        surface {}
                    },
                )
            }
        }
        assertTrue(onNodeWithTag("surface").captureToImage().toPixelMap()[20, 20].red > .98f)
        val before = compositions
        runOnIdle { color.value = Color.Blue }
        waitForIdle()
        assertTrue(onNodeWithTag("surface").captureToImage().toPixelMap()[20, 20].blue > .98f)
        assertEquals(before, compositions)
        assertEquals(Highlight.None, BackdropStyle.NoEffects.highlight())
        assertEquals(Shadow.None, BackdropStyle.NoEffects.shadow())
        assertEquals(InnerShadow.None, BackdropStyle.NoEffects.innerShadow())
    }

    @Test
    fun fullyExplicitAppearanceDoesNotSubscribeToStyleReplacement() = runRenderTest {
        val style = mutableStateOf(BackdropStyle.NoEffects)
        var calls = 0
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.White) }
            val modifier =
                remember(source) {
                    Modifier.size(40.dp).testTag("surface").drawBackdrop(
                        source,
                        { RectangleShape },
                        insets = { BackdropInsets.None },
                    ) {
                        surface {
                            effects {}
                            fill(Color.Red)
                            paintSurface { calls++ }
                            highlight(Highlight.None)
                            shadow(Shadow.None)
                            innerShadow(InnerShadow.None)
                            interactionLight(InteractionLighting.None)
                        }
                    }
                }
            CompositionLocalProvider(LocalBackdropStyle provides style.value) { Box(modifier) }
        }
        waitForIdle()
        val before = calls
        runOnIdle { style.value = BackdropStyle.NoEffects.copy(fill = { Color.Blue }) }
        waitForIdle()
        assertEquals(before, calls)
        assertTrue(onNodeWithTag("surface").captureToImage().toPixelMap()[20, 20].red > .98f)
    }
}
