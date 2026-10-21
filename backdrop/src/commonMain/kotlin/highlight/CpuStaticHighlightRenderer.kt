package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.highlightBandProfile
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry

internal fun renderStaticHighlightPixels(
    geometry: FlattenedBoundaryGeometry,
    size: Size,
    widthPx: Float,
    falloff: BackdropCurve,
    direction: HighlightDirection,
    style: HighlightStyle,
    exclusions: Set<HighlightAngularCut>,
    density: Float = 1f,
): FloatArray =
    renderStaticHighlightPixels(
        geometry.bounds,
        geometry::contains,
        { point ->
            geometry.nearestBoundary(point).let {
                HighlightBoundaryGeometry.Boundary(
                    it.distance,
                    it.outwardNormal,
                    it.position,
                    it.signedDistance,
                )
            }
        },
        size,
        widthPx,
        falloff,
        direction,
        style,
        exclusions,
        density,
    )

internal fun renderStaticHighlightPixels(
    geometry: HighlightBoundaryGeometry,
    size: Size,
    widthPx: Float,
    falloff: BackdropCurve,
    direction: HighlightDirection,
    style: HighlightStyle,
    exclusions: Set<HighlightAngularCut>,
    density: Float = 1f,
): FloatArray {
    if (!geometry.isAnalytic) {
        return renderStaticHighlightPixels(
            geometry.cpuGeometry(),
            size,
            widthPx,
            falloff,
            direction,
            style,
            exclusions,
            density,
        )
    }
    return renderStaticHighlightPixels(
        geometry.bounds,
        geometry::contains,
        geometry::nearestBoundary,
        size,
        widthPx,
        falloff,
        direction,
        style,
        exclusions,
        density,
    )
}

private fun renderStaticHighlightPixels(
    bounds: Rect,
    containsPoint: (Offset) -> Boolean,
    nearestBoundary: (Offset) -> HighlightBoundaryGeometry.Boundary,
    size: Size,
    widthPx: Float,
    falloff: BackdropCurve,
    direction: HighlightDirection,
    style: HighlightStyle,
    exclusions: Set<HighlightAngularCut>,
    density: Float,
): FloatArray {
    require(widthPx.isFinite() && widthPx >= 0f && density.isFinite() && density > 0f)
    val maximumWidthScale = direction.maximumWidthScale
    require((widthPx * maximumWidthScale).isFinite()) {
        "direction-scaled highlight width must remain finite"
    }
    val pixelWidth = ceil(size.width).toInt()
    val pixelHeight = ceil(size.height).toInt()
    val pixels = FloatArray(pixelWidth * pixelHeight * 4)
    val angular = direction as? HighlightDirection.Angular
    val angle = direction.angle.let(::normalizedAngle) * (PI / 180.0).toFloat()
    val axisX = cos(angle)
    val axisY = sin(angle)
    if (widthPx == 0f || style === HighlightStyle.None) return pixels
    val left = maxOf(0, floor(bounds.left).toInt())
    val top = maxOf(0, floor(bounds.top).toInt())
    val right = minOf(pixelWidth, ceil(bounds.right).toInt())
    val bottom = minOf(pixelHeight, ceil(bounds.bottom).toInt())
    for (y in top until bottom) for (x in left until right) {
        val point = Offset(x + .5f, y + .5f)
        if (!containsPoint(point)) continue
        val boundary = nearestBoundary(point)
        val normal = boundary.outwardNormal
        val projection = normal.x * axisX + normal.y * axisY
        val angularValues =
            when (angular) {
                null -> 0L
                else ->
                    angular.profile.evaluate(
                        atan2(normal.y, normal.x) * (180f / PI.toFloat()) -
                            normalizedAngle(angular.angle),
                    )
            }
        val magnitude = if (angular == null) directionLevel(direction, projection) else 0f
        val widthScale =
            if (angular == null) directionWidthScale(direction, magnitude)
            else Float.fromBits((angularValues shr 32).toInt())
        val bandWidth = widthPx * widthScale
        require(bandWidth.isFinite()) { "direction-scaled highlight width must remain finite" }
        if (boundary.distance >= bandWidth + .5f) continue
        val sample = HighlightSample(boundary.position, normal, projection, widthPx)
        val shaded = style.shade(sample)
        val directionalStrength =
            if (angular == null) directionStrength(direction, magnitude)
            else Float.fromBits(angularValues.toInt())
        val light = maxOf(0f, shaded.color.alpha) * maxOf(0f, shaded.strength) * directionalStrength
        val coverage = highlightBandProfile(boundary.distance, bandWidth, falloff)
        val q = light * coverage * exclusionWeight(normal, exclusions)
        if (q <= 0f) continue
        val linear = shaded.color.toHighlightLinearExtendedSrgb()
        val alpha = minOf(q, 1f)
        val scale = q / alpha
        val offset = (y * pixelWidth + x) * 4
        pixels[offset] = encodeHighlightExtendedSrgb(linear.red * scale) * alpha
        pixels[offset + 1] = encodeHighlightExtendedSrgb(linear.green * scale) * alpha
        pixels[offset + 2] = encodeHighlightExtendedSrgb(linear.blue * scale) * alpha
        pixels[offset + 3] = alpha
    }
    return pixels
}

internal fun directionLevel(direction: HighlightDirection, projection: Float): Float =
    when (direction) {
        HighlightDirection.Uniform -> 1f
        is HighlightDirection.Angular -> error("Angular profiles require a relative normal angle")
        is HighlightDirection.Incidence ->
            if (direction.mode == HighlightDirection.Mode.Single) maxOf(0f, projection)
            else abs(projection)
    }

private fun directionStrength(direction: HighlightDirection, magnitude: Float): Float =
    (direction as? HighlightDirection.Incidence)?.strength?.transform(magnitude) ?: 1f

internal fun directionWidthScale(direction: HighlightDirection, magnitude: Float): Float =
    (direction as? HighlightDirection.Incidence)?.widthScale?.transform(magnitude) ?: 1f

internal fun exclusionWeight(normal: Offset, exclusions: Set<HighlightAngularCut>): Float {
    var weight = 1f
    val radians = (PI / 180.0).toFloat()
    for ((angle, halfAngle, featherAngle) in exclusions) {
        val axis = normalizedAngle(angle) * radians
        val difference =
            abs(
                atan2(
                    cos(axis) * normal.y - sin(axis) * normal.x,
                    normal.x * cos(axis) + normal.y * sin(axis),
                ),
            )
        val start = halfAngle * radians
        val end = (halfAngle + featherAngle) * radians
        val allowed =
            when {
                difference <= start -> 0f
                featherAngle <= 0f -> 1f
                difference >= end -> 1f
                else -> smoothstep(start, end, difference)
            }
        weight = minOf(weight, allowed)
    }
    return weight
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
