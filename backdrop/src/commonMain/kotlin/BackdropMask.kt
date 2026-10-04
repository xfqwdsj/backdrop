package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified

/**
 * A surface mask with one fill source and an optional progressive ramp. A null [brush] delegates
 * the fill to the consumer's theme. [alpha] multiplies the fill's own opacity; null delegates that
 * strength to the consumer. The ramp controls the mask independently of the blur.
 */
@Immutable
public data class BackdropMask(
    public val brush: Brush? = null,
    public val alpha: Float? = null,
    public val ramp: BackdropRamp? = null,
) {
    /** Uses [color] as the fill; [Color.Unspecified] delegates it to the consumer's theme. */
    public constructor(
        color: Color,
        alpha: Float? = null,
        ramp: BackdropRamp? = null,
    ) : this(if (color.isSpecified) SolidColor(color) else null, alpha, ramp)
}
