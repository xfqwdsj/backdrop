package top.ltfan.backdrop.shadow

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max
import top.ltfan.backdrop.BackdropPrimitiveResources
import top.ltfan.backdrop.internal.blur
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.maskBlurSupport
import top.ltfan.backdrop.internal.setHdrColor
import top.ltfan.backdrop.isRenderEffectSupported

/** Generates a shadow in an isolated layer so construction cannot erase its destination. */
internal class ShadowRenderer(private val resources: BackdropPrimitiveResources) {
    private var layer: GraphicsLayer? = null
    private val paint = Paint()
    private val clear = Paint().apply { blendMode = BlendMode.Clear }
    private var clipPath: Path? = null

    fun bounds(scope: DrawScope, config: Shadow.Config): Rect =
        with(scope) {
            val support = maskBlurSupport(config.radius.toPx())
            val x = config.offset.x.toPx()
            val y = config.offset.y.toPx()
            Rect(
                -support - max(-x, 0f),
                -support - max(-y, 0f),
                size.width + support + max(x, 0f),
                size.height + support + max(y, 0f),
            )
        }

    fun draw(scope: DrawScope, outline: Outline, config: Shadow.Config) =
        with(scope) {
            val bounds = bounds(this, config)
            val x = config.offset.x.toPx()
            val y = config.offset.y.toPx()
            val output = obtain()
            output.renderEffect = null
            output.alpha = config.alpha
            output.blendMode = BlendMode.SrcOver
            paint.setHdrColor(config.color)
            paint.blur(config.radius.toPx())
            output.record(
                Density(density, fontScale),
                layoutDirection,
                IntSize(ceil(bounds.width).toInt(), ceil(bounds.height).toInt()),
            ) {
                translate(-bounds.left + x, -bounds.top + y) {
                    val canvas = drawContext.canvas
                    canvas.drawOutline(outline, paint)
                    canvas.translate(-x, -y)
                    canvas.drawOutline(outline, clear)
                    canvas.translate(x, y)
                }
            }
            translate(bounds.left, bounds.top) { drawLayer(output) }
        }

    fun draw(scope: DrawScope, outline: Outline, config: InnerShadow.Config) =
        with(scope) {
            if (!isRenderEffectSupported()) return@with
            val output = obtain()
            val rounded =
                if (outline is Outline.Rounded) clipPath ?: Path().also { clipPath = it } else null
            paint.setHdrColor(config.color)
            paint.blur(0f)
            output.alpha = config.alpha
            output.blendMode = BlendMode.SrcOver
            val radius = config.radius.toPx()
            output.renderEffect =
                if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
            val offsetX = config.offset.x.toPx()
            val offsetY = config.offset.y.toPx()
            output.record(
                Density(density, fontScale),
                layoutDirection,
                IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
            ) {
                val canvas = drawContext.canvas
                canvas.save()
                canvas.clipOutline(outline, rounded)
                canvas.drawOutline(outline, paint)
                canvas.translate(offsetX, offsetY)
                canvas.drawOutline(outline, clear)
                canvas.restore()
            }
            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, rounded)
            drawLayer(output)
            canvas.restore()
        }

    private fun obtain(): GraphicsLayer =
        layer
            ?: resources.createGraphicsLayer().also {
                it.compositingStrategy = CompositingStrategy.Offscreen
                layer = it
            }

    fun release() {
        layer?.let(resources::releaseGraphicsLayer)
        layer = null
        clipPath = null
    }
}
