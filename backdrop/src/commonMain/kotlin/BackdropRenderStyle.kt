package top.ltfan.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf

/** Complete rendering defaults evaluated at a receiving modifier's position. */
@Immutable
public data class BackdropRenderStyle(
    public val render: BackdropRenderScope.() -> Unit = NoEffects,
    public val insets: () -> BackdropInsets = { BackdropInsets.None },
) {
    public companion object {
        /** An explicit empty declaration: draws the unprocessed source, with no decorations. */
        @Stable public val NoEffects: BackdropRenderScope.() -> Unit = {}
        @Stable public val Default: BackdropRenderStyle = BackdropRenderStyle()
    }
}

public val LocalBackdropRenderStyle: ProvidableCompositionLocal<BackdropRenderStyle> =
    compositionLocalOf {
        BackdropRenderStyle.Default
    }

/** Provides complete rendering defaults for descendant receivers. */
@Composable
public fun ProvideBackdropRenderStyle(style: BackdropRenderStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackdropRenderStyle provides style, content = content)
}

/** Replaces specified producers and inherits omitted producers from the parent style. */
@Composable
public fun ProvideBackdropRenderStyle(
    render: BackdropRenderScope.() -> Unit = LocalBackdropRenderStyle.current.render,
    insets: () -> BackdropInsets = LocalBackdropRenderStyle.current.insets,
    content: @Composable () -> Unit,
) {
    ProvideBackdropRenderStyle(BackdropRenderStyle(render, insets), content)
}

internal val LocalBackdropRenderEpoch: ProvidableCompositionLocal<Int> = compositionLocalOf { 0 }
