package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightSample
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.renderStaticHighlightPixels
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

@OptIn(ExperimentalTestApi::class)
class CustomHighlightStyleTest {
    @Test
    fun customShaderAndCpuShadeStayAlignedAndUniformUpdatesDoNotRecompose() = runComposeUiTest {
        val mix = mutableStateOf(0f)
        val style = TestHighlightStyle(mix)
        var compositions = 0
        setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(160.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                SideEffect { compositions++ }
                Box(
                    Modifier.offset(40.dp, 40.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            backdrop,
                            { RectangleShape },
                            highlight = {
                                Highlight(
                                    width = 5.dp,
                                    style = style,
                                    colorResponse = HighlightColorResponse(amount = 0f),
                                )
                            },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        waitForIdle()
        val initialCompositionCount = compositions
        val before = onNodeWithTag("root").captureToImage().toPixelMap()[80, 41]
        mix.value = 1f
        waitForIdle()
        val after = onNodeWithTag("root").captureToImage().toPixelMap()[80, 41]

        assertTrue(
            before.red > before.green + 0.3f,
            "initial uniform selects the red shader output: $before",
        )
        assertTrue(
            after.green > after.red + 0.3f,
            "updated uniform selects the green shader output: $after",
        )
        assertEquals(initialCompositionCount, compositions)

        val cpuPixels =
            renderStaticHighlightPixels(
                geometry =
                    FlattenedBoundaryGeometry(
                        Path().apply {
                            addRect(Rect(0f, 0f, 80f, 80f))
                        }
                    ),
                size = Size(80f, 80f),
                widthPx = 5f,
                falloff = Highlight().falloff,
                style = style,
                direction = HighlightDirection(),
                exclusions = emptySet(),
            )
        val cpuOffset = (1 * 80 + 40) * 4
        assertTrue(
            cpuPixels[cpuOffset + 1] > cpuPixels[cpuOffset],
            "CPU shade uses the same custom style output",
        )
        assertEquals(
            cpuPixels[cpuOffset + 1],
            after.green * after.alpha,
            0.08f,
            "GPU and CPU custom output agree at the sampled rim",
        )
    }
}

private class TestHighlightStyle(private val mix: MutableState<Float>) : HighlightStyle {
    override val color: Color = Color.White
    override val shaderSource: String =
        """
        uniform float styleMix;
        half4 shadeHighlight(float2 position, float2 outwardNormal, float projection, half4 color) {
            return half4(1.0 - styleMix, styleMix, 0.0, color.a);
        }
        float highlightStrength(float2 position, float2 outwardNormal, float projection) { return 1.0; }
        """
            .trimIndent()

    override fun shade(sample: HighlightSample): top.ltfan.backdrop.highlight.HighlightEmission =
        top.ltfan.backdrop.highlight.HighlightEmission(
            Color(1f - mix.value, mix.value, 0f, color.alpha)
        )

    override fun configureShader(shader: RuntimeShader) {
        shader.setFloatUniform("styleMix", mix.value)
    }
}
