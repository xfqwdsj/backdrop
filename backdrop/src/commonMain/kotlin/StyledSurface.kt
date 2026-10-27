package top.ltfan.backdrop

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import top.ltfan.backdrop.effects.colorControls
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.effects.progressiveBlurGeometry
import top.ltfan.backdrop.effects.vibrancy
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.interaction.interactionLight
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

/**
 * Applies one fixed-order appearance to [input]. Unspecified fields inherit at this receiver. Color
 * controls, blur, vibrancy and lens precede surface clipping. Background contains shadow, processed
 * input, fill and interior light. Foreground contains highlight and inner shadow. Highlight samples
 * the processed input before fill. Child content stays between the two outputs.
 *
 * A root may declare this once, without another surface or foreground owner. Use graph operations
 * for additional background drawing and [BackdropRenderScope.appendForeground] for later foreground
 * drawing.
 */
public fun BackdropRenderScope.styledSurface(
    style: BackdropStyle? = null,
    input: BackdropImage = current,
) {
    check(!styledSurfaceDeclared && !surfaceDeclared && foregroundImage == null) {
        "styledSurface requires sole ownership of the root surface and foreground"
    }
    checkImage(input)
    val appearance = BackdropStyleResolver(style, resolveStyle)
    val material = image {
        replace(input)
        styleEffects(appearance)
        clipSurface()
    }
    val contour = appearance.highlight()
    val outer = appearance.shadow()
    val inner = appearance.innerShadow()
    val lighting = appearance.interactionLighting()
    styleInsets = appearance::insets
    group {
        shadow(outer)
        draw(material)
        paintFill(appearance::fill)
        if (lighting is InteractionLighting.Config) interactionLight(lighting)
    }
    styledForeground {
        highlight(contour, colorSource = material, interactionLighting = lighting)
        innerShadow(inner)
    }
}

internal fun BackdropImageScope.styleEffects(
    style: BackdropStyleResolver,
    fill: (() -> BackdropFill)? = style::fill,
) {
    val blur = style::blur
    val layered = blurTopology(blur)
    val ramp = fill?.let(::fillRamp)
    if (layered.value) {
        effects { colorControls(style.colorControls()) }
        layeredBlur(blur, layered)
        effects {
            vibrancy(style.vibrancy())
            lens(style.lens())
            fillCoverage(ramp)
        }
    } else
        effects {
            check(!layered.value) { "Blur topology changed without redeclaration" }
            colorControls(style.colorControls())
            filterBlur(blur())
            vibrancy(style.vibrancy())
            lens(style.lens())
            fillCoverage(ramp)
        }
}

/** Appends a delayed fill in surface coordinates; ramp coverage remains independent of blur. */
public fun BackdropImageScope.fill(config: () -> BackdropFill) {
    fillCoverage(config)
    paintFill(config)
}

internal fun BackdropImageScope.paintFill(config: () -> BackdropFill) {
    val cache = BackdropFillRampCache()
    paintSurface(CompositingStrategy.Auto) { room -> drawStyledFill(config(), room, cache) }
}

public fun BackdropImageScope.fill(config: BackdropFill): Unit = fill { config }

internal fun DrawScope.drawStyledFill(
    value: BackdropFill,
    room: BackdropRoom,
    cache: BackdropFillRampCache,
) {
    val brush = value.brush ?: return
    val alpha = value.alpha ?: 1f
    requireAlpha(alpha)
    val origin = room.extension.originInNode
    val domain =
        Size(
            room.size.width + room.extension.left + room.extension.right,
            room.size.height + room.extension.top + room.extension.bottom,
        )
    val ramp = value.ramp
    if (ramp == null) drawRect(brush, origin, domain, alpha = alpha)
    else
        translate(origin.x, origin.y) {
            val side = value.edge.side(layoutDirection)
            val leading = if (side.isVertical) room.extension.top else room.extension.left
            drawBackdropFill(
                true,
                brush,
                ramp,
                domain,
                cache,
                value.edge,
                room.size,
                leading,
                alpha,
            )
        }
}

/** Observe ramp geometry separately from animated brush and opacity. */
private fun fillRamp(config: () -> BackdropFill): State<Pair<BackdropRamp?, BackdropEdge>> =
    derivedStateOf(structuralEqualityPolicy()) { config().let { it.ramp to it.edge } }

internal fun BackdropImageScope.fillCoverage(config: () -> BackdropFill) {
    val ramp = fillRamp(config)
    effects { fillCoverage(ramp) }
}

private fun BackdropEffectScope.fillCoverage(ramp: State<Pair<BackdropRamp?, BackdropEdge>>?) {
    val (value, edge) = ramp?.value ?: return
    if (value != null)
        progressiveBlurGeometry(size, layoutDirection, edge, value)?.let { cover(it.coverage) }
}
