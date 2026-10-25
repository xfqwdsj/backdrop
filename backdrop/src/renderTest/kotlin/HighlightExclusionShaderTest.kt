package top.ltfan.backdrop

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.highlight.exclusionShader

class HighlightExclusionShaderTest : PlatformGraphicsTest() {
    @Test
    fun twoAngularCutsLeaveOppositeCardinalNormalsVisible() {
        val shader =
            RuntimeShader(
                """
            ${exclusionShader(2)}
            half4 main(float2 p) {
                float2 normal = p.x < 1.0 ? float2(1.0, 0.0) :
                    (p.x < 2.0 ? float2(0.0, 1.0) :
                    (p.x < 3.0 ? float2(-1.0, 0.0) : float2(0.0, -1.0)));
                return half4(exclusionWeight(normal), exclusions[0].x / 6.28318530718,
                    exclusions[1].x / 6.28318530718, 1.0);
            }
        """
                    .trimIndent(),
            )
        val radians = (PI / 180.0).toFloat()
        shader.setFloatUniform(
            "exclusions",
            floatArrayOf(270f * radians, 45f * radians, 0f, 180f * radians, 45f * radians, 0f),
        )
        val pixels = renderTestShader(shader, 4, 1)
        for ((index, expected) in listOf(1f, 1f, 0f, 0f).withIndex()) {
            assertEquals(.75f, pixels[index * 4 + 1], .001f, "First angular uniform")
            assertEquals(.5f, pixels[index * 4 + 2], .001f, "Second angular uniform")
            assertEquals(expected, pixels[index * 4], .001f, "Cardinal $index: ${pixels.toList()}")
        }
    }
}
