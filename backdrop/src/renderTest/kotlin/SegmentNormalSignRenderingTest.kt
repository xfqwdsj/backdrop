package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry
import top.ltfan.backdrop.internal.genericBoundaryShaderFunctions

class SegmentNormalSignRenderingTest : PlatformGraphicsTest() {
    @Test
    fun certifiedSegmentSignsMatchFullGpuProbeAndCpuOracle() {
        val fixtures =
            listOf(
                RectanglePath to 4,
                concavePath() to null,
                holedPath() to null,
                disconnectedPath() to null,
                thinNotchPath() to null,
                manySegmentPath(31) to null,
                manySegmentPath(32) to null,
                manySegmentPath(33) to null,
                curvedPath() to null,
                largeCoordinatePath() to null,
                Path().apply { addRect(Rect(0f, 0f, 32f, 32f)) } to 0,
            )
        for ((path, expectedCertifiedCount) in fixtures) {
            val geometry = FlattenedBoundaryGeometry(path)
            val certificate = geometry.normalSigns
            if (expectedCertifiedCount != null) {
                assertEquals(expectedCertifiedCount, certificate.count { it != 0f })
            }
            assertSame(certificate, geometry.normalSigns)
            val queries = sampleQueries(geometry)
            val shader = createProbeShader(geometry, queries.size)
            val fallback = render(shader, geometry, queries, FloatArray(geometry.segmentCount))
            val certified = render(shader, geometry, queries, certificate)
            for (index in queries.indices) {
                val expected = geometry.nearestBoundary(queries[index])
                val offset = index * 4
                assertEquals(
                    expected.distance / geometry.coordinateScale,
                    certified[offset],
                    .004f,
                    "CPU distance $index",
                )
                assertEquals(fallback[offset], certified[offset], .004f, "distance $index")
                assertEquals(fallback[offset + 1], certified[offset + 1], .004f, "normal x $index")
                assertEquals(fallback[offset + 2], certified[offset + 2], .004f, "normal y $index")
                assertEquals(
                    expected.outwardNormal.x,
                    certified[offset + 1] * 2f - 1f,
                    .006f,
                    "CPU normal x $index",
                )
                assertEquals(
                    expected.outwardNormal.y,
                    certified[offset + 2] * 2f - 1f,
                    .006f,
                    "CPU normal y $index",
                )
            }
        }
    }

    @Test
    fun groupedTraversalMatchesOriginalDistanceNormalAndTieOrder() {
        val fixtures =
            listOf(
                RectanglePath,
                concavePath(),
                holedPath(),
                disconnectedPath(),
                thinNotchPath(),
                largeCoordinatePath(),
            )
        for (path in fixtures) {
            val boundary = HighlightBoundaryGeometry(Outline.Generic(path))
            val geometry = boundary.cpuGeometry()
            var capacity = 16
            while (capacity < geometry.segmentCount) capacity *= 2
            val points =
                sampleQueries(geometry) +
                    listOf(Offset.Zero, Offset(16f, 16f), Offset(-1000000f, -1000000f))
            val original =
                render(
                    createProbeShader(geometry, points.size, capacity),
                    geometry,
                    points,
                    geometry.normalSigns,
                    capacity,
                )
            for (groupSize in listOf(4, 8, 16)) {
                val bounds = boundary.segmentGroupBounds(capacity, groupSize)
                assertSame(bounds, boundary.segmentGroupBounds(capacity, groupSize))
                val grouped =
                    render(
                        createProbeShader(geometry, points.size, capacity, groupSize),
                        geometry,
                        points,
                        geometry.normalSigns,
                        capacity,
                        bounds,
                    )
                assertContentEquals(original, grouped)
            }
        }
    }

    @Test
    fun paddedNormalSignsAreCachedPerGeometry() {
        val boundary = HighlightBoundaryGeometry(Outline.Generic(RectanglePath))
        val first = boundary.paddedNormalSigns(8)
        val second = boundary.paddedNormalSigns(8)
        assertSame(first, second)
        assertTrue(
            first.take(4).all { it == -1f || it == 1f },
            "expected rectangle signs, got ${first.toList()}",
        )
        assertTrue(first.drop(4).all { it == 0f })
    }

    private fun createProbeShader(
        geometry: FlattenedBoundaryGeometry,
        count: Int,
        capacity: Int = geometry.segmentCount,
        groupSize: Int = 0,
    ): RuntimeShader =
        RuntimeShader(
            """
            ${genericBoundaryShaderFunctions(geometry.segmentCount, capacity, groupSize)}
            uniform float2 probePoints[$count];
            uniform float outputScale;
            float2 probePoint(int index) {
              ${ (0 until count).joinToString("\n") { "if (index == $it) return probePoints[$it];" } }
              return float2(0.0);
            }
            half4 main(float2 coord) {
              int index = int(floor(coord.x));
              float2 closest;
              float3 boundary = nearestBoundaryWithPosition(probePoint(index), closest);
              return half4(boundary.x / outputScale, (boundary.y + 1.0) * 0.5,
                (boundary.z + 1.0) * 0.5, 1.0);
            }
            """
                .trimIndent(),
        )

    private fun render(
        shader: RuntimeShader,
        geometry: FlattenedBoundaryGeometry,
        points: List<Offset>,
        signs: FloatArray,
        capacity: Int = geometry.segmentCount,
        groupBounds: FloatArray? = null,
    ): FloatArray {
        shader.setFloatUniform("origin", 0f, 0f)
        shader.setFloatUniform("geometryScale", geometry.coordinateScale)
        shader.setFloatUniform("segments", geometry.segments.copyOf(capacity * 4))
        shader.setFloatUniform("segmentNormalSigns", signs.copyOf(capacity))
        if (groupBounds != null) shader.setFloatUniform("segmentGroupBounds", groupBounds)
        shader.setFloatUniform(
            "probePoints",
            FloatArray(points.size * 2).also { values ->
                points.forEachIndexed { index, point ->
                    values[index * 2] = point.x
                    values[index * 2 + 1] = point.y
                }
            },
        )
        shader.setFloatUniform("outputScale", geometry.coordinateScale)
        return renderTestShader(shader, points.size, 1)
    }

    private fun sampleQueries(geometry: FlattenedBoundaryGeometry): List<Offset> {
        val result = ArrayList<Offset>()
        for (index in 0 until geometry.segmentCount) {
            val offset = index * 4
            val ax = geometry.segments[offset]
            val ay = geometry.segments[offset + 1]
            val bx = geometry.segments[offset + 2]
            val by = geometry.segments[offset + 3]
            val dx = bx - ax
            val dy = by - ay
            val length = sqrt(dx * dx + dy * dy)
            if (length == 0f) continue
            val normalX = dy / length
            val normalY = -dx / length
            for (amount in listOf(.1f, .5f, .9f)) {
                val x = ax + dx * amount
                val y = ay + dy * amount
                result += Offset(x + normalX * .25f, y + normalY * .25f)
                result += Offset(x - normalX * .25f, y - normalY * .25f)
            }
        }
        val outside = geometry.coordinateScale * 2f
        result += Offset(outside, outside)
        return result
    }

    private fun concavePath(): Path =
        Path().apply {
            moveTo(0f, 0f)
            lineTo(32f, 0f)
            lineTo(32f, 8f)
            lineTo(12f, 8f)
            lineTo(12f, 32f)
            lineTo(0f, 32f)
            close()
        }

    private fun holedPath(): Path =
        Path().apply {
            if (
                !op(
                    RectanglePath,
                    Path().apply { addRect(Rect(8f, 8f, 24f, 24f)) },
                    PathOperation.Difference,
                )
            ) {
                addRect(Rect(0f, 0f, 32f, 32f))
            }
        }

    private fun disconnectedPath(): Path =
        Path().apply {
            addRect(Rect(0f, 0f, 8f, 8f))
            addRect(Rect(24f, 24f, 32f, 32f))
        }

    private fun thinNotchPath(): Path =
        Path().apply {
            moveTo(0f, 0f)
            lineTo(32f, 0f)
            lineTo(32f, 32f)
            lineTo(16.1f, 32f)
            lineTo(16.1f, 8f)
            lineTo(15.9f, 8f)
            lineTo(15.9f, 32f)
            lineTo(0f, 32f)
            close()
        }

    private fun manySegmentPath(count: Int): Path =
        Path().apply {
            repeat(count) { index ->
                val angle = index * 2.0 * PI / count
                val radius = 32.0 + 9.0 * sin(angle * 7.0)
                val x = (radius * cos(angle)).toFloat()
                val y = (radius * sin(angle)).toFloat()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

    private fun curvedPath(): Path =
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(12f, 50f, 70f, -15f, 80f, 30f)
            quadraticTo(20f, 80f, 0f, 0f)
            close()
        }

    private fun largeCoordinatePath(): Path =
        Path().apply {
            addRect(Rect(1_048_576f, 1_048_576f, 1_048_640f, 1_048_640f))
        }

    private companion object {
        val RectanglePath: Path
            get() =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(32f, 0f)
                    lineTo(32f, 32f)
                    lineTo(0f, 32f)
                    close()
                }
    }
}
