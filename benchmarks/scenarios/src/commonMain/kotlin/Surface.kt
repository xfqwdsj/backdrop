package top.ltfan.backdrop.benchmark.scenarios

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.BackdropRoom
import top.ltfan.backdrop.drawBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.effects.vibrancy
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.shadow

private val contour = Highlight(colorResponse = HighlightColorResponse(amount = .15f))

/** Draws shared optics and contour lighting with independent background opacity. */
internal fun Modifier.benchmarkSurface(
    source: Backdrop,
    shape: () -> Shape,
    phase: () -> Float,
    backgroundAlpha: () -> Float = { 1f },
): Modifier =
    drawBackdrop(source, shape, backgroundAlpha = backgroundAlpha) {
        val material = image {
            source()
            effects { scenarioOptics(phase) }
            clipSurface()
        }
        group {
            shadow(Shadow.Default)
            draw(material)
            paintSurface(CompositingStrategy.Auto) { room -> scenarioTint(room) }
        }
        foreground { highlight(contour, colorSource = material) }
    }

private fun BackdropEffectScope.scenarioOptics(
    phase: () -> Float,
) {
    val radius = (8f + phase().coerceIn(0f, 1f) * 20f) * density
    blur(radius)
    vibrancy()
    if (shape is CornerBasedShape) {
        lens(24f * density, 18f * density, depthEffect = true)
    }
}

private fun DrawScope.scenarioTint(room: BackdropRoom) {
    drawRect(
        Color.White.copy(alpha = .18f),
        room.extension.originInNode,
        Size(
            room.size.width + room.extension.left + room.extension.right,
            room.size.height + room.extension.top + room.extension.bottom,
        ),
    )
}
