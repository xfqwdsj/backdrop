package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import top.ltfan.backdrop.BackdropCurve

/** Maps normalized incidence from a non-negative `from` value to a non-negative `to` value. */
@Immutable
public data class HighlightResponse(
    public val from: Float,
    public val to: Float,
    public val curve: BackdropCurve = BackdropCurve.Linear,
) {
    init {
        require(from.isFinite() && from >= 0f) { "from must be finite and non-negative" }
        require(to.isFinite() && to >= 0f) { "to must be finite and non-negative" }
    }

    public fun transform(input: Float): Float = from + (to - from) * curve.transform(input)
}
