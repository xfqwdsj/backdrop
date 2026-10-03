package top.ltfan.backdrop.shadow

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** A shadow decoration, either configured or explicitly disabled. */
@Immutable
public sealed interface Shadow {

    /** Disables the decoration and releases its drawing layer. */
    @Immutable public data object None : Shadow

    /** Parameters evaluated by the decoration's drawing node. */
    @Immutable
    public data class Config(
        public val radius: Dp = 24f.dp,
        public val offset: DpOffset = DpOffset(0f.dp, radius / 6f),
        public val color: Color = Color.Black.copy(alpha = 0.1f),
        @param:FloatRange(from = 0.0, to = 1.0) public val alpha: Float = 1f,
        public val blendMode: BlendMode = DrawScope.DefaultBlendMode,
    ) : Shadow

    public companion object {

        @Stable public val Default: Config = Shadow()
    }
}

/** Creates a configured shadow decoration. */
@Stable
public fun Shadow(
    radius: Dp = 24f.dp,
    offset: DpOffset = DpOffset(0f.dp, radius / 6f),
    color: Color = Color.Black.copy(alpha = 0.1f),
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
): Shadow.Config =
    Shadow.Config(
        radius = radius,
        offset = offset,
        color = color,
        alpha = alpha,
        blendMode = blendMode,
    )
