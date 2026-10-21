package top.ltfan.backdrop.effects

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import top.ltfan.backdrop.BackdropEdge
import top.ltfan.backdrop.BackdropImageScope
import top.ltfan.backdrop.BackdropRamp
import top.ltfan.backdrop.isRenderEffectSupported

/**
 * Approximates spatial blur by mixing adjacent uniformly blurred versions of the current image.
 * [levels] includes the unchanged, zero-radius input and must be at least two. Each other level
 * independently blurs that same input at a radius evenly spaced between zero and [radius], in
 * surface pixels. Levels with zero weight throughout the ramp are omitted.
 *
 * [ramp] shares its anchors, curve and coverage with [progressiveBlur]. Its [BackdropRamp.stops]
 * controls the initial curve sampling, independently of the number of blur levels. Level crossings
 * and plateau compaction determine the final gradient stop count. Adjacent levels use complementary
 * premultiplied weights, subject to color and render-target quantization. Blur can redistribute
 * source alpha; a separate alpha mask controls the resulting image's fade.
 *
 * Radius must be finite and non-negative, and ramp endpoints must be finite and in `0..1`. Anchors
 * must resolve to a finite increasing span. Unsupported render effects and zero radius preserve the
 * current image and request no coverage; radius, endpoint and level-count validation still applies.
 * Algorithm selection belongs to the caller.
 */
public fun BackdropImageScope.layeredProgressiveBlur(
    radius: Float,
    edge: BackdropEdge,
    ramp: BackdropRamp = BackdropRamp(),
    levels: Int = 8,
) {
    layeredProgressiveBlurImpl(radius, edge, ramp, levels, isRenderEffectSupported())
}

internal fun BackdropImageScope.layeredProgressiveBlurImpl(
    radius: Float,
    edge: BackdropEdge,
    ramp: BackdropRamp,
    levels: Int,
    supported: Boolean,
) {
    validateProgressiveBlur(radius, ramp)
    require(levels >= 2) { "Layered progressive blur needs at least two levels" }
    if (radius == 0f || !supported) return
    val geometry = density.progressiveBlurGeometry(size, layoutDirection, edge, ramp) ?: return
    val original = current
    val stops = layeredBlurStops(ramp, levels)
    val scale = levels - 1
    val firstLevel = floor(minOf(ramp.from, ramp.to) * scale).toInt()
    val lastLevel = ceil(maxOf(ramp.from, ramp.to) * scale).toInt()
    group {
        for (level in firstLevel..lastLevel) {
            val colors = layeredBlurColors(stops, level, levels)
            val brush =
                Brush.linearGradient(
                    *colors.toTypedArray(),
                    start = geometry.start,
                    end = geometry.end,
                )
            val blurred = image {
                replace(original)
                effects {
                    cover(geometry.coverage)
                    if (level != 0) blur(radius * (level.toFloat() / (levels - 1)))
                }
                val coverage = image {
                    paint { room ->
                        drawRect(
                            brush,
                            room.extension.originInNode,
                            Size(
                                room.size.width + room.extension.left + room.extension.right,
                                room.size.height + room.extension.top + room.extension.bottom,
                            ),
                        )
                    }
                }
                mask(coverage)
            }
            draw(blurred, blendMode = BlendMode.Plus)
        }
    }
}

internal fun layeredBlurWeight(intensity: Float, level: Int, levels: Int): Float =
    (1f - abs(intensity * (levels - 1) - level)).coerceIn(0f, 1f)

/** Inserts level crossings into the sampled ramp so interpolation only mixes adjacent levels. */
internal fun layeredBlurStops(ramp: BackdropRamp, levels: Int): List<Pair<Float, Float>> {
    val intensities = ramp.intensities()
    return buildList {
        for (index in 0 until intensities.lastIndex) {
            val from = intensities[index]
            val to = intensities[index + 1]
            if (index == 0 || from != to || from != intensities[index - 1]) {
                add(index.toFloat() / intensities.lastIndex to from)
            }
            if (from == to) continue
            val scale = levels - 1
            val first = floor(minOf(from, to) * scale).toInt() + 1
            val last = ceil(maxOf(from, to) * scale).toInt() - 1
            val crossings = if (from < to) first..last else last downTo first
            for (level in crossings) {
                val intensity = level.toFloat() / scale
                val fraction = (intensity - from) / (to - from)
                if (fraction > 0f && fraction < 1f) {
                    add((index + fraction) / intensities.lastIndex to intensity)
                }
            }
        }
        add(1f to intensities.last())
    }
}

/** Retains constant-weight interval endpoints and samples where the weight changes. */
internal fun layeredBlurColors(
    stops: List<Pair<Float, Float>>,
    level: Int,
    levels: Int,
): List<Pair<Float, Color>> = buildList {
    var previous = Float.NaN
    for (index in stops.indices) {
        val weight = layeredBlurWeight(stops[index].second, level, levels)
        val next =
            if (index < stops.lastIndex) layeredBlurWeight(stops[index + 1].second, level, levels)
            else Float.NaN
        if (index == 0 || index == stops.lastIndex || weight != previous || weight != next) {
            add(stops[index].first to Color.White.copy(alpha = weight))
        }
        previous = weight
    }
}
