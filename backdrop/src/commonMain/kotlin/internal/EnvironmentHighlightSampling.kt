package top.ltfan.backdrop.internal

import kotlin.math.ceil
import top.ltfan.backdrop.effects.gaussianSupport

/** Outset covers the source blur, each outward sample ray, and the bilinear texel footprint. */
internal fun environmentHighlightSamplingOutset(
    sampleDistance: Float,
    blurRadius: Float = 0f,
): Float {
    require(sampleDistance.isFinite() && sampleDistance > 0f)
    require(blurRadius.isFinite() && blurRadius >= 0f)
    val blurSupport = if (blurRadius > 0f) gaussianSupport(blurRadius) else 0f
    return ceil(sampleDistance + blurSupport + 0.5f)
}
