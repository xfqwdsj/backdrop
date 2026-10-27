package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.blur

class FilterReuseRenderingTest {
    @Test
    fun retainedFiltersMatchFreshResolutionAcrossParameterAndStageChanges() {
        for (split in listOf(false, true)) runRenderTest {
            val revision = mutableIntStateOf(0)
            var producerCalls = 0
            setContent {
                val graphics = LocalGraphicsContext.current
                val retained = remember(graphics) { BackdropRenderer { graphics } }
                val reference = remember(graphics) { BackdropRenderer { graphics } }
                DisposableEffect(retained, reference) {
                    onDispose {
                        retained.close()
                        reference.close()
                    }
                }
                Canvas(Modifier.size(200.dp, 100.dp).testTag("filter-reuse")) {
                    val value = revision.intValue
                    val selected = value % 6
                    val radius = if (selected < 2) 4f else 12f
                    val edge = if (selected % 2 == 0) TileMode.Clamp else TileMode.Decal
                    val graph =
                        BackdropRenderScope(this, Size(80f, 80f), RectangleShape, layoutDirection)
                    val first: BackdropEffectScope.() -> Unit = {
                        producerCalls++
                        if (selected != 4) blur(radius, edge)
                    }
                    val second: BackdropEffectScope.() -> Unit = {
                        producerCalls++
                        if (selected != 5) blur(3f, edge)
                    }
                    if (split) {
                        graph.effects(first)
                        graph.effects(second)
                    } else
                        graph.effects {
                            first()
                            second()
                        }
                    val request =
                        if (value % 2 == 0) Rect(0f, 0f, 80f, 80f)
                        else Rect(-.25f, -.75f, 80.25f, 80.75f)
                    drawRect(Color.Green)
                    producerCalls = 0
                    retained.resolve(graph, request)
                    retained.beginFrame()
                    val source: DrawScope.(Rect) -> Unit = { bounds ->
                        drawRect(Color.Red, bounds.topLeft, bounds.size)
                        drawRect(Color.Blue, Offset(12f, 18f), Size(20f, 40f))
                    }
                    retained.draw(this, source = source)
                    reference.close()
                    reference.resolve(graph, request)
                    reference.beginFrame()
                    translate(100f) { reference.draw(this, source = source) }
                }
            }
            for (value in listOf(0, 6, 1, 2, 8, 3, 4, 5, 0, 12, 3, 1)) {
                runOnIdle { revision.intValue = value }
                val pixels = onNodeWithTag("filter-reuse").captureToImage().toPixelMap()
                assertEquals(4, producerCalls, "Both declarations must still observe effect inputs")
                for (y in 0 until 80) for (x in 0 until 80) {
                    val a = pixels[x, y]
                    val b = pixels[x + 100, y]
                    assertTrue(
                        abs(a.red - b.red) <= 2f / 255f &&
                            abs(a.green - b.green) <= 2f / 255f &&
                            abs(a.blue - b.blue) <= 2f / 255f &&
                            abs(a.alpha - b.alpha) <= 2f / 255f,
                        "split=$split revision=$value pixel=($x,$y): retained=$a fresh=$b",
                    )
                }
            }
        }
    }
}
