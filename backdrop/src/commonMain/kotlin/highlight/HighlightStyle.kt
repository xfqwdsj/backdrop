package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import kotlin.math.PI
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.RuntimeShaderCache
import top.ltfan.backdrop.internal.AmbientHighlightShaderString
import top.ltfan.backdrop.internal.DefaultHighlightShaderString
import top.ltfan.backdrop.isRuntimeShaderSupported

@Immutable
public interface HighlightStyle {

    public val color: Color

    public val blendMode: BlendMode

    public fun DrawScope.createShader(
        shape: Shape,
        runtimeShaderCache: RuntimeShaderCache,
    ): RuntimeShader?

    /** Disables the static style while retaining an optional environment pass. */
    @Immutable
    public data object None : HighlightStyle {
        override val color: Color = Color.Transparent
        override val blendMode: BlendMode = BlendMode.SrcOver

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache,
        ): RuntimeShader? = null
    }

    @Immutable
    public data class Plain(
        override val color: Color = Color.White.copy(alpha = 0.38f),
        override val blendMode: BlendMode = BlendMode.Plus,
    ) : HighlightStyle {

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache,
        ): RuntimeShader? = null
    }

    @Immutable
    public data class Default(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus,
        /** Direction of the highlight normal in degrees from the positive x axis. */
        val angle: Float = 90f,
        @param:FloatRange(from = 0.0) val falloff: Float = 1f,
    ) : HighlightStyle {

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache,
        ): RuntimeShader? {
            return if (isRuntimeShaderSupported()) {
                runtimeShaderCache
                    .obtainRuntimeShader(
                        "Default",
                        DefaultHighlightShaderString,
                    )
                    .apply {
                        setFloatUniform("size", size.width, size.height)
                        setFloatUniform("cornerRadii", getCornerRadii(shape))
                        setColorUniform("color", color.copy(alpha = 1f))
                        setFloatUniform("angle", angle * (PI / 180f).toFloat())
                        setFloatUniform("falloff", falloff)
                    }
            } else {
                null
            }
        }
    }

    @Immutable
    public data class Ambient(
        /** Sheen color. Its alpha is the strength; extended-range RGB keeps the sheen HDR. */
        override val color: Color = Color.White.copy(alpha = 0.38f),
        override val blendMode: BlendMode = DrawScope.DefaultBlendMode,
        /** Direction of the highlight normal in degrees from the positive x axis. */
        val angle: Float = 90f,
        @param:FloatRange(from = 0.0) val falloff: Float = 1f,
    ) : HighlightStyle {

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache,
        ): RuntimeShader? {
            return if (isRuntimeShaderSupported()) {
                runtimeShaderCache
                    .obtainRuntimeShader(
                        "Ambient",
                        AmbientHighlightShaderString,
                    )
                    .apply {
                        setFloatUniform("size", size.width, size.height)
                        setFloatUniform("cornerRadii", getCornerRadii(shape))
                        setColorUniform("color", color.copy(alpha = 1f))
                        setFloatUniform("angle", angle * (PI / 180f).toFloat())
                        setFloatUniform("falloff", falloff)
                    }
            } else {
                null
            }
        }
    }

    public companion object {

        @Stable public val Default: Default = Default()

        @Stable public val Ambient: Ambient = Ambient()

        @Stable public val Plain: Plain = Plain()
    }
}

private fun DrawScope.getCornerRadii(shape: Shape): FloatArray {
    val size = size
    val maxRadius = size.minDimension / 2f
    val shape = shape as? CornerBasedShape ?: return FloatArray(4) { maxRadius }
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) shape.topStart.toPx(size, this) else shape.topEnd.toPx(size, this)
    val topRight = if (isLtr) shape.topEnd.toPx(size, this) else shape.topStart.toPx(size, this)
    val bottomRight =
        if (isLtr) shape.bottomEnd.toPx(size, this) else shape.bottomStart.toPx(size, this)
    val bottomLeft =
        if (isLtr) shape.bottomStart.toPx(size, this) else shape.bottomEnd.toPx(size, this)
    return floatArrayOf(
        topLeft.fastCoerceAtMost(maxRadius),
        topRight.fastCoerceAtMost(maxRadius),
        bottomRight.fastCoerceAtMost(maxRadius),
        bottomLeft.fastCoerceAtMost(maxRadius),
    )
}
