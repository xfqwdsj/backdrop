package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry
import top.ltfan.backdrop.internal.genericBoundaryShaderFunctions

class BoundaryShaderGeometryCapacityTest : PlatformGraphicsTest() {
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

    @Test
    fun oneShaderObservesActiveCountChangesWithoutReadingInactiveTailSegments() {
        val geometry = FlattenedBoundaryGeometry(Path().apply { addRect(Rect(2f, 2f, 10f, 10f)) })
        val tail = FlattenedBoundaryGeometry(Path().apply { addRect(Rect(-2f, -2f, 12f, 12f)) })
        val segments = geometry.segments + tail.segments
        val points =
            listOf(
                Offset(6f, 6f),
                Offset(1f, 6f),
                Offset(2f, 2f),
                Offset(1f, 1f),
                Offset(2.25f, 2.25f),
            )
        val expected = renderQueries(geometry, geometry.segments.copyOf(8 * 4), 8, points)
        val shader = queryShader(geometry, segments, 8, points)
        val first = renderTestShader(shader, points.size, 1)
        assertContentEquals(expected, first)
        shader.setIntUniform("activeSegmentCount", 8)
        assertFalse(first.contentEquals(renderTestShader(shader, points.size, 1)))
        shader.setIntUniform("activeSegmentCount", 4)
        assertContentEquals(first, renderTestShader(shader, points.size, 1))
    }

    private fun renderQueries(
        geometry: FlattenedBoundaryGeometry,
        segments: FloatArray,
        segmentCapacity: Int,
        points: List<Offset>,
    ): FloatArray =
        renderTestShader(queryShader(geometry, segments, segmentCapacity, points), points.size, 1)

    private fun queryShader(
        geometry: FlattenedBoundaryGeometry,
        segments: FloatArray,
        segmentCapacity: Int,
        points: List<Offset>,
    ): RuntimeShader {
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
                    .trimIndent(),
            )
        shader.setFloatUniform("origin", 0f, 0f)
        shader.setFloatUniform("geometryScale", geometry.coordinateScale)
        shader.setIntUniform("activeSegmentCount", geometry.segmentCount)
        shader.setFloatUniform("segments", segments)
        shader.setFloatUniform("testPoints", pointUniform)
        return shader
    }
}
