package top.ltfan.backdrop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.layeredProgressiveBlur

/** Measures graph declaration and resource preparation without recording pixels. */
// JMH discovers these entry points through generated harness code.
@Suppress("unused")
@State(Scope.Benchmark)
open class GraphBenchmark {
    @Param("1", "8") var receivers: Int = 1
    private lateinit var scene: ImageComposeScene
    private lateinit var graphics: GraphicsContext
    private val shape = RoundedCornerShape(24.dp)
    private val ramp = BackdropRamp()
    private val density = Density(1f)
    private var revision = 0
    private lateinit var uniform: BackdropRenderScope
    private lateinit var layered: BackdropRenderScope
    private lateinit var renderers: List<BackdropRenderer>

    @OptIn(InternalComposeUiApi::class)
    @Setup
    fun setup() {
        registerSkikoComposeImplementation()
        uniform = declaration(320f, layered = false)
        layered = declaration(320f, layered = true)
        // Primitive preparation allocates real backend resources even without recording pixels.
        scene =
            ImageComposeScene(width = 1, height = 1) {
                val context = LocalGraphicsContext.current
                SideEffect { graphics = context }
            }
        scene.render(0L).close()
        renderers = List(receivers) { BackdropRenderer { graphics } }
    }

    @TearDown
    fun teardown() {
        renderers.forEach { it.close() }
        scene.close()
    }

    @Benchmark fun declareUniform(): Any = List(receivers) { declaration(320f, layered = false) }

    @Benchmark fun declareLayered(): Any = List(receivers) { declaration(320f, layered = true) }

    @Benchmark fun resolveUniformAnimation(): Any = resolveAnimation(layered = false)

    @Benchmark fun resolveLayeredAnimation(): Any = resolveAnimation(layered = true)

    @Benchmark fun resolveUniformRetainedDeclaration(): Any = resolveRetained(uniform)

    @Benchmark fun resolveLayeredRetainedDeclaration(): Any = resolveRetained(layered)

    private fun resolveRetained(graph: BackdropRenderScope): Any {
        val requested = Rect(0f, 0f, 320f, 240f)
        for (renderer in renderers) renderer.resolve(graph, requested)
        return renderers.last().extension
    }

    private fun resolveAnimation(layered: Boolean): Any {
        // Geometry animation includes declaration; retained cases isolate resolution work.
        val width = 280f + (revision++ % 80)
        for (index in renderers.indices) {
            val graph = declaration(width, layered)
            renderers[index].resolve(graph, Rect(0f, 0f, width, 240f))
        }
        return renderers.last().extension
    }

    private fun declaration(width: Float, layered: Boolean): BackdropRenderScope =
        BackdropRenderScope(density, Size(width, 240f), shape, LayoutDirection.Ltr).apply {
            if (layered) layeredProgressiveBlur(24f, BackdropEdge.Top, ramp)
            else effects { blur(24f) }
            clipSurface()
            paintSurface { drawRect(Color.White.copy(alpha = .15f)) }
        }
}
