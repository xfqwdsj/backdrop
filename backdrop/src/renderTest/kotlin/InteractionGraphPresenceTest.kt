package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.interaction.InteractionLightEntry
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.interaction.InteractionRegistry
import top.ltfan.backdrop.interaction.LocalInteractionRegistry
import top.ltfan.backdrop.interaction.interactionLight

class InteractionGraphPresenceTest {
    @Test
    fun emptyWindowsOmitLightingAndMovementRetainsDeclaration() = runRenderTest {
        val registry = InteractionRegistry()
        lateinit var window: Any
        var declarations = 0
        var lightingSelected = false
        var present = true
        setContent {
            window = LocalWindowInfo.current
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            CompositionLocalProvider(LocalInteractionRegistry provides registry) {
                Box(
                    Modifier.size(40.dp)
                        .drawBackdrop(
                            source,
                            { RectangleShape },
                            render = {
                                declarations++
                                present = hasInteractionLights
                                val before = current
                                interactionLight(InteractionLighting.Config())
                                lightingSelected = current !== before
                                foreground { assertEquals(present, hasInteractionLights) }
                            },
                        ),
                )
            }
        }
        waitForIdle()
        assertFalse(present)
        assertFalse(lightingSelected, "An empty window preserves the preceding image")
        val initial = declarations
        val otherWindow = entry(Any())
        runOnIdle { registry.lights += otherWindow }
        waitForIdle()
        assertEquals(initial, declarations, "Other windows retain the declaration")

        val light = entry(window)
        runOnIdle { registry.lights += light }
        waitForIdle()
        assertTrue(present)
        assertTrue(lightingSelected)
        val active = declarations
        repeat(3) { index ->
            runOnIdle {
                light.x += 1f
                light.intensity = index / 2f
            }
            waitForIdle()
            assertEquals(active, declarations, "Position and release intensity are drawing inputs")
        }
        val second = entry(window)
        runOnIdle { registry.lights += second }
        waitForIdle()
        assertEquals(active, declarations, "A nonempty window retains the declaration")
        runOnIdle { registry.lights.remove(light) }
        waitForIdle()
        assertEquals(active, declarations)
        runOnIdle { registry.lights.remove(second) }
        waitForIdle()
        assertFalse(present)
        assertFalse(lightingSelected)
        assertTrue(declarations > active)
    }

    @Test
    fun replacementProviderRebindsPresenceObservation() = runRenderTest {
        val first = InteractionRegistry()
        val second = InteractionRegistry()
        val selected = mutableStateOf<InteractionRegistry?>(first)
        lateinit var window: Any
        var present = false
        val render: BackdropRenderScope.() -> Unit = {
            image { present = hasInteractionLights }
            interactionLight(InteractionLighting.Config())
        }
        setContent {
            window = LocalWindowInfo.current
            val source = rememberCanvasBackdrop {}
            CompositionLocalProvider(LocalInteractionRegistry provides selected.value) {
                Box(
                    Modifier.size(40.dp).drawBackdrop(source, { RectangleShape }, render = render),
                )
            }
        }
        waitForIdle()
        runOnIdle { first.lights += entry(window) }
        waitForIdle()
        assertTrue(present)
        runOnIdle { selected.value = second }
        waitForIdle()
        assertFalse(present)
        runOnIdle { second.lights += entry(window) }
        waitForIdle()
        assertTrue(present)
        runOnIdle { selected.value = null }
        waitForIdle()
        assertFalse(present)
    }

    private fun entry(window: Any) =
        InteractionLightEntry(
            Any(),
            window,
            20f,
            20f,
            40f,
            Color.Red,
            BackdropCurve.Linear,
            0f,
        )
}
