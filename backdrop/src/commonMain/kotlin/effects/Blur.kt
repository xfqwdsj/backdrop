package top.ltfan.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.TileMode
import kotlin.math.ceil
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.BackdropEffectScopeImpl
import top.ltfan.backdrop.isRenderEffectSupported

public fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp,
) {
    require(radius.isFinite() && radius >= 0f) { "Blur radius must be finite and non-negative" }
    if (!isRenderEffectSupported()) return
    if (radius == 0f) return

    val native = (this as BackdropEffectScopeImpl).obtainNativeBlur(radius, edgeTreatment)
    val effect = native.effect
    addEffect(native.sampling) { effect }
}

private const val MaxBlurSigma = 532f

/** Conservative finite support used by Skia's Gaussian blur kernel. */
internal fun gaussianSupport(radius: Float): Float =
    ceil(3f * minOf(0.57735f * radius + 0.5f, MaxBlurSigma))
