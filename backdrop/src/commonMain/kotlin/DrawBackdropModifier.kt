package top.ltfan.backdrop

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.interaction.interactionLight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

/**
 * Draws a surface using fixed ordering: outer shadow, blurred and clipped source, fill and interior
 * light, children, then contour highlight and inner shadow. Null producers inherit at this
 * modifier's position; an explicit None omits that decoration. [backgroundAlpha] fades the
 * background group and leaves children and foreground decorations alone. Use [renderBackdrop] to
 * choose another order or include decorations in the fading group.
 */
public fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    blurRadius: (() -> Dp)? = null,
    fill: (() -> Color)? = null,
    highlight: (() -> Highlight)? = null,
    shadow: (() -> Shadow)? = null,
    innerShadow: (() -> InnerShadow)? = null,
    interactionLighting: (() -> InteractionLighting)? = null,
    backgroundAlpha: () -> Float = { 1f },
    insets: (() -> BackdropInsets)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier =
    renderBackdrop(
        backdrop,
        shape,
        backgroundAlpha,
        insets,
        layerBlock,
        exportedBackdrop,
    ) {
        // Defaults are looked up only for inherited fields, inside effect observation.
        val radius = (blurRadius ?: resolveStyle().blurRadius)()
        val contour = (highlight ?: resolveStyle().highlight)()
        val outer = (shadow ?: resolveStyle().shadow)()
        val inner = (innerShadow ?: resolveStyle().innerShadow)()
        val color = (fill ?: resolveStyle().fill)()
        val material = image {
            source()
            effects { blur(radius.toPx()) }
            clipSurface()
        }
        val lighting = (interactionLighting ?: resolveStyle().interactionLighting)()
        group {
            shadow(outer)
            draw(material)
            if (color != Color.Transparent) fill(color)
            if (lighting is InteractionLighting.Config) interactionLight(lighting)
        }
        foreground {
            highlight(
                contour,
                colorSource = material,
                interactionLighting = lighting,
            )
            innerShadow(inner)
        }
        styleInsets = insets ?: resolveStyle().insets
    }
