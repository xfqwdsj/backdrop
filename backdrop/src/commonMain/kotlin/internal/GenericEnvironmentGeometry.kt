package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PathSegment
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A line approximation of a filled path boundary. Curves are subdivided until their control points
 * are within 0.05 px of the finite chord. Keep this value in pixel units: these segments are
 * consumed in the same local pixel coordinates as the environment shader.
 */
internal class GenericEnvironmentGeometry(path: Path) {
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
                    val childWeight = kotlin.math.sqrt(((1.0 + w.toDouble()) * 0.5)).toFloat()
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
