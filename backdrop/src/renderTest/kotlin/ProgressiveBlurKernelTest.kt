package top.ltfan.backdrop

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.effects.progressiveBlurShader

class ProgressiveBlurKernelTest : PlatformGraphicsTest() {
    @Test
    fun gaussianRecurrenceMatchesIndependentExponentialWeights() {
        val width = 17
        val height = 9
        for (vertical in listOf(false, true)) for (radius in
            listOf(.25f, 1f, 1.25f, 2f, 3.25f, 8f, 32f, 149f, 200f)) {
            // An analytic input isolates kernel arithmetic from native image interpolation kernels.
            val text =
                progressiveBlurShader(vertical)
                    .replace(
                        "uniform shader content;",
                        """
                    half4 sampleContent(float2 p) {
                        float value = 0.5 + 0.4 * cos(p.${if (vertical) "y" else "x"} * 0.628318530718);
                        return half4(value * 0.375, value * 0.1875, 0.0, 0.375);
                    }
                """
                            .trimIndent(),
                    )
                    .replace("content.eval(", "sampleContent(")
            val shader = RuntimeShader(text)
            shader.setFloatUniform("anchors", 0f, width.toFloat())
            shader.setFloatUniform("blurRadius", radius)
            shader.setFloatUniform("axis", 0f)
            shader.setFloatUniform("curve", BackdropCurve.Linear.shaderControls())
            shader.setFloatUniform("intensities", 0f, 1f)
            val pixels = renderTestShader(shader, width, height)
            for (y in 0 until height) for (x in 0 until width) {
                val localRadius = radius * ((x + .5) / width)
                val position = if (vertical) y + .5 else x + .5
                val expected = reference(position, localRadius) * .375
                val offset = (y * width + x) * 4
                assertEquals(
                    expected.toFloat(),
                    pixels[offset],
                    .0015f,
                    "vertical=$vertical radius=$radius pixel=$x,$y",
                )
                assertEquals(.375f, pixels[offset + 3], .001f)
            }
        }
    }

    private fun reference(position: Double, radius: Double): Double {
        fun sample(at: Double) = .5 + .4 * cos(at * PI / 5)
        if (radius <= 0) return sample(position)
        val sigma = max(radius / 2, .0001)
        fun weight(distance: Double) = exp(-distance * distance / (2 * sigma * sigma))
        val limit = ceil(radius)
        var total = sample(position)
        var norm = 1.0
        var tap = 1.0
        while (tap < 150 && tap < limit) {
            val a = weight(tap)
            val b = weight(tap + 1)
            val combined = a + b
            val offset = tap + b / combined
            total += combined * (sample(position - offset) + sample(position + offset))
            norm += 2 * combined
            tap += 2
        }
        if (limit < 150 && limit % 2 == 1.0) {
            val value = weight(limit)
            total += value * (sample(position - limit) + sample(position + limit))
            norm += 2 * value
        }
        return total / norm
    }
}
