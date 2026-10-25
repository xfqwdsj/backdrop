package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class UnreachableDecorationTest {
    @Test
    fun unreachableDecorationSkipsSamplingAndRendererConstruction() {
        var sampled = 0
        val decoration = Decoration { sampled++ }
        val graph =
            BackdropRenderScope(
                    Density(1f),
                    Size(40f, 40f),
                    RectangleShape,
                    LayoutDirection.Ltr,
                    style = { BackdropStyle.NoEffects },
                )
                .apply {
                    image { surface { drawSurface(decoration) } }
                }
        BackdropRenderer { error("No graphics resources are required") }
            .use { renderer ->
                renderer.resolve(graph, Rect(0f, 0f, 40f, 40f))
            }
        assertEquals(0, sampled, "Unreachable decoration sampling must remain unevaluated")
    }

    private class Decoration(val onSampling: () -> Unit) : BackdropDecoration<Decoration> {
        override fun sampling(geometry: BackdropGeometry): BackdropSampling? {
            onSampling()
            return null
        }

        override fun createRenderer(
            resources: BackdropRenderResources,
        ): BackdropDecorationRenderer<Decoration> =
            error("Unreachable decoration renderers must remain unconstructed")
    }
}
