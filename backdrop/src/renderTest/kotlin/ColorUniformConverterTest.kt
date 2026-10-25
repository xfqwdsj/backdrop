package top.ltfan.backdrop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ColorUniformConverterTest : PlatformGraphicsTest() {
    @Test
    fun layoutColorUniformsCopyScalarValuesBeforeScratchStorageIsReused() {
        val shader =
            RuntimeShader(
                """
                layout(color) uniform float4 firstColor;
                layout(color) uniform float4 secondColor;
                float4 main(float2 coord) {
                    return float4(
                        (firstColor.rgb * firstColor.a + secondColor.rgb * secondColor.a) * 0.5,
                        (firstColor.a + secondColor.a) * 0.5
                    );
                }
                """,
            )
        shader.setColorUniform("firstColor", Color(1f, 0f, 0f, 0.4f))
        shader.setColorUniform("secondColor", Color(0f, 0f, 1f, 0.8f))

        assertPixel(
            floatArrayOf(0.2f, 0f, 0.4f, 0.6f),
            render(shader),
        )

        val transparentShader =
            RuntimeShader(
                """
                layout(color) uniform float4 color;
                float4 main(float2 coord) { return float4(color.rgb * color.a, color.a); }
                """,
            )
        transparentShader.setColorUniform(
            "color",
            Color(1.2f, -0.1f, 0.4f, alpha = 0f, colorSpace = ColorSpaces.DisplayP3),
        )
        assertPixel(
            floatArrayOf(0f, 0f, 0f, 0f),
            render(transparentShader),
        )
    }

    private fun render(shader: RuntimeShader): FloatArray = renderTestShader(shader, 1, 1)

    private fun assertPixel(expected: FloatArray, actual: FloatArray) {
        for (index in expected.indices) {
            assertTrue(abs(expected[index] - actual[index]) < 0.01f)
        }
    }
}
