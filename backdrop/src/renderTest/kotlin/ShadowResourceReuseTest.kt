package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

class ShadowResourceReuseTest {
    @Test
    fun reusedShadowStateMatchesFreshStateAcrossParametersAndRefresh() = runRenderTest {
        val revision = mutableIntStateOf(0)
        val samples =
            listOf(
                Shadow(radius = 5.dp, color = Color.Red),
                Shadow(radius = 5.dp, color = Color.Blue),
                Shadow(radius = 0.dp, color = Color.Blue),
                Shadow(
                    radius = 5.dp,
                    offset = DpOffset((-2.5f).dp, 1.25f.dp),
                    color = Color.Red,
                    alpha = .25f,
                ),
                InnerShadow(radius = 5.dp, color = Color.Red),
                InnerShadow(radius = 5.dp, color = Color.Blue),
                InnerShadow(radius = 0.dp, color = Color.Blue),
                InnerShadow(radius = 5.dp, color = Color.Green, alpha = 0f),
            )
        setContent {
            val graphics = LocalGraphicsContext.current
            val reused = remember(graphics) { BackdropRenderer { graphics } }
            val fresh = remember(graphics) { BackdropRenderer { graphics } }
            DisposableEffect(reused, fresh) {
                onDispose {
                    reused.close()
                    fresh.close()
                }
            }
            Canvas(Modifier.size(280.dp, 140.dp).testTag("shadow-resources")) {
                val value = revision.intValue
                val selected = samples[value % samples.size]
                val graph =
                    BackdropRenderScope(1f, 1f, Size(80f, 80f), RectangleShape, LayoutDirection.Ltr)
                graph.group {
                    when (selected) {
                        is Shadow -> shadow(selected)
                        is InnerShadow -> innerShadow(selected)
                        else -> error("Unsupported fixture")
                    }
                }
                if (value >= samples.size) reused.refresh()
                fresh.close()
                for ((renderer, x) in listOf(reused to 25f, fresh to 165f)) {
                    renderer.resolve(graph, Rect(0f, 0f, 80f, 80f))
                    renderer.beginFrame()
                    translate(x, 25f) { renderer.draw(this) { error("Shadow declares no source") } }
                }
            }
        }
        for (value in samples.indices + listOf(samples.size, samples.size + 1, 4, 0)) {
            runOnIdle { revision.intValue = value }
            val pixels = onNodeWithTag("shadow-resources").captureToImage().toPixelMap()
            var covered = false
            for (y in 0 until 130) for (x in 0 until 130) {
                assertEquals(pixels[x + 140, y], pixels[x, y], "revision=$value pixel=($x,$y)")
                if (pixels[x, y].alpha > .01f) covered = true
            }
            if (value % samples.size in listOf(0, 1, 3, 4, 5))
                assertTrue(covered, "revision=$value draws a shadow")
        }
    }
}
