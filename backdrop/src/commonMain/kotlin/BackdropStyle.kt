package top.ltfan.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

/** Delayed appearance defaults for the fixed-order [drawBackdrop] surface. */
@Immutable
public data class BackdropStyle(
    public val blurRadius: () -> Dp = { 0.dp },
    public val fill: () -> Color = { Color.Transparent },
    public val highlight: () -> Highlight = { Highlight.Default },
    public val shadow: () -> Shadow = { Shadow.Default },
    public val innerShadow: () -> InnerShadow = { InnerShadow.None },
    public val interactionLighting: () -> InteractionLighting = { InteractionLighting.None },
    public val insets: () -> BackdropInsets = { BackdropInsets.None },
) {
    public companion object {
        @Stable public val Default: BackdropStyle = BackdropStyle()
        /** Explicit appearance with unprocessed input and no decorations. */
        @Stable
        public val NoEffects: BackdropStyle =
            BackdropStyle(
                highlight = { Highlight.None },
                shadow = { Shadow.None },
            )
    }
}

public val LocalBackdropStyle: ProvidableCompositionLocal<BackdropStyle> = compositionLocalOf {
    BackdropStyle.Default
}

@Composable
public fun ProvideBackdropStyle(style: BackdropStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackdropStyle provides style, content = content)
}

/** Replaces supplied fields and inherits the parent's remaining producers. */
@Composable
public fun ProvideBackdropStyle(
    blurRadius: () -> Dp = LocalBackdropStyle.current.blurRadius,
    fill: () -> Color = LocalBackdropStyle.current.fill,
    highlight: () -> Highlight = LocalBackdropStyle.current.highlight,
    shadow: () -> Shadow = LocalBackdropStyle.current.shadow,
    innerShadow: () -> InnerShadow = LocalBackdropStyle.current.innerShadow,
    interactionLighting: () -> InteractionLighting = LocalBackdropStyle.current.interactionLighting,
    insets: () -> BackdropInsets = LocalBackdropStyle.current.insets,
    content: @Composable () -> Unit,
) {
    ProvideBackdropStyle(
        BackdropStyle(
            blurRadius,
            fill,
            highlight,
            shadow,
            innerShadow,
            interactionLighting,
            insets,
        ),
        content,
    )
}
