package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PathSegment
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A line approximation of a filled path boundary. Curves are subdivided until their control points
 * are within 0.05 px of the finite chord. Keep this value in pixel units: these segments are
 * consumed in the same local pixel coordinates as the contour shader.
 */
internal class FlattenedBoundaryGeometry(path: Path) {
    /** Four floats per segment: start x/y followed by end x/y. */
    internal val segments: FloatArray

    internal val segmentCount: Int
        get() = segments.size / 4

    private val flattenedPath: Path =
        Path().let { result ->
            if (result.op(path, Path(), PathOperation.Union)) result else path
        }

    internal val bounds: Rect = flattenedPath.getBounds()

    internal val coordinateScale: Float =
        maxOf(1f, abs(bounds.left), abs(bounds.top), abs(bounds.right), abs(bounds.bottom))

    /** A nonzero value certifies the segment's outward normal for its full length. */
    internal val normalSigns: FloatArray by
        lazy(LazyThreadSafetyMode.NONE) {
            val signs = FloatArray(segmentCount)
            val tolerance = 9.5367432e-7 * coordinateScale.toDouble()
            val probe = maxOf(0.0001, tolerance * 4.0)
            val distanceLimit = tolerance * 2.0
            val distanceLimitSquared = distanceLimit * distanceLimit
            for (index in 0 until segmentCount) {
                val offset = index * 4
                val ax = segments[offset].toDouble()
                val ay = segments[offset + 1].toDouble()
                val bx = segments[offset + 2].toDouble()
                val by = segments[offset + 3].toDouble()
                val dx = bx - ax
                val dy = by - ay
                val length = sqrt(dx * dx + dy * dy)
                if (length <= 0.0) continue
                val normalX = dy / length
                val normalY = -dx / length
                val shiftedAx = ax + normalX * probe
                val shiftedAy = ay + normalY * probe
                val shiftedBx = bx + normalX * probe
                val shiftedBy = by + normalY * probe
                val minX = minOf(shiftedAx, shiftedBx) - distanceLimit
                val minY = minOf(shiftedAy, shiftedBy) - distanceLimit
                val maxX = maxOf(shiftedAx, shiftedBx) + distanceLimit
                val maxY = maxOf(shiftedAy, shiftedBy) + distanceLimit
                var touchesBoundary = false
                for (otherIndex in 0 until segmentCount) {
                    val otherOffset = otherIndex * 4
                    val cx = segments[otherOffset].toDouble()
                    val cy = segments[otherOffset + 1].toDouble()
                    val ex = segments[otherOffset + 2].toDouble()
                    val ey = segments[otherOffset + 3].toDouble()
                    if (
                        minOf(cx, ex) > maxX ||
                            maxOf(cx, ex) < minX ||
                            minOf(cy, ey) > maxY ||
                            maxOf(cy, ey) < minY
                    ) {
                        continue
                    }
                    if (
                        distanceSquaredBetweenSegments(
                            shiftedAx,
                            shiftedAy,
                            shiftedBx,
                            shiftedBy,
                            cx,
                            cy,
                            ex,
                            ey,
                        ) <= distanceLimitSquared
                    ) {
                        touchesBoundary = true
                        break
                    }
                }
                if (!touchesBoundary) {
                    val midpointX = (shiftedAx + shiftedBx) * 0.5
                    val midpointY = (shiftedAy + shiftedBy) * 0.5
                    signs[index] = if (contains(midpointX, midpointY)) -1f else 1f
                }
            }
            signs
        }

    internal fun contains(point: Offset): Boolean {
        var crossings = 0
        for (index in segments.indices step 4) {
            val x0 = segments[index]
            val y0 = segments[index + 1]
            val x1 = segments[index + 2]
            val y1 = segments[index + 3]
            if ((y0 > point.y) != (y1 > point.y)) {
                val crossingX = x0 + (point.y - y0) * (x1 - x0) / (y1 - y0)
                if (crossingX > point.x) {
                    crossings++
                }
            }
        }
        return crossings % 2 != 0
    }

    private fun contains(x: Double, y: Double): Boolean {
        var crossings = 0
        for (index in segments.indices step 4) {
            val x0 = segments[index].toDouble()
            val y0 = segments[index + 1].toDouble()
            val x1 = segments[index + 2].toDouble()
            val y1 = segments[index + 3].toDouble()
            if ((y0 > y) != (y1 > y)) {
                val crossingX = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
                if (crossingX > x) crossings++
            }
        }
        return crossings % 2 != 0
    }

    internal fun nearestBoundary(point: Offset): Boundary {
        var bestDistance = Float.POSITIVE_INFINITY
        var bestNormal = Offset.Zero
        var bestPosition = Offset.Zero
        val coordinateScale = maxOf(coordinateScale, abs(point.x), abs(point.y))
        val tolerance = (8f * Float.fromBits(0x34000000)) * coordinateScale
        for (index in segments.indices step 4) {
            val start = Offset(segments[index], segments[index + 1])
            val end = Offset(segments[index + 2], segments[index + 3])
            val edge = end - start
            val lengthSquared = edge.x * edge.x + edge.y * edge.y
            if (lengthSquared == 0f) continue
            val relative = point - start
            val amount =
                ((relative.x * edge.x + relative.y * edge.y) / lengthSquared).coerceIn(0f, 1f)
            val closest = start + edge * amount
            val delta = point - closest
            val distanceSquared = delta.x * delta.x + delta.y * delta.y
            val distance = sqrt(distanceSquared)
            if (distance < bestDistance - tolerance) {
                bestDistance = distance
                bestNormal = Offset(edge.y, -edge.x) / sqrt(lengthSquared)
                bestPosition = closest
            }
        }
        val probeDistance = maxOf(0.0001f, tolerance * 4f)
        if (contains(bestPosition + bestNormal * probeDistance)) bestNormal = -bestNormal
        return Boundary(
            bestDistance,
            bestNormal,
            bestPosition,
            if (contains(point)) -bestDistance else bestDistance,
        )
    }

    internal data class Boundary(
        val distance: Float,
        val outwardNormal: Offset,
        val position: Offset,
        val signedDistance: Float,
    )

    init {
        val coordinates = ArrayList<Float>()
        require(
            bounds.left.isFinite() &&
                bounds.top.isFinite() &&
                bounds.right.isFinite() &&
                bounds.bottom.isFinite()
        )
        val iterator = PathIterator(flattenedPath, PathIterator.ConicEvaluation.AsConic)
        val points = FloatArray(8)
        var contourX = 0f
        var contourY = 0f
        var currentX = 0f
        var currentY = 0f
        var hasContour = false

        fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
            require(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite())
            if (x0 != x1 || y0 != y1) {
                coordinates.add(x0)
                coordinates.add(y0)
                coordinates.add(x1)
                coordinates.add(y1)
            }
        }

        fun closeContour() {
            if (hasContour) add(currentX, currentY, contourX, contourY)
            hasContour = false
        }

        while (iterator.hasNext()) {
            when (iterator.next(points)) {
                PathSegment.Type.Move -> {
                    closeContour()
                    contourX = points[0]
                    contourY = points[1]
                    currentX = contourX
                    currentY = contourY
                    hasContour = true
                }
                PathSegment.Type.Line -> {
                    if (!hasContour) {
                        contourX = points[0]
                        contourY = points[1]
                        currentX = contourX
                        currentY = contourY
                        hasContour = true
                    }
                    add(points[0], points[1], points[2], points[3])
                    currentX = points[2]
                    currentY = points[3]
                }
                PathSegment.Type.Quadratic -> {
                    if (!hasContour) {
                        contourX = points[0]
                        contourY = points[1]
                        currentX = contourX
                        currentY = contourY
                        hasContour = true
                    }
                    flattenQuadratic(
                        points[0],
                        points[1],
                        points[2],
                        points[3],
                        points[4],
                        points[5],
                        ::add,
                    )
                    currentX = points[4]
                    currentY = points[5]
                }
                PathSegment.Type.Cubic -> {
                    if (!hasContour) {
                        contourX = points[0]
                        contourY = points[1]
                        currentX = contourX
                        currentY = contourY
                        hasContour = true
                    }
                    flattenCubic(
                        points[0],
                        points[1],
                        points[2],
                        points[3],
                        points[4],
                        points[5],
                        points[6],
                        points[7],
                        ::add,
                    )
                    currentX = points[6]
                    currentY = points[7]
                }
                PathSegment.Type.Close -> {
                    closeContour()
                    currentX = contourX
                    currentY = contourY
                }
                PathSegment.Type.Conic -> {
                    if (!hasContour) {
                        contourX = points[0]
                        contourY = points[1]
                        currentX = contourX
                        currentY = contourY
                        hasContour = true
                    }
                    flattenConic(
                        points[0],
                        points[1],
                        points[2],
                        points[3],
                        points[4],
                        points[5],
                        points[6],
                        ::add,
                    )
                    currentX = points[4]
                    currentY = points[5]
                }
                PathSegment.Type.Done -> break
            }
        }
        closeContour()
        segments = coordinates.toFloatArray()
    }

    private companion object {
        const val FLATNESS = 0.05f

        fun flattenQuadratic(
            x0: Float,
            y0: Float,
            cx: Float,
            cy: Float,
            x1: Float,
            y1: Float,
            add: (Float, Float, Float, Float) -> Unit,
        ) {
            val stack = ArrayList<FloatArray>()
            stack.add(floatArrayOf(x0, y0, cx, cy, x1, y1))
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.lastIndex)
                if (distanceToLine(c[2], c[3], c[0], c[1], c[4], c[5]) <= FLATNESS) {
                    add(c[0], c[1], c[4], c[5])
                } else {
                    val ax = (c[0] + c[2]) * 0.5f
                    val ay = (c[1] + c[3]) * 0.5f
                    val bx = (c[2] + c[4]) * 0.5f
                    val by = (c[3] + c[5]) * 0.5f
                    val mx = (ax + bx) * 0.5f
                    val my = (ay + by) * 0.5f
                    val left = floatArrayOf(c[0], c[1], ax, ay, mx, my)
                    val right = floatArrayOf(mx, my, bx, by, c[4], c[5])
                    val parent = floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5])
                    if (left.contentEquals(parent) || right.contentEquals(parent)) {
                        add(c[0], c[1], c[4], c[5])
                    } else {
                        stack.add(right)
                        stack.add(left)
                    }
                }
            }
        }

        fun flattenCubic(
            x0: Float,
            y0: Float,
            c1x: Float,
            c1y: Float,
            c2x: Float,
            c2y: Float,
            x1: Float,
            y1: Float,
            add: (Float, Float, Float, Float) -> Unit,
        ) {
            val stack = ArrayList<FloatArray>()
            stack.add(floatArrayOf(x0, y0, c1x, c1y, c2x, c2y, x1, y1))
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.lastIndex)
                if (
                    maxOf(
                        distanceToLine(c[2], c[3], c[0], c[1], c[6], c[7]),
                        distanceToLine(c[4], c[5], c[0], c[1], c[6], c[7]),
                    ) <= FLATNESS
                ) {
                    add(c[0], c[1], c[6], c[7])
                } else {
                    val aX = (c[0] + c[2]) * 0.5f
                    val aY = (c[1] + c[3]) * 0.5f
                    val bX = (c[2] + c[4]) * 0.5f
                    val bY = (c[3] + c[5]) * 0.5f
                    val dX = (c[4] + c[6]) * 0.5f
                    val dY = (c[5] + c[7]) * 0.5f
                    val eX = (aX + bX) * 0.5f
                    val eY = (aY + bY) * 0.5f
                    val fX = (bX + dX) * 0.5f
                    val fY = (bY + dY) * 0.5f
                    val mX = (eX + fX) * 0.5f
                    val mY = (eY + fY) * 0.5f
                    val left = floatArrayOf(c[0], c[1], aX, aY, eX, eY, mX, mY)
                    val right = floatArrayOf(mX, mY, fX, fY, dX, dY, c[6], c[7])
                    val parent = floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
                    if (left.contentEquals(parent) || right.contentEquals(parent)) {
                        add(c[0], c[1], c[6], c[7])
                    } else {
                        stack.add(right)
                        stack.add(left)
                    }
                }
            }
        }

        fun flattenConic(
            x0: Float,
            y0: Float,
            cx: Float,
            cy: Float,
            x1: Float,
            y1: Float,
            weight: Float,
            add: (Float, Float, Float, Float) -> Unit,
        ) {
            require(weight.isFinite() && weight > 0f) { "Conic weight must be finite and positive" }
            val stack = ArrayList<FloatArray>()
            stack.add(floatArrayOf(x0, y0, cx, cy, x1, y1, weight))
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.lastIndex)
                if (distanceToLine(c[2], c[3], c[0], c[1], c[4], c[5]) <= FLATNESS) {
                    add(c[0], c[1], c[4], c[5])
                } else {
                    val w = c[6]
                    val midX = ((c[0] + 2f * w * c[2] + c[4]) / (2f + 2f * w))
                    val midY = ((c[1] + 2f * w * c[3] + c[5]) / (2f + 2f * w))
                    val leftControlX = (c[0] + w * c[2]) / (1f + w)
                    val leftControlY = (c[1] + w * c[3]) / (1f + w)
                    val rightControlX = (w * c[2] + c[4]) / (1f + w)
                    val rightControlY = (w * c[3] + c[5]) / (1f + w)
                    val childWeight = sqrt(((1.0 + w.toDouble()) * 0.5)).toFloat()
                    val left =
                        floatArrayOf(
                            c[0],
                            c[1],
                            leftControlX,
                            leftControlY,
                            midX,
                            midY,
                            childWeight,
                        )
                    val right =
                        floatArrayOf(
                            midX,
                            midY,
                            rightControlX,
                            rightControlY,
                            c[4],
                            c[5],
                            childWeight,
                        )
                    if (left.contentEquals(c) || right.contentEquals(c)) {
                        add(c[0], c[1], c[4], c[5])
                    } else {
                        stack.add(right)
                        stack.add(left)
                    }
                }
            }
        }

        fun distanceToLine(
            px: Float,
            py: Float,
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
        ): Float {
            val dx = x1 - x0
            val dy = y1 - y0
            val denominator = dx * dx + dy * dy
            if (denominator == 0f) return hypot(px - x0, py - y0)
            val projection = (((px - x0) * dx + (py - y0) * dy) / denominator).coerceIn(0f, 1f)
            return hypot(px - (x0 + projection * dx), py - (y0 + projection * dy))
        }
    }
}

private fun distanceSquaredBetweenSegments(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    dx: Double,
    dy: Double,
): Double {
    val abx = bx - ax
    val aby = by - ay
    val cdx = dx - cx
    val cdy = dy - cy
    val acx = cx - ax
    val acy = cy - ay
    val adx = dx - ax
    val ady = dy - ay
    val cax = ax - cx
    val cay = ay - cy
    val cbx = bx - cx
    val cby = by - cy
    val o1 = cross(abx, aby, acx, acy)
    val o2 = cross(abx, aby, adx, ady)
    val o3 = cross(cdx, cdy, cax, cay)
    val o4 = cross(cdx, cdy, cbx, cby)
    val firstStraddles = (o1 > 0.0 && o2 < 0.0) || (o2 > 0.0 && o1 < 0.0)
    val secondStraddles = (o3 > 0.0 && o4 < 0.0) || (o4 > 0.0 && o3 < 0.0)
    if (firstStraddles && secondStraddles) return 0.0
    var minimum = pointSegmentDistanceSquared(ax, ay, cx, cy, dx, dy)
    val distance = pointSegmentDistanceSquared(bx, by, cx, cy, dx, dy)
    if (distance < minimum) minimum = distance
    val reverseDistance = pointSegmentDistanceSquared(cx, cy, ax, ay, bx, by)
    if (reverseDistance < minimum) minimum = reverseDistance
    val otherReverseDistance = pointSegmentDistanceSquared(dx, dy, ax, ay, bx, by)
    if (otherReverseDistance < minimum) minimum = otherReverseDistance
    return minimum
}

private fun pointSegmentDistanceSquared(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
): Double {
    val dx = bx - ax
    val dy = by - ay
    val denominator = dx * dx + dy * dy
    if (denominator <= 0.0) {
        val x = px - ax
        val y = py - ay
        return x * x + y * y
    }
    val amount = (((px - ax) * dx + (py - ay) * dy) / denominator).coerceIn(0.0, 1.0)
    val x = px - (ax + amount * dx)
    val y = py - (ay + amount * dy)
    return x * x + y * y
}

private fun cross(ax: Double, ay: Double, bx: Double, by: Double): Double = ax * by - ay * bx
