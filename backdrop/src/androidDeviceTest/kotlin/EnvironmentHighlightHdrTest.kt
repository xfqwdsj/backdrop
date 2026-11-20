package top.ltfan.backdrop

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.internal.setRuntimeShader

class EnvironmentHighlightHdrTest {
    @Test
    @RequiresApi(34)
    fun blurredEnvironmentRetainsColorAndCoverageOnAnF16Target() = runRenderTest {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val sourceAlpha = mutableFloatStateOf(.5f)
        val size = 64
        val sourceShader =
            RuntimeShader(
                """
                uniform float opacity;
                half4 main(float2 p) { return half4(2.0, 0.3, 0.1, 1.0) * opacity; }
                """
                    .trimIndent(),
            )
        sourceShader.setFloatUniform("opacity", 1f)
        val direct = renderTestShader(sourceShader, 1, 1)
        assertTrue(direct[0] > 1.5f, "F16 target red=${direct[0]}")

        setContent {
            val source = rememberCanvasBackdrop {
                val paint = Paint()
                sourceShader.setFloatUniform("opacity", sourceAlpha.floatValue)
                paint.setRuntimeShader(sourceShader)
                drawContext.canvas.drawRect(samplingBounds, paint)
            }
            Box(
                Modifier.size(size.dp).drawBackdrop(source, { RectangleShape }) {
                    val environment = image { source() }
                    replace(image {})
                    highlight(
                        Highlight(
                            width = 8.dp,
                            blurRadius = 4.dp,
                            baseBrightness = 0f,
                            environmentGain = 1f,
                            direction = HighlightDirection.Uniform,
                        ),
                        environment = environment,
                        blendMode = BlendMode.SrcOver,
                    )
                },
            )
        }

        val translucent = runOnIdle { captureRenderTestContentF16() }
        runOnIdle { sourceAlpha.floatValue = 1f }
        val opaque = runOnIdle { captureRenderTestContentF16() }

        val rim = (32 * size + 1) * 4
        val falloff = (32 * size + 4) * 4
        val interior = (32 * size + 32) * 4
        // HWUI intermediates can clamp extended values before the F16 capture layer.
        assertTrue(
            translucent[rim] > translucent[rim + 1] && opaque[rim] > translucent[rim] + .15f,
            "Rim premultiplied RGBA: ${translucent.slice(rim..rim + 3)} to ${opaque.slice(rim..rim + 3)}",
        )
        val falloffAlpha = translucent[falloff + 3]
        assertTrue(
            falloffAlpha > 0f &&
                falloffAlpha < translucent[rim + 3] &&
                opaque[falloff + 3] > falloffAlpha,
            "Falloff alpha: ${translucent[falloff + 3]} to ${opaque[falloff + 3]}",
        )
        assertEquals(0f, translucent[interior + 3])
    }
}
