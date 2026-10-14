package top.ltfan.backdrop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry

class AnalyticHighlightGeometryTest {
    @Test
    fun rectangleAndCircularRoundedQueriesMatchGpuForInteriorExteriorAndTies() {
        val outlines =
            listOf(
                Outline.Rectangle(Rect(3f, 5f, 43f, 35f)),
                Outline.Rounded(
                    RoundRect(
                        3f,
                        5f,
                        43f,
                        35f,
                        CornerRadius(8f),
                        CornerRadius(12f),
                        CornerRadius(6f),
                        CornerRadius(10f),
                    )
                ),
                Outline.Rounded(
                    RoundRect(
                        3f,
                        5f,
                        43f,
                        35f,
                        CornerRadius.Zero,
                        CornerRadius(12f),
                        CornerRadius.Zero,
                        CornerRadius(10f),
                    )
                ),
            )
        val points =
            listOf(
                Offset(4f, 6f),
                Offset(15.25f, 5.5f),
                Offset(22.5f, 5.5f),
                Offset(22.5f, 20.5f),
                Offset(42f, 34f),
                Offset(43.5f, 20f),
                Offset(44f, 36f),
            )
        for (outline in outlines) {
            val boundary = HighlightBoundaryGeometry(outline)
            assertTrue(boundary.isAnalytic)
            assertEquals(0, boundary.segmentCount)
            assertEquals(boundary.shaderFunctions(1), boundary.shaderFunctions(8))
            for (point in points) assertGpuMatchesCpu(boundary, point)
        }
    }

    @Test
    fun analyticUniformUpdatesReuseOneShaderForDifferentSizesAndRadii() {
        val first =
            HighlightBoundaryGeometry(
                Outline.Rounded(RoundRect(Rect(2f, 3f, 34f, 25f), CornerRadius(7f)))
            )
        val second =
            HighlightBoundaryGeometry(
                Outline.Rounded(RoundRect(Rect(4f, 1f, 48f, 39f), CornerRadius(14f)))
            )
        val source = first.shaderFunctions(1) + probeMain()
        assertEquals(source, second.shaderFunctions(1) + probeMain())
        val shader = RuntimeShader(source)
        val point = Offset(8.25f, 7.5f)
        first.configureShader(shader)
        shader.setFloatUniform("probePoint", point.x, point.y)
        assertShaderBoundary(first, shader, point)
        second.configureShader(shader)
        assertShaderBoundary(second, shader, point)
    }

    @Test
    fun invalidRoundedAndEllipticalOutlinesKeepFlattenedFallback() {
        val invalid =
            HighlightBoundaryGeometry(
                Outline.Rounded(
                    RoundRect(
                        0f,
                        0f,
                        20f,
                        20f,
                        CornerRadius(12f),
                        CornerRadius(12f),
                        CornerRadius(0f),
                        CornerRadius(0f),
                    )
                )
            )
        val elliptical =
            HighlightBoundaryGeometry(
                Outline.Rounded(
                    RoundRect(
                        0f,
                        0f,
                        30f,
                        20f,
                        CornerRadius(8f, 6f),
                        CornerRadius(8f, 6f),
                        CornerRadius(8f, 6f),
                        CornerRadius(8f, 6f),
                    )
                )
            )
        val generic =
            HighlightBoundaryGeometry(
                Outline.Generic(Path().apply { addRect(Rect(0f, 0f, 20f, 20f)) })
            )
        assertFalse(invalid.isAnalytic)
        assertFalse(elliptical.isAnalytic)
        assertFalse(generic.isAnalytic)
        assertTrue(invalid.cpuGeometry().segmentCount > 0)
        assertTrue(elliptical.cpuGeometry().segmentCount > 0)
        assertTrue(generic.cpuGeometry().segmentCount > 0)
    }

    @Test
    fun overlappingRoundedCornerRegionsApplyEveryContainmentConstraint() {
        val boundary =
            HighlightBoundaryGeometry(
                Outline.Rounded(
                    RoundRect(
                        0f,
                        0f,
                        100f,
                        100f,
                        CornerRadius(90f),
                        CornerRadius.Zero,
                        CornerRadius(90f),
                        CornerRadius.Zero,
                    )
                )
            )
        val point = Offset(80f, 80f)
        assertTrue(boundary.isAnalytic)
        assertFalse(boundary.contains(point))

        val shader = RuntimeShader(boundary.shaderFunctions(1) + containsProbeMain())
        boundary.configureShader(shader)
        shader.setFloatUniform("probePoint", point.x, point.y)
        assertEquals(0f, render(shader)[0])
    }

    private fun assertGpuMatchesCpu(boundary: HighlightBoundaryGeometry, point: Offset) {
        val shader = RuntimeShader(boundary.shaderFunctions(1) + probeMain())
        boundary.configureShader(shader)
        shader.setFloatUniform("probePoint", point.x, point.y)
        assertShaderBoundary(boundary, shader, point)
    }

    private fun assertShaderBoundary(
        boundary: HighlightBoundaryGeometry,
        shader: RuntimeShader,
        point: Offset,
    ) {
        val actual = render(shader)
        val expected = boundary.nearestBoundary(point)
        assertEquals(expected.distance / 64f, actual[0], .002f)
        assertEquals((expected.outwardNormal.x + 1f) * .5f, actual[1], .002f)
        assertEquals((expected.outwardNormal.y + 1f) * .5f, actual[2], .002f)

        val positionShader = RuntimeShader(boundary.shaderFunctions(1) + positionProbeMain())
        boundary.configureShader(positionShader)
        positionShader.setFloatUniform("probePoint", point.x, point.y)
        val position = render(positionShader)
        assertEquals(expected.position.x / 32f, position[0], .003f)
        assertEquals(expected.position.y / 32f, position[1], .003f)
    }

    private fun probeMain(): String =
        """
        uniform float2 probePoint;
        half4 main(float2 coord) {
          float2 closest;
          float3 boundary = nearestBoundaryWithPosition(probePoint, closest);
          return half4(boundary.x / 64.0, (boundary.y + 1.0) * 0.5,
            (boundary.z + 1.0) * 0.5, 1.0);
        }
        """
            .trimIndent()

    private fun containsProbeMain(): String =
        """
        uniform float2 probePoint;
        half4 main(float2 coord) {
          return half4(containsPoint(probePoint) ? 1.0 : 0.0, 0.0, 0.0, 1.0);
        }
        """
            .trimIndent()

    private fun positionProbeMain(): String =
        """
        uniform float2 probePoint;
        half4 main(float2 coord) {
          float2 closest;
          nearestBoundaryWithPosition(probePoint, closest);
          return half4(closest.x / 32.0, closest.y / 32.0, 0.0, 1.0);
        }
        """
            .trimIndent()

    private fun render(shader: RuntimeShader): FloatArray {
        val info = ImageInfo(1, 1, ColorType.RGBA_F16, ColorAlphaType.UNPREMUL)
        Surface.makeRaster(info).use { surface ->
            Paint().use { paint ->
                paint.shader = shader.asSkikoRuntimeShader().makeShader()
                surface.canvas.drawRect(SkiaRect.makeWH(1f, 1f), paint)
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(surface.readPixels(bitmap, 0, 0))
                val buffer =
                    ByteBuffer.wrap(bitmap.readPixels(info, 8)!!).order(ByteOrder.nativeOrder())
                return FloatArray(4) { half(buffer.getShort(it * 2)) }
            }
        }
    }

    private fun half(value: Short): Float {
        val bits = value.toInt() and 0xffff
        val sign = if (bits and 0x8000 == 0) 1f else -1f
        val exponent = bits ushr 10 and 0x1f
        val mantissa = bits and 0x3ff
        return sign *
            if (exponent == 0) Math.scalb(mantissa.toFloat(), -24)
            else Math.scalb(1f + mantissa / 1024f, exponent - 15)
    }
}
