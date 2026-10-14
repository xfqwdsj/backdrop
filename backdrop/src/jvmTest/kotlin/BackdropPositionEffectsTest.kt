package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackdropPositionEffectsTest {
    @Test
    fun positionChangesDoNotRebuildEffectsButGeometryChangesDo() = runComposeUiTest {
        var x by mutableIntStateOf(0)
        var width by mutableIntStateOf(40)
        var density by mutableStateOf(Density(1f, 1f))
        var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
        val effectInput = mutableStateOf(false)
        var effectCalls = 0
        val effect: BackdropEffectScope.() -> Unit = {
            effectCalls++
            effectInput.value
        }

        setContent {
            CompositionLocalProvider(
                LocalDensity provides density,
                LocalLayoutDirection provides layoutDirection,
            ) {
                Box(
                    Modifier.offset { IntOffset(x, 0) }
                        .size(width.dp, 40.dp)
                        .testTag("surface")
                        .drawPlainBackdrop(TestBackdrop, { RectangleShape }, effects = effect)
                )
            }
        }
        onNodeWithTag("surface").assertExists()
        waitForIdle()
        assertEquals(1, effectCalls)

        repeat(10) { index ->
            runOnIdle { x = index + 1 }
            waitForIdle()
        }
        assertEquals(1, effectCalls, "Position-only updates should keep the resolved effect chain")

        var previousCalls = effectCalls
        runOnIdle { width = 48 }
        waitForIdle()
        assertTrue(effectCalls > previousCalls, "A size change affects effect resolution")

        previousCalls = effectCalls
        runOnIdle { density = Density(1.25f, 1f) }
        waitForIdle()
        assertTrue(effectCalls > previousCalls, "A density change affects effect resolution")

        previousCalls = effectCalls
        runOnIdle { density = Density(1.25f, 1.5f) }
        waitForIdle()
        assertTrue(effectCalls > previousCalls, "A font scale change affects effect resolution")

        previousCalls = effectCalls
        runOnIdle { layoutDirection = LayoutDirection.Rtl }
        waitForIdle()
        assertTrue(
            effectCalls > previousCalls,
            "A layout direction change affects effect resolution",
        )

        previousCalls = effectCalls
        runOnIdle { effectInput.value = true }
        waitForIdle()
        assertTrue(
            effectCalls > previousCalls,
            "Snapshot reads in the effect producer remain observed",
        )
    }
}

private val TestBackdrop =
    object : Backdrop {
        override val isCoordinatesDependent: Boolean = false

        override fun BackdropDrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?,
        ) {
            drawRect(Color.Transparent)
        }
    }
