package top.ltfan.backdrop

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope

internal sealed interface RenderImageNode {
    data object Source : RenderImageNode

    data object Empty : RenderImageNode

    data class DrawWithContent(
        val input: Int,
        val sampling: BackdropSampling,
        val draw: DrawScope.(DrawScope.() -> Unit, BackdropRoom) -> Unit,
    ) : RenderImageNode

    data class SurfaceClip(val input: Int) : RenderImageNode

    data class ShapeClip(val input: Int, val shape: Shape) : RenderImageNode

    data class Paint(
        val input: Int?,
        val draw: DrawScope.(BackdropRoom) -> Unit,
        val surfaceClip: Boolean = false,
    ) : RenderImageNode

    data class Process(val input: Int, val effects: BackdropEffectScope.() -> Unit) :
        RenderImageNode

    data class Composite(
        val destination: Int,
        val source: Int,
        val alpha: Float,
        val blendMode: BlendMode,
    ) : RenderImageNode

    data class Mask(
        val input: Int,
        val mask: Int,
        val channel: BackdropMaskChannel,
        val inverse: Boolean,
    ) : RenderImageNode

    data class Clip(val input: Int, val path: Path) : RenderImageNode

    data class Transform(val input: Int, val matrix: Matrix) : RenderImageNode

    data class Primitive<P : BackdropPrimitive<P>>(
        val primitive: P,
        val inputs: List<BackdropPrimitiveInput>,
    ) : RenderImageNode
}
