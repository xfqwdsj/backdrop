package top.ltfan.backdrop

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import top.ltfan.backdrop.backdrops.LayerBackdrop

internal const val StandaloneBackdropDeprecation =
    "Standalone Backdrop is deprecated. Migrate to top.ltfan.m3x:backdrop. " +
        "See https://github.com/xfqwdsj/backdrop#migrate-to-m3x"

/**
 * Draws a receiver declaration with image composition and surface phases. The root selects
 * presentation outputs; nested images retain their own compositing semantics. Ordinary drawing
 * shares its destination when dependencies and compositing permit it. [BackdropImageScope.layer]
 * requests an independent compositing boundary. [backgroundAlpha] applies once to the background;
 * foreground and child content retain their own opacity.
 */
@Deprecated(StandaloneBackdropDeprecation, level = DeprecationLevel.WARNING)
public fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    backgroundAlpha: () -> Float = { 1f },
    insets: (() -> BackdropInsets)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    render: (BackdropRenderScope.() -> Unit)? = null,
): Modifier =
    backdropDeclaration(
        backdrop,
        shape,
        backgroundAlpha,
        insets,
        layerBlock,
        exportedBackdrop,
        render,
    )
