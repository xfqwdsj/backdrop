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

/** Paired declaration and retained-resolution work; excludes pixel recording and GPU timing. */
@Suppress("unused")
@State(Scope.Benchmark)
open class StyleBenchmark {
    @Param("uniform", "layered") var algorithm: String = "uniform"
    private val shape = RoundedCornerShape(24.dp)
    private val density = Density(1f)
    private val bounds = Rect(0f, 0f, 320f, 240f)
    private val fill = BackdropFill(Color.White, alpha = .15f)
    private val blur: () -> BackdropBlur = {
        if (algorithm == "layered") BackdropBlur.LayeredProgressive(24.dp, BackdropEdge.Top)
        else BackdropBlur.Uniform(24.dp)
    }
    private lateinit var style: BackdropStyle
    private lateinit var styled: BackdropRenderScope
    private lateinit var native: BackdropRenderScope
    private lateinit var scene: ImageComposeScene
    private lateinit var graphics: GraphicsContext
    private lateinit var renderer: BackdropRenderer

    @OptIn(InternalComposeUiApi::class)
    @Setup
    fun setup() {
        registerSkikoComposeImplementation()
        style = constructStyle()
        styled = declare(true)
        native = declare(false)
        scene =
            ImageComposeScene(width = 1, height = 1) {
                val context = LocalGraphicsContext.current
                SideEffect { graphics = context }
            }
        scene.render(0L).close()
        renderer = BackdropRenderer { graphics }
    }

    @TearDown
    fun teardown() {
        renderer.close()
        scene.close()
    }

    @Benchmark
    fun constructStyle(): BackdropStyle = BackdropStyle.NoEffects {
        blur = this@StyleBenchmark.blur
        fill = { this@StyleBenchmark.fill }
    }

    @Benchmark fun deriveStyle(): BackdropStyle = style { fill = null }

    @Benchmark fun declareStyled(): Any = declare(true)

    @Benchmark fun declareNative(): Any = declare(false)

    @Benchmark
    fun resolveStyled(): Any {
        renderer.resolve(styled, bounds)
        return renderer.extension
    }

    @Benchmark
    fun resolveNative(): Any {
        renderer.resolve(native, bounds)
        return renderer.extension
    }

    private fun declare(styled: Boolean): BackdropRenderScope =
        BackdropRenderScope(density, Size(320f, 240f), shape, LayoutDirection.Ltr).apply {
            if (styled) styledSurface(style)
            else {
                if (algorithm == "layered") layeredProgressiveBlur(24f, BackdropEdge.Top)
                else effects { blur(24f) }
                clipSurface()
                fill(fill)
            }
        }
}
