package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.asSkikoRuntimeShader
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry

class SegmentNormalSignRenderingTest {
    @Test
    fun certifiedSegmentSignsMatchFullGpuProbeAndCpuOracle() {
        val fixtures =
            listOf(
                RectanglePath to 4,
                concavePath() to null,
                holedPath() to null,
                disconnectedPath() to null,
                thinNotchPath() to null,
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

    private fun createProbeShader(geometry: FlattenedBoundaryGeometry, count: Int): RuntimeShader =
        RuntimeShader(
            """
            ${genericBoundaryShaderFunctions(geometry.segmentCount)}
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
                .trimIndent()
        )

    private fun render(
        shader: RuntimeShader,
        geometry: FlattenedBoundaryGeometry,
        points: List<Offset>,
        signs: FloatArray,
    ): FloatArray {
        shader.setFloatUniform("origin", 0f, 0f)
        shader.setFloatUniform("geometryScale", geometry.coordinateScale)
        shader.setFloatUniform("segments", geometry.segments)
        shader.setFloatUniform("segmentNormalSigns", signs)
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
        val info =
            ImageInfo(points.size, 1, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            Paint().use { paint ->
                paint.shader = shader.asSkikoRuntimeShader().makeShader()
                surface.canvas.drawRect(SkiaRect.makeWH(points.size.toFloat(), 1f), paint)
            }
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(surface.readPixels(bitmap, 0, 0))
                val bytes = checkNotNull(bitmap.readPixels(info, points.size * 8))
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                return FloatArray(points.size * 4) { halfToFloat(buffer.short.toInt() and 0xffff) }
            }
        }
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
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
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

    private fun largeCoordinatePath(): Path =
        Path().apply {
            addRect(Rect(1_048_576f, 1_048_576f, 1_048_640f, 1_048_640f))
        }

    private companion object {
        val RectanglePath =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(32f, 0f)
                lineTo(32f, 32f)
                lineTo(0f, 32f)
                close()
            }
    }
}
