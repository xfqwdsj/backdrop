package top.ltfan.backdrop.shape

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Opt-in marker for structured contour geometry. */
@Suppress("ExperimentalAnnotationRetention")
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Structured contour shapes may change while their optimized contract is validated.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class ExperimentalBackdropShapeApi

/** One straight, circular-arc, or cubic segment in a contour. */
@ExperimentalBackdropShapeApi
@Immutable
public sealed interface ContourSegment {
    public val start: Offset
    public val end: Offset

    @Immutable
    public data class Line(override val start: Offset, override val end: Offset) : ContourSegment

    @Immutable
    public data class Arc(
        public val center: Offset,
        public val radius: Float,
        public val startAngleDegrees: Float,
        public val sweepAngleDegrees: Float,
    ) : ContourSegment {
        init {
            require(
                center.x.isFinite() &&
                    center.y.isFinite() &&
                    radius.isFinite() &&
                    radius >= 0f &&
                    startAngleDegrees.isFinite() &&
                    sweepAngleDegrees.isFinite(),
            ) {
                "Arc geometry must be finite with a non-negative radius"
            }
        }

        override val start: Offset
            get() {
                val radians = startAngleDegrees * DegreesToRadians
                return center + Offset(cos(radians) * radius, sin(radians) * radius)
            }

        override val end: Offset
            get() {
                val radians = (startAngleDegrees + sweepAngleDegrees) * DegreesToRadians
                return center + Offset(cos(radians) * radius, sin(radians) * radius)
            }
    }

    @Immutable
    public data class Cubic(
        override val start: Offset,
        public val control1: Offset,
        public val control2: Offset,
        override val end: Offset,
    ) : ContourSegment
}

/** One ordered closed contour. [segments] may contain lines, arcs, and cubic Beziers. */
@ExperimentalBackdropShapeApi
@Immutable
public class ContourLoop(segments: List<ContourSegment>) {
    public val segments: List<ContourSegment> = segments.toList()

    init {
        require(this.segments.isNotEmpty()) { "A contour loop must contain at least one segment" }
        for (index in 1 until this.segments.size) {
            require(this.segments[index].start.isNear(this.segments[index - 1].end)) {
                "Contour segments must form one ordered closed path"
            }
        }
        require(this.segments.last().end.isNear(this.segments.first().start)) {
            "Contour segments must form one ordered closed path"
        }
    }

    public fun copy(segments: List<ContourSegment> = this.segments): ContourLoop =
        ContourLoop(segments)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ContourLoop && segments == other.segments)

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "ContourLoop(segments=$segments)"
}

/** Ordered closed loops supplied by a [ContourShape]. */
@ExperimentalBackdropShapeApi
@Immutable
public class Contour(loops: List<ContourLoop>) {
    public val loops: List<ContourLoop> = loops.toList()

    init {
        require(this.loops.isNotEmpty()) { "A contour must contain at least one closed loop" }
    }

    public fun copy(loops: List<ContourLoop> = this.loops): Contour = Contour(loops)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Contour && loops == other.loops)

    override fun hashCode(): Int = loops.hashCode()

    override fun toString(): String = "Contour(loops=$loops)"
}

/**
 * A Compose [Shape] whose outline and optimizer input come from the same structured contours. The
 * default outline uses non-zero fill for every supplied loop.
 */
@ExperimentalBackdropShapeApi
public interface ContourShape : Shape {
    public fun createContour(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Contour

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(createContour(size, layoutDirection, density).toPath())
}

@ExperimentalBackdropShapeApi
internal fun Contour.toPath(): Path =
    Path().apply {
        for (loop in loops) {
            val first = loop.segments.first().start
            moveTo(first.x, first.y)
            for (segment in loop.segments) {
                when (segment) {
                    is ContourSegment.Line -> lineTo(segment.end.x, segment.end.y)
                    is ContourSegment.Arc -> {
                        val bounds =
                            Rect(
                                segment.center.x - segment.radius,
                                segment.center.y - segment.radius,
                                segment.center.x + segment.radius,
                                segment.center.y + segment.radius,
                            )
                        arcTo(
                            rect = bounds,
                            startAngleDegrees = segment.startAngleDegrees,
                            sweepAngleDegrees = segment.sweepAngleDegrees,
                            forceMoveTo = false,
                        )
                    }
                    is ContourSegment.Cubic ->
                        cubicTo(
                            segment.control1.x,
                            segment.control1.y,
                            segment.control2.x,
                            segment.control2.y,
                            segment.end.x,
                            segment.end.y,
                        )
                }
            }
            close()
        }
    }

private fun Offset.isNear(other: Offset): Boolean =
    abs(x - other.x) <= 0.001f && abs(y - other.y) <= 0.001f

private const val DegreesToRadians = (PI / 180.0).toFloat()
