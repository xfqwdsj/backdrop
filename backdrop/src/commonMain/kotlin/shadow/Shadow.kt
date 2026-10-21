package top.ltfan.backdrop.shadow

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.BackdropImageScope
import top.ltfan.backdrop.BackdropPrimitive
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropPrimitiveGeometry
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropPrimitiveResources
import top.ltfan.backdrop.internal.maskBlurSupport
import top.ltfan.backdrop.primitive

/** Parameters for a shape shadow image. */
@Immutable
public sealed interface Shadow {
    /** Omits the primitive and releases its rendering resources. */
    @Immutable public data object None : Shadow

    @Immutable
    public data class Config
    public constructor(
        public val radius: Dp = 24f.dp,
        public val offset: DpOffset = DpOffset(0f.dp, radius / 6f),
        public val color: Color = Color.Black.copy(alpha = 0.1f),
        @param:FloatRange(from = 0.0, to = 1.0) public val alpha: Float = 1f,
    ) : Shadow {
        init {
            require(radius.value.isFinite() && radius.value >= 0f) {
                "Shadow radius must be finite and non-negative"
            }
            require(offset.x.value.isFinite() && offset.y.value.isFinite()) {
                "Shadow offsets must be finite"
            }
            require(alpha.isFinite() && alpha in 0f..1f) {
                "Shadow alpha must be finite and in 0..1"
            }
        }
    }

    public companion object {

        @Stable public val Default: Config = Shadow()
    }
}

@Suppress("FunctionName")
public fun Shadow(
    radius: Dp = 24f.dp,
    offset: DpOffset = DpOffset(0f.dp, radius / 6f),
    color: Color = Color.Black.copy(alpha = 0.1f),
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1f,
): Shadow.Config = Shadow.Config(radius, offset, color, alpha)

/** Draws an outer shape shadow at the current declaration position. */
public fun BackdropImageScope.shadow(
    config: Shadow,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    if (config === Shadow.None) return
    primitive(ShadowPrimitive(config as Shadow.Config), alpha = alpha, blendMode = blendMode)
}

private data class ShadowPrimitive(val config: Shadow.Config) : BackdropPrimitive<ShadowPrimitive> {
    override fun bounds(geometry: BackdropPrimitiveGeometry): Rect =
        with(geometry.density) {
            val r = maskBlurSupport(config.radius.toPx())
            val x = config.offset.x.toPx()
            val y = config.offset.y.toPx()
            Rect(
                -r - maxOf(-x, 0f),
                -r - maxOf(-y, 0f),
                geometry.size.width + r + maxOf(x, 0f),
                geometry.size.height + r + maxOf(y, 0f),
            )
        }

    override fun createRenderer(
        resources: BackdropPrimitiveResources,
    ): BackdropPrimitiveRenderer<ShadowPrimitive> = Renderer(resources)

    private class Renderer(resources: BackdropPrimitiveResources) :
        BackdropPrimitiveRenderer<ShadowPrimitive> {
        private val renderer = ShadowRenderer(resources)

        override fun draw(
            scope: DrawScope,
            primitive: ShadowPrimitive,
            context: BackdropPrimitiveDrawContext,
        ) {
            renderer.draw(
                scope,
                context.shape.createOutline(context.size, context.layoutDirection, context.density),
                primitive.config,
            )
        }

        override fun refresh() {
            renderer.release()
        }

        override fun release() {
            renderer.release()
        }
    }
}
