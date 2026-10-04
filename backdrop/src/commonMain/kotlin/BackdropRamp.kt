package top.ltfan.backdrop

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** Iterations both this file and the progressive blur shader run to invert the curve. */
internal const val BezierSolveIterations = 16

/** One axis of a cubic bezier from 0 to 1 through [first] and [second]. */
private fun coordinate(t: Float, first: Float, second: Float): Float {
    val inverse = 1f - t
    return 3f * inverse * inverse * t * first + 3f * inverse * t * t * second + t * t * t
}

/**
 * A cubic bezier easing, held as its four control values rather than as an [Easing] so the
 * progressive blur shader evaluates the very curve the Kotlin side samples. Parameterized the way
 * [androidx.compose.animation.core.CubicBezierEasing] is: the curve runs from (0,0) to (1,1)
 * through (x1,y1) and (x2,y2).
 */
@Immutable
public data class BackdropBezier(
    public val x1: Float,
    public val y1: Float,
    public val x2: Float,
    public val y2: Float,
) {
    /**
     * The curve's output for [input], solved the way the shader solves it, so a mask painted from
     * this value and a blur evaluated in a shader stay on one curve.
     */
    public fun transform(input: Float): Float {
        val x = input.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        var t = x
        repeat(BezierSolveIterations) {
            t = (low + high) * 0.5f
            if (coordinate(t, x1, x2) < x) low = t else high = t
        }
        return coordinate(t, y1, y2)
    }

    /** This curve as a Compose [Easing], for interop with the animation APIs. */
    public fun asEasing(): Easing = Easing { transform(it) }

    public companion object {
        /** The curve the scroll edge of this library ramps with. */
        public val Default: BackdropBezier = BackdropBezier(0.35f, 0f, 0.6f, 1f)
    }
}

/**
 * The geometry of one axis of a surface, in layout pixels. Both ranges start where the surface's
 * content side is — the edge the scrolled content passes — and grow towards the surface's own far
 * edge, which is the direction that content travels.
 *
 * [occupied] is the surface itself, `0f..thickness`. [content] is the part of it that carries
 * content, `0f..(thickness - insets)`, so the window insets a surface excludes sit at the far end
 * of both. A ramp that reaches below `0f` therefore asks for room on the content side, beyond the
 * surface, which is the room its effects cover past the surface.
 *
 * A [Density], so a ramp can express its ends in [Dp] while the geometry stays in pixels.
 */
@Immutable
public class BackdropAxis(
    /**
     * The edge whose axis this is: which way the surface faces and which way its content travels.
     */
    public val edge: BackdropSide,
    public val occupied: ClosedFloatingPointRange<Float>,
    public val content: ClosedFloatingPointRange<Float>,
    override val density: Float,
    override val fontScale: Float,
) : Density {
    init {
        require(occupied.start <= occupied.endInclusive) {
            "An axis runs from its start to its end, but was $occupied"
        }
        require(content.start >= occupied.start && content.endInclusive <= occupied.endInclusive) {
            "A content range sits inside the surface that carries it, but was $content in $occupied"
        }
    }

    /** The surface's own span along this axis, in pixels. */
    public val thickness: Float
        get() = occupied.endInclusive - occupied.start

    /** The span of this axis that carries content, in pixels. */
    public val contentThickness: Float
        get() = content.endInclusive - content.start

    /** Where [coordinate] sits on this axis, as a share of [occupied] from its far end. */
    public fun shareFromFarEnd(coordinate: Float): Float =
        (occupied.endInclusive - coordinate) / thickness

    override fun toString(): String =
        "BackdropAxis(edge=$edge, occupied=$occupied, content=$content, density=$density)"
}

/**
 * Where a ramp begins and ends on an axis: the coordinates it runs between. [BackdropRamp.from]
 * holds at the smaller of the two and [BackdropRamp.to] at the larger, so a longer coordinate
 * always means a stronger ramp and no caller has to flip anything per edge.
 *
 * The returned span may reach past [BackdropAxis.occupied]: what it asks for beyond the surface is
 * the room the surface's effects cover past itself.
 */
public fun interface BackdropRampAnchors {
    public operator fun invoke(axis: BackdropAxis): ClosedFloatingPointRange<Float>

    public companion object {
        /** The whole surface: a ramp that fills what it is drawn into. */
        public val Auto: BackdropRampAnchors = SpanAnchors { axis -> axis.occupied }

        /**
         * Full strength from the content side inwards for [plateau], spent over [fade] beyond it.
         * The [fade] is what the ramp asks for past the surface, so it is also how far the effects
         * cover.
         */
        public fun offsets(plateau: Dp, fade: Dp): BackdropRampAnchors = SpanAnchors { axis ->
            val plateauPx = with(axis) { plateau.toPx() }
            val fadePx = with(axis) { fade.toPx() }
            (-fadePx)..plateauPx
        }

        /**
         * The same shape with both parts taken as shares of the content a surface carries, so the
         * ramp reads the same at any height, font scale or inset.
         */
        public fun ratio(plateau: Float, fade: Float): BackdropRampAnchors = SpanAnchors { axis ->
            val content = axis.contentThickness
            (-fade * content)..(plateau * content)
        }
    }
}

/** Anchors built from a lambda, equal to another of the same shape so caches still hold. */
private class SpanAnchors(private val span: (BackdropAxis) -> ClosedFloatingPointRange<Float>) :
    BackdropRampAnchors {
    override fun invoke(axis: BackdropAxis): ClosedFloatingPointRange<Float> = span(axis)

    override fun equals(other: Any?): Boolean = other is SpanAnchors && other.span == span

    override fun hashCode(): Int = span.hashCode()
}

/**
 * A progressive ramp: [from] where its span starts, reaching [to] where the span ends through
 * [curve].
 *
 * A surface paints this ramp as [brush], which samples the curve [stops] times because a gradient
 * brush is a list of colors; the progressive blur evaluates [curve] itself, per pixel. [stops]
 * defaults to [DEFAULT_STOPS], where one sample covers less alpha than a display can show, so a
 * painted ramp reads as continuous; lower it only to shorten that color list, since it costs
 * nothing per pixel. The intensities are the ramp's own share of a surface's alpha, so a surface
 * that paints a translucent mask scales them rather than replacing them.
 */
@Immutable
public data class BackdropRamp(
    /** Where this ramp runs on an axis. */
    public val anchors: BackdropRampAnchors = BackdropRampAnchors.Auto,
    /** The intensity at the ramp's smaller coordinate. */
    public val from: Float = 0f,
    /** The intensity at the ramp's larger coordinate. */
    public val to: Float = 1f,
    public val curve: BackdropBezier = BackdropBezier.Default,
    /** Samples of the curve [brush] paints, between [MIN_STOPS] and [MAX_STOPS]. */
    public val stops: Int = DEFAULT_STOPS,
) {
    init {
        require(stops in MIN_STOPS..MAX_STOPS) {
            "The ramp needs $MIN_STOPS to $MAX_STOPS stops, but had $stops"
        }
    }

    /** The span this ramp runs between on [axis], in that axis's coordinates. */
    public fun span(axis: BackdropAxis): ClosedFloatingPointRange<Float> = anchors(axis)

    /** The intensity at [position] along the ramp: [from] at zero, [to] at one, through [curve]. */
    public fun intensityAt(position: Float): Float =
        from + (to - from) * curve.transform(position.coerceIn(0f, 1f))

    /**
     * The [stops] intensities this ramp samples from its start to its end, which [brush] paints.
     */
    public fun intensities(): FloatArray = FloatArray(stops) { intensityAt(it / (stops - 1f)) }

    /**
     * This ramp painted in [color] from [start] to [end], which a caller places on its own axis.
     * The alpha of the result follows the ramp, so the color's own alpha scales every sample. This
     * allocates a color list of [stops] entries: remember it rather than calling it on every frame.
     */
    public fun brush(color: Color, start: Offset, end: Offset): Brush {
        val intensities = intensities()
        return Brush.linearGradient(
            colors = List(stops) { index -> color.copy(alpha = intensities[index] * color.alpha) },
            start = start,
            end = end,
        )
    }

    public companion object {

        /**
         * Samples a ramp paints with unless it asks for another count: one sample per step of an
         * 8-bit alpha channel, which puts the sampling below what a display can show.
         */
        public const val DEFAULT_STOPS: Int = 256

        /** Fewest samples that still describe a ramp. */
        public const val MIN_STOPS: Int = 2

        /** Most samples a ramp paints with, so one ramp stays one short list of colors. */
        public const val MAX_STOPS: Int = 256
    }
}

/** Places the ramp's anchors in the drawing layer's pixel coordinates. */
internal fun Density.backdropRampEnds(
    ramp: BackdropRamp,
    edge: BackdropEdge,
    surfaceSize: Size,
    layoutDirection: LayoutDirection,
    origin: Offset,
): Pair<Offset, Offset> {
    val side = edge.side(layoutDirection)
    val thickness = if (side.isVertical) surfaceSize.height else surfaceSize.width
    val axis = BackdropAxis(side, 0f..thickness, 0f..thickness, density, fontScale)
    val span = ramp.span(axis)
    fun at(coordinate: Float): Offset {
        val along = if (side.carriesContent) thickness - coordinate else coordinate
        return origin + if (side.isVertical) Offset(0f, along) else Offset(along, 0f)
    }
    return at(span.start) to at(span.endInclusive)
}
