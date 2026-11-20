package top.ltfan.backdrop.shape

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.capsule.continuities.G2Continuity
import com.kyant.capsule.core.Point
import com.kyant.capsule.path.PathSegment

/**
 * Bundled G2 continuous rounded rectangle. The same ordered segments define clipping and the
 * structured contour consumed by Backdrop.
 */
@ExperimentalBackdropShapeApi
@Immutable
@OptIn(ExperimentalBackdropShapeApi::class)
public class ContinuousRoundedRectangleShape
public constructor(
    public val topStart: CornerSize = ZeroCornerSize,
    public val topEnd: CornerSize = ZeroCornerSize,
    public val bottomEnd: CornerSize = ZeroCornerSize,
    public val bottomStart: CornerSize = ZeroCornerSize,
) : ContourShape {
    public constructor(corner: Float) : this(CornerSize(corner))

    public fun copy(
        topStart: CornerSize = this.topStart,
        topEnd: CornerSize = this.topEnd,
        bottomEnd: CornerSize = this.bottomEnd,
        bottomStart: CornerSize = this.bottomStart,
    ): ContinuousRoundedRectangleShape =
        ContinuousRoundedRectangleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createContour(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Contour {
        if (size.width <= 0f || size.height <= 0f) {
            return Contour(
                listOf(
                    ContourLoop(
                        listOf(ContourSegment.Line(Offset.Zero, Offset.Zero)),
                    ),
                ),
            )
        }
        val maximum = minOf(size.width, size.height) * 0.5f
        fun CornerSize.px() = toPx(size, density).coerceIn(0f, maximum)
        val topLeft = (if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd).px()
        val topRight = (if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart).px()
        val bottomRight =
            (if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart).px()
        val bottomLeft =
            (if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd).px()
        val segments =
            G2Continuity()
                .createRoundedRectanglePathSegments(
                    width = size.width.toDouble(),
                    height = size.height.toDouble(),
                    topLeft = topLeft.toDouble(),
                    topRight = topRight.toDouble(),
                    bottomRight = bottomRight.toDouble(),
                    bottomLeft = bottomLeft.toDouble(),
                )
                .flatMap(PathSegment::toContourSegments)
        return Contour(listOf(ContourLoop(segments)))
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ContinuousRoundedRectangleShape &&
                topStart == other.topStart &&
                topEnd == other.topEnd &&
                bottomEnd == other.bottomEnd &&
                bottomStart == other.bottomStart)

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    override fun toString(): String =
        "ContinuousRoundedRectangleShape(topStart=$topStart, topEnd=$topEnd, bottomEnd=$bottomEnd, bottomStart=$bottomStart)"
}

/** Creates a bundled pill using the same G2 contour contract. */
@Suppress("FunctionName")
@ExperimentalBackdropShapeApi
public fun CapsuleShape(): ContinuousRoundedRectangleShape =
    ContinuousRoundedRectangleShape(CornerSize(50))

@OptIn(ExperimentalBackdropShapeApi::class)
private fun PathSegment.toContourSegments(): List<ContourSegment> =
    when (this) {
        is PathSegment.Line -> listOf(ContourSegment.Line(from.toOffset(), to.toOffset()))
        is PathSegment.Arc ->
            listOf(
                ContourSegment.Arc(
                    center = center.toOffset(),
                    radius = radius.toFloat(),
                    startAngleDegrees = (startAngle * RadiansToDegrees).toFloat(),
                    sweepAngleDegrees = (sweepAngle * RadiansToDegrees).toFloat(),
                ),
            )
        is PathSegment.Circle ->
            List(4) { index ->
                ContourSegment.Arc(
                    center = center.toOffset(),
                    radius = radius.toFloat(),
                    startAngleDegrees = index * 90f,
                    sweepAngleDegrees = 90f,
                )
            }
        is PathSegment.Cubic ->
            listOf(
                ContourSegment.Cubic(
                    start = p0.toOffset(),
                    control1 = p1.toOffset(),
                    control2 = p2.toOffset(),
                    end = p3.toOffset(),
                ),
            )
    }

private fun Point.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

private const val RadiansToDegrees = 180.0 / kotlin.math.PI
