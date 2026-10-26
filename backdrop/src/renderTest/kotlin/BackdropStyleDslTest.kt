package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.highlight.Highlight

class BackdropStyleDslTest {
    @Test
    fun derivationIsFlatDoesNotExecuteProducersAndNullRestoresInheritance() {
        var reads = 0
        val red: () -> BackdropFill = {
            reads++
            BackdropFill(Color.Red)
        }
        val base = BackdropStyle { fill = red }
        val derived = base {
            fill = { BackdropFill(Color.Blue) }
            fill = null
            highlight = { Highlight.None }
        }
        assertEquals(0, reads)
        assertSame(red, base.fill)
        assertEquals(base, BackdropStyle { fill = red })
        assertEquals(base.hashCode(), BackdropStyle { fill = red }.hashCode())
        assertNotEquals(base, derived)
        assertNull(derived.fill)
        assertSame(base, base {})
        assertSame(BackdropStyle {}, BackdropStyle {})
        var long = base
        repeat(10_000) { long = long { highlight = { Highlight.None } } }
        assertSame(red, long.fill)
        assertEquals(0, reads)
        val theme = BackdropStyle.Default { fill = { BackdropFill(Color.Green) } }
        val resolved = BackdropStyleResolver(derived) { theme }
        assertEquals(BackdropFill(Color.Green), resolved.fill())
        assertSame(Highlight.None, resolved.highlight())
        assertEquals(0, reads)
    }

    @Test
    fun completeOverridesNeverReadDefaultsAndTransparentFillRemainsExplicit() {
        val explicit = BackdropStyle.NoEffects
        val resolved =
            BackdropStyleResolver(explicit) { error("Explicit fields must not read theme") }
        assertEquals(BackdropBlur.None, resolved.blur())
        assertEquals(BackdropColorControls.None, resolved.colorControls())
        assertEquals(1f, resolved.vibrancy())
        assertEquals(BackdropLens.None, resolved.lens())
        assertEquals(BackdropFill.None, resolved.fill())
        assertEquals(BackdropInsets.None, resolved.insets())
        resolved.highlight()
        resolved.shadow()
        resolved.innerShadow()
        resolved.interactionLighting()
        val transparent = BackdropStyle { fill = { BackdropFill(Color.Transparent) } }
        assertEquals(
            BackdropFill.None,
            BackdropStyleResolver(transparent) { error("No inherited fill") }.fill(),
        )
    }

    @Test
    fun fixedSurfaceOwnsForegroundAndNativeAppendRetainsIt() {
        fun scope() =
            BackdropRenderScope(Density(1f), Size(40f, 40f), RectangleShape, LayoutDirection.Ltr)
        val a = scope()
        a.styledSurface(BackdropStyle.NoEffects)
        val original = a.foregroundImage
        a.appendForeground { fill(Color.Red) }
        assertTrue(original !== a.foregroundImage)
        assertFailsWith<IllegalStateException> { a.foreground {} }
        assertFailsWith<IllegalStateException> { a.styledSurface() }
        assertFailsWith<IllegalStateException> { a.surface {} }
        val b = scope()
        b.foreground {}
        assertFailsWith<IllegalStateException> { b.styledSurface() }
        val c = scope()
        c.surface {}
        assertFailsWith<IllegalStateException> { c.styledSurface() }
        val d = scope()
        val foreign = scope().current
        assertFailsWith<IllegalArgumentException> { d.styledSurface(input = foreign) }
        // The free graph API still replaces foreground, and nested surfaces remain available.
        val free = scope()
        free.foreground { fill(Color.Red) }
        val first = free.foregroundImage
        free.foreground { fill(Color.Blue) }
        assertTrue(first !== free.foregroundImage)
        free.image {
            surface {
                effects {}
                fill(Color.Green)
            }
        }
    }

    @Test
    fun clearedOverrideUsesReceivingThemeAndDelayedFillDoesNotRedeclare() = runRenderTest {
        val color = mutableStateOf(Color.Red)
        var declarations = 0
        var compositions = 0
        val style = BackdropStyle.NoEffects { fill = { BackdropFill(color.value) } }
        val inherited = style { fill = null }
        setContent {
            SideEffect { compositions++ }
            val source = rememberCanvasBackdrop { drawRect(Color.White) }
            val modifier =
                remember(source) {
                    Modifier.size(40.dp).drawBackdrop(source, { RectangleShape }) {
                        declarations++
                        styledSurface(inherited)
                    }
                }
            Row {
                ProvideBackdropStyle(style) { Box(modifier.testTag("delayed")) }
                ProvideBackdropStyle(
                    BackdropStyle.NoEffects { fill = { BackdropFill(Color.Green) } },
                ) {
                    Box(modifier.testTag("green"))
                }
            }
        }
        assertTrue(onNodeWithTag("delayed").captureToImage().toPixelMap()[20, 20].red > .98f)
        assertTrue(onNodeWithTag("green").captureToImage().toPixelMap()[20, 20].green > .98f)
        val before = declarations to compositions
        runOnIdle { color.value = Color.Blue }
        waitForIdle()
        assertTrue(onNodeWithTag("delayed").captureToImage().toPixelMap()[20, 20].blue > .98f)
        assertEquals(before, declarations to compositions)
    }

    @Test
    fun blurParameterAnimationRetainsDeclarationAndLayeredSwitchRebuilds() = runRenderTest {
        val blur = mutableStateOf<BackdropBlur>(BackdropBlur.Uniform(2.dp))
        var declarations = 0
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.White) }
            val modifier =
                remember(source) {
                    Modifier.size(40.dp).testTag("blur").drawBackdrop(source, { RectangleShape }) {
                        declarations++
                        styledSurface(BackdropStyle.NoEffects { this.blur = { blur.value } })
                    }
                }
            Box(modifier)
        }
        waitForIdle()
        val before = declarations
        runOnIdle { blur.value = BackdropBlur.Uniform(4.dp) }
        waitForIdle()
        onNodeWithTag("blur").captureToImage()
        assertEquals(before, declarations)
        runOnIdle { blur.value = BackdropBlur.LayeredProgressive(4.dp, BackdropEdge.Top) }
        waitForIdle()
        onNodeWithTag("blur").captureToImage()
        assertTrue(declarations > before, "Switching topology must redeclare")
        val layered = declarations
        runOnIdle { blur.value = BackdropBlur.LayeredProgressive(6.dp, BackdropEdge.Top) }
        waitForIdle()
        assertTrue(onNodeWithTag("blur").captureToImage().toPixelMap()[20, 20].red > .98f)
        assertEquals(layered, declarations)
    }
}
