package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public data class Highlight(
    val width: Dp = 0.5f.dp,
    val blurRadius: Dp = width / 2f,
    @param:FloatRange(from = 0.0, to = 1.0) val alpha: Float = 1f,
    val style: HighlightStyle = HighlightStyle.Default,
) {

    public companion object {

        @Stable public val Default: Highlight = Highlight()

        @Stable public val Ambient: Highlight = Highlight(style = HighlightStyle.Ambient)

        @Stable public val Plain: Highlight = Highlight(style = HighlightStyle.Plain)
    }
}
