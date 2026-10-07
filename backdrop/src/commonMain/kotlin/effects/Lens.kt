package top.ltfan.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.internal.RoundedRectRefractionShaderString
import top.ltfan.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import top.ltfan.backdrop.internal.lensSampling
import top.ltfan.backdrop.isRuntimeShaderSupported

public fun BackdropEffectScope.lens(
    @FloatRange(from = 0.0) refractionHeight: Float,
    @FloatRange(from = 0.0) refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false,
) {
    require(refractionHeight.isFinite() && refractionHeight >= 0f) {
        "refractionHeight must be finite and non-negative."
    }
    require(refractionAmount.isFinite() && refractionAmount >= 0f) {
        "refractionAmount must be finite and non-negative."
    }
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    val scope = this
    val effectSize = size
    val cornerRadii = cornerRadii ?: throwUnsupportedSDFException()
    val shaderString =
        if (!chromaticAberration) RoundedRectRefractionShaderString
        else RoundedRectRefractionWithDispersionShaderString
    runtimeShaderEffect(
        key = if (!chromaticAberration) "LensRefraction" else "LensRefractionWithDispersion",
        shaderString = shaderString,
        uniformShaderName = "content",
        sampling =
            lensSampling(
                effectSize,
                cornerRadii,
                refractionHeight,
                refractionAmount,
                chromaticAberration,
            ),
    ) {
        setFloatUniform("size", effectSize.width, effectSize.height)
        setFloatUniform("offset", -scope.extension.left, -scope.extension.top)
        setFloatUniform("cornerRadii", cornerRadii)
        setFloatUniform("refractionHeight", refractionHeight)
        setFloatUniform("refractionAmount", -refractionAmount)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (chromaticAberration) {
            setFloatUniform("chromaticAberration", 1f)
        }
    }
}

private val BackdropEffectScope.cornerRadii: FloatArray?
    get() =
        when (val shape = shape) {
            is AbsoluteRoundedCornerShape -> {
                val size = size
                val maxRadius = size.minDimension / 2f
                val topLeft = normalizedRadius(shape.topStart.toPx(size, this), maxRadius)
                val topRight = normalizedRadius(shape.topEnd.toPx(size, this), maxRadius)
                val bottomRight = normalizedRadius(shape.bottomEnd.toPx(size, this), maxRadius)
                val bottomLeft = normalizedRadius(shape.bottomStart.toPx(size, this), maxRadius)
                floatArrayOf(
                    topLeft,
                    topRight,
                    bottomRight,
                    bottomLeft,
                )
            }

            is CornerBasedShape -> {
                val size = size
                val maxRadius = size.minDimension / 2f
                val isLtr = layoutDirection == LayoutDirection.Ltr
                val topLeft =
                    normalizedRadius(
                        if (isLtr) shape.topStart.toPx(size, this)
                        else shape.topEnd.toPx(size, this),
                        maxRadius,
                    )
                val topRight =
                    normalizedRadius(
                        if (isLtr) shape.topEnd.toPx(size, this)
                        else shape.topStart.toPx(size, this),
                        maxRadius,
                    )
                val bottomRight =
                    normalizedRadius(
                        if (isLtr) shape.bottomEnd.toPx(size, this)
                        else shape.bottomStart.toPx(size, this),
                        maxRadius,
                    )
                val bottomLeft =
                    normalizedRadius(
                        if (isLtr) shape.bottomStart.toPx(size, this)
                        else shape.bottomEnd.toPx(size, this),
                        maxRadius,
                    )
                floatArrayOf(
                    topLeft,
                    topRight,
                    bottomRight,
                    bottomLeft,
                )
            }

            else -> null
        }

private fun normalizedRadius(radius: Float, maximum: Float): Float {
    require(radius.isFinite()) { "Corner radii must be finite." }
    return radius.fastCoerceAtLeast(0f).fastCoerceAtMost(maximum)
}

private fun throwUnsupportedSDFException(): Nothing {
    throw UnsupportedOperationException("Only CornerBasedShape is supported in lens effects.")
}
