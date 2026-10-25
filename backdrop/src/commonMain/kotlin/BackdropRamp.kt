package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Surface-axis geometry in pixels, increasing from the content side toward the far edge. [occupied]
 * describes the surface span and [content] the portion available to its content. Coordinates
 * outside [occupied] describe coverage beyond the surface. [Density] supports anchors expressed in
 * layout units.
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
 * Resolves the coordinate span over which a ramp interpolates from [BackdropRamp.from] to
 * [BackdropRamp.to]. The span may extend beyond [BackdropAxis.occupied] to request effect coverage.
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

/** Anchor equality follows the supplied span function. */
private class SpanAnchors(private val span: (BackdropAxis) -> ClosedFloatingPointRange<Float>) :
    BackdropRampAnchors {
    override fun invoke(axis: BackdropAxis): ClosedFloatingPointRange<Float> = span(axis)

    override fun equals(other: Any?): Boolean = other is SpanAnchors && other.span == span

    override fun hashCode(): Int = span.hashCode()
}

/**
 * A spatial intensity transition defined by anchors, endpoints, and a response curve. Consumers
 * share its geometry to align their transitions. Painted brushes sample the curve; shader consumers
 * can evaluate it directly. Ramp intensity multiplies the fill's own opacity.
 */
@Immutable
public data class BackdropRamp(
    /** Where this ramp runs on an axis. */
    public val anchors: BackdropRampAnchors = BackdropRampAnchors.Auto,
    /** The intensity at the ramp's smaller coordinate. */
    public val from: Float = 0f,
    /** The intensity at the ramp's larger coordinate. */
    public val to: Float = 1f,
    public val curve: BackdropCurve = BackdropCurve.Default,
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
     * allocates a color list of [stops] entries; remembering the brush reuses that list across
     * frames.
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

        /** Default number of curve samples used to construct a gradient brush. */
        public const val DEFAULT_STOPS: Int = 256

        /** Minimum number of samples for a ramp. */
        public const val MIN_STOPS: Int = 2

        /** Maximum supported gradient sample count. */
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
