package top.ltfan.backdrop

import kotlin.test.Test
import kotlin.test.assertEquals

class BackdropCurveShaderParityTest : PlatformGraphicsTest() {
    @Test
    fun runtimeShaderMatchesCpuCurveAcrossTheUnitInterval() {
        val curves =
            listOf(
                BackdropCurve.Linear,
                BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
                BackdropCurve.CubicBezier(0f, 0f, 1f, 1f),
                BackdropCurve.CubicBezier(0.2f, 0.1f, 0.8f, 0.9f),
            )
        val samples = listOf(0f, 0.01f, 0.2f, 0.5f, 0.707f, 0.99f, 1f)

        for (curve in curves) {
            val shader =
                RuntimeShader(
                    """
                uniform float x;
                uniform float4 controls;
                $BackdropCurveShader
                half4 main(float2 p) {
                    float value = backdropCurve(x, controls);
                    return half4(value, value, value, 1.0);
                }
                """
                        .trimIndent(),
                )
            shader.setFloatUniform("controls", curve.shaderControls())
            for (sample in samples) {
                shader.setFloatUniform("x", sample)
                assertEquals(
                    curve.transform(sample),
                    render(shader),
                    0.004f,
                    "curve=$curve sample=$sample",
                )
            }
        }
    }

    private fun render(shader: RuntimeShader): Float =
        renderTestShader(shader, 8, 8)[(4 * 8 + 4) * 4]
}
