package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

/**
 * Declares one surface: a serial source effect chain, surface drawing, and decorations. Outer
 * shadows precede the processed source; fill, surface drawing and interior light precede children;
 * highlight, inner shadow and foreground drawing follow children. Repeated appearance setters
 * replace that slot. Effect and drawing blocks append in their respective phases.
 *
 * Unset appearance slots inherit at the receiving modifier's position. Draw callbacks use their
 * destination directly. The enclosing [BackdropImageScope] provides images, masks and explicit
 * layer isolation.
 */
@BackdropRenderDsl
public class BackdropSurfaceScope
internal constructor(
    size: Size,
    public val density: Density,
    public val shape: Shape,
    public val layoutDirection: LayoutDirection,
    private val imageScope: BackdropImageScope? = null,
) {
    private val initialSize = size

    /** Measured surface pixels, shared with the enclosing image declaration. */
    public val size: Size
        get() = imageScope?.size ?: initialSize

    private var declaring = true

    internal fun finishDeclaration() {
        declaring = false
    }

    private fun checkDeclaration() {
        check(declaring) { "Surface operations belong to the declaration phase" }
    }

    internal val effectBlocks = mutableListOf<BackdropEffectScope.() -> Unit>()
    internal var explicitEffects: Boolean = false
    internal var fill: (DrawScope.(Rect) -> Unit)? = null
    internal var contour: Highlight? = null
    internal var outer: Shadow? = null
    internal var inner: InnerShadow? = null
    internal var lighting: InteractionLighting? = null
    internal val paints = mutableListOf<DrawScope.(BackdropRoom) -> Unit>()
    internal val foregroundPaints = mutableListOf<DrawScope.(BackdropRoom) -> Unit>()
    internal val behindDrawings = mutableListOf<SurfaceDecoration<*>>()
    internal val surfaceDrawings = mutableListOf<SurfaceDecoration<*>>()
    internal val foregroundDrawings = mutableListOf<SurfaceDecoration<*>>()

    /** Appends optical stages in declaration order and replaces inherited blur. */
    public fun effects(block: @BackdropRenderDsl BackdropEffectScope.() -> Unit) {
        checkDeclaration()
        explicitEffects = true
        effectBlocks += block
    }

    /** Replaces the inherited fill, evaluated in declaration observation. */
    public fun fill(color: Color) {
        checkDeclaration()
        fill = { bounds -> drawRect(color, bounds.topLeft, bounds.size) }
    }

    /** Replaces the inherited fill with a brush in surface coordinates. */
    public fun fill(brush: Brush, alpha: Float = 1f) {
        checkDeclaration()
        requireAlpha(alpha)
        fill = { bounds -> drawRect(brush, bounds.topLeft, bounds.size, alpha = alpha) }
    }

    /** Draws directly above the processed source and fill, clipped to visible surface coverage. */
    public fun paintSurface(block: @BackdropRenderDsl DrawScope.(BackdropRoom) -> Unit) {
        checkDeclaration()
        paints += block
    }

    /** Draws directly after foreground decorations, clipped to the measured surface shape. */
    public fun paintForeground(block: @BackdropRenderDsl DrawScope.(BackdropRoom) -> Unit) {
        checkDeclaration()
        foregroundPaints += block
    }

    public fun highlight(config: Highlight) {
        checkDeclaration()
        contour = config
    }

    public fun shadow(config: Shadow) {
        checkDeclaration()
        outer = config
    }

    public fun innerShadow(config: InnerShadow) {
        checkDeclaration()
        inner = config
    }

    public fun interactionLight(config: InteractionLighting) {
        checkDeclaration()
        lighting = config
    }

    /** Adds a stateful drawing before the outer shadow and source. */
    public fun <P : BackdropDecoration<P>> drawBehind(decoration: P) {
        checkDeclaration()
        behindDrawings += SurfaceDecoration(decoration)
    }

    /** Adds a stateful drawing after surface paint and before interior lighting. */
    public fun <P : BackdropDecoration<P>> drawSurface(decoration: P) {
        checkDeclaration()
        surfaceDrawings += SurfaceDecoration(decoration)
    }

    /** Adds a stateful drawing after the contour and inner shadow. */
    public fun <P : BackdropDecoration<P>> drawForeground(decoration: P) {
        checkDeclaration()
        foregroundDrawings += SurfaceDecoration(decoration)
    }
}
