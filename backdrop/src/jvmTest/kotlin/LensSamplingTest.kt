package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensSamplingTest {
    @Test
    fun crossingTheCenterIncludesLargeInwardOvershoot() {
        val size = Size(100f, 80f)
        val radii = FloatArray(4) { 20f }
        val output = Rect(0f, 0f, 100f, 80f)
        val plain = lensSampling(size, radii, 36f, 75f, dispersion = false).requiredInput(output)
        val chromatic = lensSampling(size, radii, 36f, 75f, dispersion = true).requiredInput(output)

        assertEquals(-25.5f, plain.left)
        assertEquals(125.5f, plain.right)
        assertEquals(-100.5f, chromatic.left)
        assertEquals(200.5f, chromatic.right)
    }

    @Test
    fun deepInteriorUsesIdentityAndNearBoundaryUsesAttenuatedRefraction() {
        val size = Size(100f, 80f)
        val radii = floatArrayOf(0f, 10f, 16f, 20f)
        val output = Rect(45f, 35f, 55f, 45f)
        val deep = lensSampling(size, radii, 14f, 20f, dispersion = false).requiredInput(output)
        val nearBoundary =
            lensSampling(size, radii, 120f, 20f, dispersion = false).requiredInput(output)
        val maximumSdf = -35f
        val normalizedSdf = 1f + maximumSdf / 120f
        val activeAmount = 20f * (1f - sqrt(1f - normalizedSdf * normalizedSdf))

        assertEquals(output, deep)
        assertTrue(activeAmount < 20f)
        assertEquals(49.5f - activeAmount, nearBoundary.left, 0.001f)
    }

    @Test
    fun sampledShaderCoordinatesStayInsideTheCertifiedBounds() {
        val random = Random(8127)
        repeat(100) {
            val size = Size(random.nextFloat() * 180f + 20f, random.nextFloat() * 140f + 20f)
            val left = (random.nextFloat() * 3f - 1.5f) * size.width
            val top = (random.nextFloat() * 3f - 1.5f) * size.height
            val output =
                Rect(
                    left,
                    top,
                    left + random.nextFloat() * size.width,
                    top + random.nextFloat() * size.height,
                )
            val amount = random.nextFloat() * size.maxDimension * 3f
            val height = random.nextFloat() * size.minDimension * 1.5f + 1f
            val depth = random.nextBoolean()
            val dispersion = random.nextBoolean()
            val maximumRadius = size.minDimension * 0.5f
            val radii = FloatArray(4) { random.nextFloat() * maximumRadius }
            val required =
                lensSampling(size, radii, height, amount, dispersion).requiredInput(output)
            repeat(500) {
                val x = output.left + random.nextFloat() * output.width
                val y = output.top + random.nextFloat() * output.height
                val source =
                    roundedRectSamples(x, y, size, radii, amount, height, depth, dispersion)
                source.forEach { (sx, sy) ->
                    assertTrue(sx >= required.left - 0.001f && sx <= required.right + 0.001f)
                    assertTrue(sy >= required.top - 0.001f && sy <= required.bottom + 0.001f)
                }
            }
        }
    }
}

private fun roundedRectSamples(
    x: Float,
    y: Float,
    size: Size,
    radii: FloatArray,
    amount: Float,
    height: Float,
    depth: Boolean,
    dispersion: Boolean,
): List<Pair<Float, Float>> {
    val halfWidth = size.width * 0.5f
    val halfHeight = size.height * 0.5f
    val px = x - halfWidth
    val py = y - halfHeight
    val radius =
        when {
            px >= 0f && py <= 0f -> radii[1]
            px >= 0f -> radii[2]
            py <= 0f -> radii[0]
            else -> radii[3]
        }
    val cornerX = abs(px) - (halfWidth - radius)
    val cornerY = abs(py) - (halfHeight - radius)
    val outsideX = max(cornerX, 0f)
    val outsideY = max(cornerY, 0f)
    val signedDistance =
        sqrt(outsideX * outsideX + outsideY * outsideY) - radius + min(max(cornerX, cornerY), 0f)
    val clampedDistance =
        if (-signedDistance >= height) return listOf(x to y) else min(signedDistance, 0f)
    val t = 1f + clampedDistance / height
    val d = -(1f - sqrt(max(0f, 1f - t * t))) * amount

    val gradRadius = min(radius * 1.5f, min(halfWidth, halfHeight))
    val gx = abs(px) - (halfWidth - gradRadius)
    val gy = abs(py) - (halfHeight - gradRadius)
    var nx: Float
    var ny: Float
    if (gx >= 0f || gy >= 0f) {
        val ox = max(gx, 0f)
        val oy = max(gy, 0f)
        val length = sqrt(ox * ox + oy * oy)
        nx = if (length == 0f) 0f else px.sign() * ox / length
        ny = if (length == 0f) 0f else py.sign() * oy / length
    } else {
        val xAxis = if (gy <= gx) 1f else 0f
        nx = px.sign() * xAxis
        ny = py.sign() * (1f - xAxis)
    }
    if (depth) {
        val length = sqrt(px * px + py * py)
        if (length > 0f) {
            nx += px / length
            ny += py / length
        }
    }
    val length = sqrt(nx * nx + ny * ny)
    if (length > 0f) {
        nx /= length
        ny /= length
    }
    val intensity = if (dispersion) px * py / (halfWidth * halfHeight) else 0f
    return listOf(1f, 2f / 3f, 1f / 3f, 0f, -1f / 3f, -2f / 3f, -1f).map { factor ->
        val scale = d * (1f + factor * intensity)
        (x + scale * nx) to (y + scale * ny)
    }
}

private fun Float.sign(): Float =
    when {
        this > 0f -> 1f
        this < 0f -> -1f
        else -> 0f
    }
