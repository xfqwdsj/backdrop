package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.boundaryUniformCapacity
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry
import top.ltfan.backdrop.internal.genericBoundaryShaderFunctions
import top.ltfan.backdrop.internal.staticHighlightTileShader

class HighlightShaderVariantTest : PlatformGraphicsTest() {
    @Test
    fun geometryCapacityUsesPaddedDegenerateSegments() {
        assertEquals(1, boundaryUniformCapacity(0))
        assertEquals(4, boundaryUniformCapacity(3))
        val geometry = rectangleGeometry()
        val padded = FloatArray(8 * 4).also { geometry.segments.copyInto(it) }
        val shader = genericBoundaryShaderFunctions(segmentCount = 4, segmentCapacity = 8)

        assertEquals(32, padded.size)
        assertFalse(padded.drop(16).any { it != 0f })
        assertContains(shader, "uniform float4 segments[8]")
        assertEquals(1, Regex("if \\(denominator <= 0.0\\) continue;").findAll(shader).count())
    }

    @Test
    fun shaderUsesOneNearestBoundaryResponseAndFixedWidthProfile() {
        val shader =
            staticHighlightTileShader(
                geometry = rectangleGeometry(),
                style = HighlightStyle.Solid(Color.White),
                exclusionCount = 0,
            )

        assertContains(
            shader,
            "nearestBoundaryWithPosition(p, conservativeUpper + geometryScale * 0.000004, nearestPosition)",
        )
        assertContains(shader, "shadeHighlight(nearestPosition, normal, projection, color)")
        assertContains(shader, "float bandWidth = width * widthScale")
        assertContains(
            shader,
            "float q = light * fixedBandProfile(nearest.x, bandWidth, falloffCurve) * exclusionWeight(normal)",
        )
        assertFalse(
            shader.substringAfter("half4 shadeAt(float2 coord)").contains("for (int i = 0; i <"),
        )
        assertContains(shader, "float low = max(0.0, distance - 0.5)")
        assertContains(shader, "float progress = clamp(1.0 - (low + high) * 0.5 / bandWidth")
        assertContains(shader, "if (nearest.x >= bandWidth + 0.5) return half4(0.0)")
        assertContains(shader, "return shadeAt(coord);")
        assertFalse(shader.contains("shadeAt(coord + float2(-0.25"))
    }

    private fun rectangleGeometry(): FlattenedBoundaryGeometry =
        FlattenedBoundaryGeometry(Path().apply { addRect(Rect(0f, 0f, 8f, 8f)) })
}
