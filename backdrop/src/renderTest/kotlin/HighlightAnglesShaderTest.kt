package top.ltfan.backdrop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.highlight.HighlightAngleDomain
import top.ltfan.backdrop.highlight.angularDirectionShader
import top.ltfan.backdrop.highlight.highlightAngles

class HighlightAnglesShaderTest : PlatformGraphicsTest() {
    @Test
    fun angularShaderMatchesCpuForRotationFoldingSeamsAndClearing() {
        val count = 1441
        for (domain in HighlightAngleDomain.entries) {
            val limit = if (domain == HighlightAngleDomain.Unsigned) 180f else 360f
            val profile =
                highlightAngles(domain) {
                    widthScale(0f, limit, .2f, 2f, BackdropCurve.CubicBezier(.1f, .3f, .7f, .8f))
                    brightness(0f, limit, 2f, .5f)
                    clear(setOf(40f..50f, 90f..110f))
                    widthScale(45f, 46f, 3f)
                }
            for (rotation in listOf(0f, 37.25f, 270f)) {
                val shader =
                    RuntimeShader(
                        """
                    uniform float2 directionVector;
                    uniform float rotation;
                    $BackdropCurveShader
                    ${angularDirectionShader(profile.capacity)}
                    half4 main(float2 p) {
                        float angle = radians(floor(p.x) * 0.25 + rotation);
                        float2 value = angularResponse(float2(cos(angle), sin(angle)));
                        return half4(value, 0.0, 1.0);
                    }
                """
                            .trimIndent(),
                    )
                val radians = rotation.toDouble() * PI / 180.0
                shader.setFloatUniform(
                    "directionVector",
                    cos(radians).toFloat(),
                    sin(radians).toFloat(),
                )
                shader.setFloatUniform("rotation", rotation)
                profile.upload(shader)
                val pixels = renderTestShader(shader, count, 1)
                repeat(count) { i ->
                    val angle = i * .25f
                    assertEquals(
                        profile.widthScaleAt(angle),
                        pixels[i * 4],
                        .003f,
                        "width domain=$domain rotation=$rotation angle=$angle",
                    )
                    assertEquals(
                        profile.brightnessAt(angle),
                        pixels[i * 4 + 1],
                        .003f,
                        "brightness domain=$domain rotation=$rotation angle=$angle",
                    )
                }
            }
        }
    }
}
