package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry
import top.ltfan.backdrop.internal.genericBoundaryShaderFunctions

class BoundaryShaderGeometryCapacityTest {
    @Test
    fun paddedSegmentsDoNotChangeContainmentOrNearestBoundaryQueries() {
        val geometry = FlattenedBoundaryGeometry(Path().apply { addRect(Rect(2f, 2f, 10f, 10f)) })
        val actualSegments = geometry.segments
        val paddedSegments = FloatArray(8 * 4).also { actualSegments.copyInto(it) }
        val points =
            listOf(
                Offset(6f, 6f), // inside
                Offset(1f, 6f), // outside along an edge
                Offset(2f, 2f), // exact corner
                Offset(1f, 1f), // outside near a corner
                Offset(2.25f, 2.25f), // nearest point is the corner
            )

        val actual = renderQueries(geometry, actualSegments, 4, points)
        val padded = renderQueries(geometry, paddedSegments, 8, points)
        for (index in actual.indices) {
            assertEquals(
                actual[index],
                padded[index],
                .001f,
                "channel=${index % 4} point=${points[index / 4]}",
            )
        }
    }

    private fun renderQueries(
        geometry: FlattenedBoundaryGeometry,
        segments: FloatArray,
        segmentCapacity: Int,
        points: List<Offset>,
    ): FloatArray {
        val pointUniform = FloatArray(points.size * 2)
        points.forEachIndexed { index, point ->
            pointUniform[index * 2] = point.x
            pointUniform[index * 2 + 1] = point.y
        }
        val shader =
            RuntimeShader(
                """
                ${genericBoundaryShaderFunctions(geometry.segmentCount, segmentCapacity)}
                uniform float2 testPoints[${points.size}];
                half4 main(float2 coord) {
                  float x = floor(coord.x);
                  float2 point = x < 1.0 ? testPoints[0]
                    : x < 2.0 ? testPoints[1]
                    : x < 3.0 ? testPoints[2]
                    : x < 4.0 ? testPoints[3]
                    : testPoints[4];
                  float2 closest;
                  float3 nearest = nearestBoundaryWithPosition(point, closest);
                  float contained = containsPoint(point) ? 1.0 : 0.0;
                  return half4(closest.x, closest.y, nearest.x, contained);
                }
                """
                    .trimIndent()
            )
        shader.setFloatUniform("origin", 0f, 0f)
        shader.setFloatUniform("geometryScale", geometry.coordinateScale)
        shader.setFloatUniform("segments", segments)
        shader.setFloatUniform("testPoints", pointUniform)
        val runtimeShader = shader.asSkikoRuntimeShader().makeShader()
        val info =
            ImageInfo(points.size, 1, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            Paint().use { paint ->
                paint.shader = runtimeShader
                surface.canvas.drawRect(SkiaRect.makeWH(points.size.toFloat(), 1f), paint)
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(surface.readPixels(bitmap, 0, 0))
                val bytes = checkNotNull(bitmap.readPixels(info, points.size * 8))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(points.size * 4) {
                    halfToFloat(buffer.short.toInt() and 0xffff)
                }
            }
        }
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = if (bits and 0x8000 == 0) 1f else -1f
        val exponent = (bits ushr 10) and 31
        val mantissa = bits and 1023
        return sign *
            when (exponent) {
                0 -> Math.scalb(mantissa.toFloat(), -24)
                31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Math.scalb(1f + mantissa / 1024f, exponent - 15)
            }
    }
}
