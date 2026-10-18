package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropStyleReadTrackingTest {
    @Test
    fun explicitProducersDoNotRereadReplacedStyles() = runComposeUiTest {
        val style = mutableStateOf(BackdropStyle.Default)
        var explicitEffectCalls = 0
        var explicitInsetCalls = 0
        val explicitEffects: BackdropEffectScope.() -> Unit = { explicitEffectCalls++ }
        val explicitInsets: () -> BackdropInsets = {
            explicitInsetCalls++
            BackdropInsets.None
        }
        val modifier =
            Modifier.size(40.dp)
                .testTag("surface")
                .drawPlainBackdrop(
                    TestBackdrop,
                    { RectangleShape },
                    effects = explicitEffects,
                    insets = explicitInsets,
                )

        setContent {
            CompositionLocalProvider(LocalBackdropStyle provides style.value) {
                Box(modifier)
            }
        }
        onNodeWithTag("surface").assertExists()
        waitForIdle()
        val initialEffectCalls = explicitEffectCalls
        val initialInsetCalls = explicitInsetCalls
        assertTrue(initialEffectCalls > 0)
        assertTrue(initialInsetCalls > 0)

        repeat(10) { index ->
            runOnIdle {
                style.value =
                    BackdropStyle(
                        effects = { cover(BackdropInsets(top = (index + 1).dp)) },
                        insets = { BackdropInsets(top = (index + 1).dp) },
                    )
            }
            waitForIdle()
        }

        assertEquals(
            initialEffectCalls,
            explicitEffectCalls,
            "Explicit effects should not subscribe to replaced styles",
        )
        assertEquals(
            initialInsetCalls,
            explicitInsetCalls,
            "Explicit insets should not subscribe to replaced styles",
        )
    }

    @Test
    fun explicitProducersSkipStyleReadsAndNullRestoresInheritedProducers() = runComposeUiTest {
        val version = mutableIntStateOf(0)
        val useExplicitProducers = mutableStateOf(true)
        var inheritedEffectCalls = 0
        var inheritedInsetCalls = 0
        var explicitEffectCalls = 0
        var explicitInsetCalls = 0
        val explicitEffects: BackdropEffectScope.() -> Unit = { explicitEffectCalls++ }
        val explicitInsets: () -> BackdropInsets = {
            explicitInsetCalls++
            BackdropInsets.None
        }

        setContent {
            CompositionLocalProvider(
                LocalBackdropStyle provides
                    BackdropStyle(
                        effects = {
                            inheritedEffectCalls++
                            version.intValue
                        },
                        insets = {
                            inheritedInsetCalls++
                            version.intValue
                            BackdropInsets.None
                        },
                    ),
            ) {
                val modifier =
                    Modifier.size(40.dp)
                        .testTag("surface")
                        .drawPlainBackdrop(
                            TestBackdrop,
                            { RectangleShape },
                            effects = explicitEffects.takeIf { useExplicitProducers.value },
                            insets = explicitInsets.takeIf { useExplicitProducers.value },
                        )
                Box(modifier)
            }
        }
        onNodeWithTag("surface").assertExists()
        waitForIdle()
        assertEquals(1, explicitEffectCalls)
        assertEquals(1, explicitInsetCalls)
        assertEquals(0, inheritedEffectCalls)
        assertEquals(0, inheritedInsetCalls)

        runOnIdle { version.intValue++ }
        waitForIdle()
        assertEquals(1, explicitEffectCalls)
        assertEquals(1, explicitInsetCalls)
        assertEquals(0, inheritedEffectCalls, "Explicit effects should not subscribe to style")
        assertEquals(0, inheritedInsetCalls, "Explicit insets should not subscribe to style")

        runOnIdle { useExplicitProducers.value = false }
        waitForIdle()
        assertTrue(inheritedEffectCalls > 0, "Null effects should inherit the current style")
        assertTrue(inheritedInsetCalls > 0, "Null insets should inherit the current style")
        assertEquals(1, explicitEffectCalls)
        assertEquals(1, explicitInsetCalls)

        val inheritedEffectBaseline = inheritedEffectCalls
        val inheritedInsetBaseline = inheritedInsetCalls
        runOnIdle { version.intValue++ }
        waitForIdle()
        assertTrue(inheritedEffectCalls > inheritedEffectBaseline)
        assertTrue(inheritedInsetCalls > inheritedInsetBaseline)
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
