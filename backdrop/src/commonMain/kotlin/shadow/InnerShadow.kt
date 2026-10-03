package top.ltfan.backdrop.shadow

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp

/** An inner shadow decoration, either configured or explicitly disabled. */
@Immutable
public sealed interface InnerShadow {

    /** Disables the decoration and releases its drawing layer. */
    @Immutable public data object None : InnerShadow

    /** Parameters evaluated by the decoration's drawing node. */
    @Immutable
    public data class Config(
        public val radius: Dp = 24f.dp,
        public val offset: DpOffset = DpOffset(0f.dp, radius),
        public val color: Color = Color.Black.copy(alpha = 0.15f),
        @param:FloatRange(from = 0.0, to = 1.0) public val alpha: Float = 1f,
        public val blendMode: BlendMode = DrawScope.DefaultBlendMode,
    ) : InnerShadow

    public companion object {

        @Stable public val Default: Config = InnerShadow()

        /** Interpolates the parameters of two configured inner shadows. */
        @Stable
        public fun lerp(
            start: Config,
            stop: Config,
            fraction: Float,
        ): Config {
            return InnerShadow(
                radius = lerp(start.radius, stop.radius, fraction),
                offset = lerp(start.offset, stop.offset, fraction),
                color = lerp(start.color, stop.color, fraction),
                alpha = lerp(start.alpha, stop.alpha, fraction),
                blendMode = if (fraction < 0.5f) start.blendMode else stop.blendMode,
            )
        }
    }
}

/** Creates a configured inner shadow decoration. */
@Stable
public fun InnerShadow(
    radius: Dp = 24f.dp,
    offset: DpOffset = DpOffset(0f.dp, radius),
    color: Color = Color.Black.copy(alpha = 0.15f),
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
): InnerShadow.Config =
    InnerShadow.Config(
        radius = radius,
        offset = offset,
        color = color,
        alpha = alpha,
        blendMode = blendMode,
    )
