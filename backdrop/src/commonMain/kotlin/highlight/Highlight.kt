package top.ltfan.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A highlight decoration, either configured or explicitly disabled. */
@Immutable
public sealed interface Highlight {

    /** Disables the decoration and releases its drawing layer. */
    @Immutable public data object None : Highlight

    /** Parameters evaluated by the decoration's drawing node. */
    @Immutable
    public data class Config(
        public val width: Dp = 0.5f.dp,
        public val blurRadius: Dp = width / 2f,
        @param:FloatRange(from = 0.0, to = 1.0) public val alpha: Float = 1f,
        public val style: HighlightStyle = HighlightStyle.Default,
    ) : Highlight

    public companion object {

        @Stable public val Default: Config = Highlight()

        @Stable public val Ambient: Config = Highlight(style = HighlightStyle.Ambient)

        @Stable public val Plain: Config = Highlight(style = HighlightStyle.Plain)
    }
}

/** Creates a configured highlight decoration. */
@Stable
public fun Highlight(
    width: Dp = 0.5f.dp,
    blurRadius: Dp = width / 2f,
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1f,
    style: HighlightStyle = HighlightStyle.Default,
): Highlight.Config =
    Highlight.Config(
        width = width,
        blurRadius = blurRadius,
        alpha = alpha,
        style = style,
    )
