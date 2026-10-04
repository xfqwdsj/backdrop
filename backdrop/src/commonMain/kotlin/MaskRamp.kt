package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.ceil
import top.ltfan.backdrop.internal.clipToExtendedShape

/** Where a mask reaches past the surface, on the layer's own sides and in its own space. */
public class BackdropMaskRoom(
    public val insets: BackdropInsets,
    /** The room covered on the layer's leading side, which is where its coordinates start. */
    public val leading: Float,
    public val domain: Size,
)

/** The room [ramp] reaches past a [surfaceSize] surface, which is where its mask is drawn. */
public fun DrawScope.backdropMaskRoom(
    ramp: BackdropRamp,
    edge: BackdropEdge,
    surfaceSize: Size,
): BackdropMaskRoom {
    val side = edge.side(layoutDirection)
    val vertical = side.isVertical
    val thickness = if (vertical) surfaceSize.height else surfaceSize.width
    val axis =
        BackdropAxis(
            edge = side,
            occupied = 0f..thickness,
            content = 0f..thickness,
            density = density,
            fontScale = fontScale,
        )
    val span = ramp.span(axis)
    val towardsContent = side.carriesContent
    val pastContentSide = maxOf(0f, -span.start)
    val pastFarSide = maxOf(0f, span.endInclusive - thickness)
    val insets =
        when (side) {
            BackdropSide.Top ->
                BackdropInsets(
                    top = pastFarSide.toDp(),
                    bottom = pastContentSide.toDp(),
                )
            BackdropSide.Bottom ->
                BackdropInsets(
                    top = pastContentSide.toDp(),
                    bottom = pastFarSide.toDp(),
                )
            BackdropSide.Left ->
                BackdropInsets(
                    left = pastFarSide.toDp(),
                    right = pastContentSide.toDp(),
                )
            BackdropSide.Right ->
                BackdropInsets(
                    left = pastContentSide.toDp(),
                    right = pastFarSide.toDp(),
                )
        }
    val leading = if (towardsContent) pastFarSide else pastContentSide
    return BackdropMaskRoom(
        insets = insets,
        leading = leading,
        domain =
            Size(
                surfaceSize.width + insets.left.toPx() + insets.right.toPx(),
                surfaceSize.height + insets.top.toPx() + insets.bottom.toPx(),
            ),
    )
}

/** Paints a solid-color mask, multiplying its opacity by [alpha] and the optional ramp. */
public fun DrawScope.drawBackdropMask(
    ramped: Boolean,
    color: Color,
    ramp: BackdropRamp,
    domain: Size,
    cache: BackdropMaskRampCache,
    edge: BackdropEdge,
    surfaceSize: Size,
    leadingInset: Float,
    alpha: Float = 1f,
): Unit =
    drawBackdropMask(
        ramped,
        SolidColor(color),
        ramp,
        domain,
        cache,
        edge,
        surfaceSize,
        leadingInset,
        alpha,
    )

/** Paints [brush], multiplying its own opacity by [alpha] and the optional ramp. */
public fun DrawScope.drawBackdropMask(
    ramped: Boolean,
    brush: Brush,
    ramp: BackdropRamp,
    domain: Size,
    cache: BackdropMaskRampCache,
    edge: BackdropEdge,
    surfaceSize: Size,
    leadingInset: Float,
    alpha: Float = 1f,
) {
    if (!ramped) {
        drawRect(brush, size = domain, alpha = alpha)
        return
    }
    val area = Size(ceil(domain.width), ceil(domain.height))
    val (start, end) = maskRampEnds(ramp, edge, surfaceSize, leadingInset)
    val rampBrush = cache.brush(ramp, start, end, Color.White)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, area), Paint())
        try {
            drawRect(brush, size = area, alpha = alpha)
            drawRect(rampBrush, size = area, blendMode = BlendMode.DstIn)
        } finally {
            canvas.restore()
        }
    }
}

/**
 * Keeps the mask's ramp brush while its ramp, color and domain hold: the brush is a color list of
 * the ramp's sample count, so rebuilding it every frame would allocate on every frame.
 */
public class BackdropMaskRampCache {
    private var ramp: BackdropRamp? = null
    private var start: Offset = Offset.Zero
    private var end: Offset = Offset.Zero
    private var color: Color = Color.Unspecified
    private var brush: Brush? = null

    public fun brush(ramp: BackdropRamp, start: Offset, end: Offset, color: Color): Brush {
        val cached = brush
        if (
            cached != null &&
                this.ramp == ramp &&
                this.start == start &&
                this.end == end &&
                this.color == color
        )
            return cached
        return ramp.brush(color, start, end).also {
            this.ramp = ramp
            this.start = start
            this.end = end
            this.color = color
            brush = it
        }
    }
}

/**
 * Where a ramp's span lands in the space a mask is drawn in, which starts at the area the caller
 * asked for. The axis a ramp measures on is 0 on the surface's content side — the edge the scrolled
 * content passes — and grows towards the surface's own far edge.
 */
private fun DrawScope.maskRampEnds(
    ramp: BackdropRamp,
    edge: BackdropEdge,
    surfaceSize: Size,
    leadingInset: Float,
): Pair<Offset, Offset> {
    val side = edge.side(layoutDirection)
    val origin = if (side.isVertical) Offset(0f, leadingInset) else Offset(leadingInset, 0f)
    return backdropRampEnds(ramp, edge, surfaceSize, layoutDirection, origin)
}

/**
 * Clips to the extended surface outline. Rounded corners retain their radius; custom shapes resolve
 * their outline against the extended size. The layout and content size stay unchanged.
 */
public fun DrawScope.clipToGrownShape(
    shape: Shape,
    insets: BackdropInsets,
    block: DrawScope.() -> Unit,
): Unit =
    clipToExtendedShape(
        shape,
        size,
        BackdropExtension(
            insets.left.toPx(),
            insets.top.toPx(),
            insets.right.toPx(),
            insets.bottom.toPx(),
        ),
        null,
        block,
    )
