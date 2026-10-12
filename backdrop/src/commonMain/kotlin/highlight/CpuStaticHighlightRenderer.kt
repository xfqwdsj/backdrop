package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.highlightBandProfile
import top.ltfan.backdrop.internal.GenericEnvironmentGeometry

/**
 * Premultiplied RGBA pixels for the static fallback, with exclusions applied after Gaussian blur.
 */
internal fun renderStaticHighlightPixels(
    geometry: GenericEnvironmentGeometry,
    size: Size,
    width: Float,
    blurRadius: Float,
    style: HighlightStyle,
    direction: HighlightDirection,
    depthCurve: BackdropCurve,
    exclusions: Set<HighlightExclusion>,
): FloatArray {
    val pixelWidth = ceil(size.width).toInt()
    val pixelHeight = ceil(size.height).toInt()
    require(pixelWidth >= 0 && pixelHeight >= 0 && blurRadius.isFinite() && blurRadius >= 0f)
    val pixels = FloatArray(pixelWidth * pixelHeight * 4)
    val candidates = BooleanArray(pixelWidth * pixelHeight)
    val margin = width + blurRadius * 3f + 0.5f
    val marginSquared = margin * margin
    val segments = geometry.segments
    for (index in segments.indices step 4) {
        val x0 = segments[index]
        val y0 = segments[index + 1]
        val x1 = segments[index + 2]
        val y1 = segments[index + 3]
        val left = maxOf(0, floor(minOf(x0, x1) - margin).toInt())
        val top = maxOf(0, floor(minOf(y0, y1) - margin).toInt())
        val right = minOf(pixelWidth, ceil(maxOf(x0, x1) + margin).toInt())
        val bottom = minOf(pixelHeight, ceil(maxOf(y0, y1) + margin).toInt())
        val edgeX = x1 - x0
        val edgeY = y1 - y0
        val edgeLengthSquared = edgeX * edgeX + edgeY * edgeY
        if (edgeLengthSquared <= 0f) continue
        for (y in top until bottom) for (x in left until right) {
            val px = x + 0.5f - x0
            val py = y + 0.5f - y0
            val amount = ((px * edgeX + py * edgeY) / edgeLengthSquared).coerceIn(0f, 1f)
            val dx = x0 + amount * edgeX - (x + 0.5f)
            val dy = y0 + amount * edgeY - (y + 0.5f)
            if (dx * dx + dy * dy <= marginSquared) candidates[y * pixelWidth + x] = true
        }
    }
    val angle = normalizedAngle(direction.angle) * (PI / 180.0).toFloat()
    val directionX = cos(angle)
    val directionY = sin(angle)
    val bounds = geometry.bounds
    for (y in maxOf(0, bounds.top.toInt()) until minOf(pixelHeight, ceil(bounds.bottom).toInt())) {
        for (x in
            maxOf(0, bounds.left.toInt()) until minOf(pixelWidth, ceil(bounds.right).toInt())) {
            if (!candidates[y * pixelWidth + x]) continue
            val point = Offset(x + 0.5f, y + 0.5f)
            if (!geometry.contains(point)) continue
            val boundary = geometry.nearestBoundary(point)
            val projection =
                boundary.outwardNormal.x * directionX + boundary.outwardNormal.y * directionY
            val magnitude = abs(projection)
            val widthScale =
                if (style.directional) direction.widthCurve.transform(magnitude) else 1f
            val strength =
                if (style.directional) direction.strengthCurve.transform(magnitude) else 1f
            val profile =
                highlightBandProfile(boundary.distance, width * widthScale, depthCurve) * strength
            if (profile <= 0f) continue
            val sample =
                HighlightSample(
                    point,
                    boundary.outwardNormal,
                    projection,
                    boundary.distance,
                    width * widthScale,
                )
            val shaded = style.shade(sample).convert(ColorSpaces.ExtendedSrgb)
            val alpha = shaded.alpha * profile
            val offset = (y * pixelWidth + x) * 4
            pixels[offset] = shaded.red * alpha
            pixels[offset + 1] = shaded.green * alpha
            pixels[offset + 2] = shaded.blue * alpha
            pixels[offset + 3] = alpha
        }
    }
    if (blurRadius > 0f) gaussianBlur(pixels, pixelWidth, pixelHeight, blurRadius)
    if (exclusions.isNotEmpty()) {
        val directions = exclusions.map { it.asDirection() }
        for (y in 0 until pixelHeight) {
            for (x in 0 until pixelWidth) {
                val offset = (y * pixelWidth + x) * 4
                if (pixels[offset + 3] <= 0f) continue
                val point = Offset(x + 0.5f, y + 0.5f)
                if (!geometry.contains(point)) continue
                val weight =
                    exclusionWeight(geometry.nearestBoundary(point).outwardNormal, directions)
                for (channel in 0..3) pixels[offset + channel] *= weight
            }
        }
    }
    return pixels
}

private fun exclusionWeight(normal: Offset, exclusions: List<HighlightExclusion.Direction>): Float {
    val radians = (PI / 180.0).toFloat()
    var weight = 1f
    for ((exclusionAngle, halfAngle, featherAngle) in exclusions) {
        val angle = normalizedAngle(exclusionAngle) * radians
        val difference = acos((normal.x * cos(angle) + normal.y * sin(angle)).coerceIn(-1f, 1f))
        val start = halfAngle * radians
        val end = (halfAngle + featherAngle) * radians
        val allowed =
            when {
                difference <= start -> 0f
                featherAngle <= 0f -> 1f
                difference >= end -> 1f
                else -> {
                    val t = ((difference - start) / (end - start)).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
            }
        weight = minOf(weight, allowed)
    }
    return weight
}

private fun gaussianBlur(pixels: FloatArray, width: Int, height: Int, sigma: Float) {
    val radius = ceil(sigma * 3f).toInt().coerceAtLeast(1)
    val kernel = FloatArray(radius * 2 + 1)
    var total = 0f
    for (step in -radius..radius) {
        val weight = exp(-(step * step) / (2f * sigma * sigma))
        kernel[step + radius] = weight
        total += weight
    }
    for (index in kernel.indices) kernel[index] /= total
    val intermediate = FloatArray(pixels.size)
    for (y in 0 until height) for (x in 0 until width) for (channel in 0..3) {
        var sum = 0f
        for (step in -radius..radius) {
            val sampleX = x + step
            if (sampleX in 0 until width)
                sum += pixels[(y * width + sampleX) * 4 + channel] * kernel[step + radius]
        }
        intermediate[(y * width + x) * 4 + channel] = sum
    }
    for (y in 0 until height) for (x in 0 until width) for (channel in 0..3) {
        var sum = 0f
        for (step in -radius..radius) {
            val sampleY = y + step
            if (sampleY in 0 until height)
                sum += intermediate[(sampleY * width + x) * 4 + channel] * kernel[step + radius]
        }
        pixels[(y * width + x) * 4 + channel] = sum
    }
}
