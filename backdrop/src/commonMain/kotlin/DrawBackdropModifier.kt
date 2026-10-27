package top.ltfan.backdrop

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import top.ltfan.backdrop.backdrops.LayerBackdrop

/**
 * Draws a receiver declaration with image composition and surface phases. The root selects
 * presentation outputs; nested images retain their own compositing semantics. Ordinary drawing
 * shares its destination when dependencies and compositing permit it. [BackdropImageScope.layer]
 * requests an independent compositing boundary. [backgroundAlpha] applies once to the background;
 * foreground and child content retain their own opacity.
 */
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
