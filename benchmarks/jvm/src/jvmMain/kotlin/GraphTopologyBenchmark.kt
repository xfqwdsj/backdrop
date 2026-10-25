package top.ltfan.backdrop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import top.ltfan.backdrop.effects.blur

/** Resolves shared, transformed, masked and serial graphs without recording pixels. */
// JMH discovers these entry points through generated harness code.
@Suppress("unused")
@State(Scope.Benchmark)
open class GraphTopologyBenchmark {
    @Param("shared", "overlap", "masked", "serial") lateinit var topology: String
    @Param("4", "16") var branches: Int = 4
    @Param("retained", "declaration") lateinit var mutation: String
    private val density = Density(1f)
    private var revision = 0
    private lateinit var graph: BackdropRenderScope
    private lateinit var renderer: BackdropRenderer
    private val requested = Rect(0f, 0f, 320f, 240f)

    @OptIn(InternalComposeUiApi::class)
    @Setup
    fun setup() {
        registerSkikoComposeImplementation()
        renderer = BackdropRenderer { error("These graphs prepare effects without layers") }
        graph = declaration()
        renderer.resolve(graph, requested)
    }

    private fun declaration(): BackdropRenderScope {
        val radius = 4f + (revision++ % 16) * .25f
        val graph =
            BackdropRenderScope(density, Size(320f, 240f), RectangleShape, LayoutDirection.Ltr)
        with(graph) {
            if (topology == "serial") {
                repeat(branches) { effects { blur(radius) } }
            } else {
                val shared = image {
                    source()
                    effects { blur(radius) }
                }
                val images =
                    List(branches) { index ->
                        image {
                            replace(shared)
                            if (topology == "overlap") {
                                transform(Matrix().apply { translate(index * 8f, index * -5f) })
                            }
                            if (topology == "masked") {
                                mask(image { paint { drawRect(Color.White.copy(alpha = .5f)) } })
                            }
                        }
                    }
                group { images.forEach { draw(it, alpha = .5f) } }
            }
        }
        return graph
    }

    @Benchmark
    fun resolveTopology(): Any {
        renderer.resolve(if (mutation == "declaration") declaration() else graph, requested)
        return renderer.extension
    }

    @TearDown
    fun teardown() {
        renderer.close()
    }
}
