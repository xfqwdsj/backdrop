package top.ltfan.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

/**
 * Visual defaults consumed at the modifier's position in the UI tree. Producers run in drawing or
 * effect observation, so snapshot state read inside them updates rendering without recomposition.
 * Each producer supplies a complete configuration; effects execute in their declared order.
 */
@Immutable
public data class BackdropStyle(
    public val effects: BackdropEffectScope.() -> Unit = NoEffects,
    public val highlight: () -> Highlight = DefaultHighlight,
    public val shadow: () -> Shadow = DefaultShadow,
    public val innerShadow: () -> InnerShadow = DefaultInnerShadow,
) {
    public companion object {
        /** An empty effect chain, suitable for explicitly disabling inherited effects. */
        @Stable public val NoEffects: BackdropEffectScope.() -> Unit = {}

        /** Default highlight and shadow, an empty effect chain, and disabled inner shadow. */
        @Stable public val Default: BackdropStyle = BackdropStyle()
    }
}

private val DefaultHighlight: () -> Highlight = { Highlight.Default }
private val DefaultShadow: () -> Shadow = { Shadow.Default }
private val DefaultInnerShadow: () -> InnerShadow = { InnerShadow.None }

/**
 * Visual defaults for descendant backdrop modifiers. Explicit modifier parameters take priority.
 */
public val LocalBackdropStyle: ProvidableCompositionLocal<BackdropStyle> = compositionLocalOf {
    BackdropStyle.Default
}

/** Provides complete visual defaults for [content]. Producers are evaluated by consuming nodes. */
@Composable
public fun ProvideBackdropStyle(style: BackdropStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackdropStyle provides style, content = content)
}

/**
 * Replaces the specified producers and inherits omitted producers from the parent style. Returning
 * a decoration's `None` disables it; [BackdropStyle.NoEffects] disables the inherited effect chain.
 */
@Composable
public fun ProvideBackdropStyle(
    effects: BackdropEffectScope.() -> Unit = LocalBackdropStyle.current.effects,
    highlight: () -> Highlight = LocalBackdropStyle.current.highlight,
    shadow: () -> Shadow = LocalBackdropStyle.current.shadow,
    innerShadow: () -> InnerShadow = LocalBackdropStyle.current.innerShadow,
    content: @Composable () -> Unit,
) {
    ProvideBackdropStyle(BackdropStyle(effects, highlight, shadow, innerShadow), content)
}
