package top.ltfan.backdrop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect as SkiaRect
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightRenderer
import top.ltfan.backdrop.highlight.HighlightStyle

class HighlightVariantCacheTest : PlatformGraphicsTest() {
    @Test
    fun returningToTwoVariantsBoundsShaderRetentionAndUpdatesUniforms() {
        lateinit var graphics: GraphicsContext
        val scene =
            ImageComposeScene(width = 1, height = 1) { graphics = LocalGraphicsContext.current }
        scene.render().close()
        val resources = PrimitiveResources { graphics }
        val renderer = HighlightRenderer(resources)
        val rectangle = Rect(0f, 0f, 80f, 80f)
        val outlines =
            listOf(
                Outline.Rectangle(rectangle),
                Outline.Generic(Path().apply { addRect(rectangle) }),
            )
        val frame = ProcessedBackdropFrame()
        val recorder = PictureRecorder()
        val scope = CanvasDrawScope()
        try {
            repeat(12) { index ->
                val width = 2f + (index / 2 % 4)
                val red = index % 2 == 0
                val config =
                    Highlight(
                        width = width.dp,
                        falloff = BackdropCurve.Linear,
                        direction = HighlightDirection.Uniform,
                        style = HighlightStyle.Solid(if (red) Color.Red else Color.Blue),
                        colorResponse = HighlightColorResponse(amount = 0f),
                    )
                val canvas = recorder.beginRecording(SkiaRect.makeWH(80f, 80f))
                scope.draw(
                    Density(1f),
                    LayoutDirection.Ltr,
                    canvas.asComposeCanvas(),
                    Size(80f, 80f),
                ) {
                    renderer.draw(this, config, outlines[index % 2], frame)
                }
                recorder.finishRecordingAsPicture().close()
                val shaders = shaderEntries(renderer)
                assertTrue(shaders.size <= 2, "Returning signatures reuse their original shaders")
                val key = field(renderer, "currentStaticShaderKey") as String
                val shader = field(checkNotNull(shaders[key]), "shader") as RuntimeShader
                // This probe uses surface coordinates instead of the last output tile's origin.
                shader.setFloatUniform("origin", 0f, 0f)
                val pixels = renderTestShader(shader, 80, 80)
                val edge = 40 * 4
                assertEquals(1f - .5f / width, pixels[edge + 3], .002f, "width update $index")
                assertTrue(pixels[edge + if (red) 0 else 2] > .5f, "color update $index")
                assertEquals(0f, pixels[edge + if (red) 2 else 0], .002f)
            }
            assertEquals(2, shaderEntries(renderer).size)
            renderer.release()
            assertTrue(shaderEntries(renderer).isEmpty(), "Release drops retained shader entries")
        } finally {
            renderer.release()
            resources.close()
            recorder.close()
            scene.close()
        }
    }

    // Reflection counts retained resources through the private native shader cache.
    private fun shaderEntries(renderer: HighlightRenderer): Map<*, *> =
        field(checkNotNull(field(renderer, "runtimeShaderCache")), "runtimeShaders") as Map<*, *>

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
