package top.ltfan.backdrop.shadow

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import top.ltfan.backdrop.BackdropImageScope
import top.ltfan.backdrop.BackdropPrimitive
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropPrimitiveGeometry
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropPrimitiveResources
import top.ltfan.backdrop.primitive

/** Parameters for a shape inner shadow image. */
@Immutable
public sealed interface InnerShadow {
    /** Omits the primitive and releases its rendering resources. */
    @Immutable public data object None : InnerShadow

    @Immutable
    public data class Config
    public constructor(
        public val radius: Dp = 24f.dp,
        public val offset: DpOffset = DpOffset(0f.dp, radius),
        public val color: Color = Color.Black.copy(alpha = 0.15f),
        @param:FloatRange(from = 0.0, to = 1.0) public val alpha: Float = 1f,
    ) : InnerShadow {
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

        @Stable public val Default: Config = InnerShadow()

        /** Interpolates the parameters of two configured inner shadows. */
        @Stable
        public fun lerp(
            start: Config,
            stop: Config,
            fraction: Float,
        ): Config {
            return InnerShadow(
                radius = lerp(start.radius, stop.radius, fraction),
                offset = lerp(start.offset, stop.offset, fraction),
                color = lerp(start.color, stop.color, fraction),
                alpha = lerp(start.alpha, stop.alpha, fraction),
            )
        }
    }
}

@Suppress("FunctionName")
public fun InnerShadow(
    radius: Dp = 24f.dp,
    offset: DpOffset = DpOffset(0f.dp, radius),
    color: Color = Color.Black.copy(alpha = 0.15f),
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1f,
): InnerShadow.Config = InnerShadow.Config(radius, offset, color, alpha)

/** Draws a clipped inner shadow at the current declaration position. */
public fun BackdropImageScope.innerShadow(
    config: InnerShadow,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    if (config === InnerShadow.None) return
    primitive(
        InnerShadowPrimitive(config as InnerShadow.Config),
        alpha = alpha,
        blendMode = blendMode,
    )
}

private data class InnerShadowPrimitive(val config: InnerShadow.Config) :
    BackdropPrimitive<InnerShadowPrimitive> {
    override fun bounds(geometry: BackdropPrimitiveGeometry): Rect = geometry.surfaceBounds

    override fun createRenderer(
        resources: BackdropPrimitiveResources,
    ): BackdropPrimitiveRenderer<InnerShadowPrimitive> = Renderer(resources)

    private class Renderer(resources: BackdropPrimitiveResources) :
        BackdropPrimitiveRenderer<InnerShadowPrimitive> {
        private val renderer = ShadowRenderer(resources)

        override fun draw(
            scope: DrawScope,
            primitive: InnerShadowPrimitive,
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
