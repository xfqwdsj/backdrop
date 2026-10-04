package top.ltfan.backdrop.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max
import top.ltfan.backdrop.BackdropExtension
import top.ltfan.backdrop.LocalBackdropRenderEpoch
import top.ltfan.backdrop.LocalBackdropStyle
import top.ltfan.backdrop.internal.ShapeProvider
import top.ltfan.backdrop.internal.SurfaceBounds
import top.ltfan.backdrop.internal.blur
import top.ltfan.backdrop.internal.setHdrColor

internal class ShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: (() -> Shadow)?,
    val surfaceBounds: SurfaceBounds,
) : ModifierNodeElement<ShadowNode>() {

    override fun create(): ShadowNode {
        return ShadowNode(shapeProvider, shadow, surfaceBounds)
    }

    override fun update(node: ShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.surfaceBounds = surfaceBounds
        node.onObservedReadsChanged()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

internal class ShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: (() -> Shadow)?,
    var surfaceBounds: SurfaceBounds,
) : DrawModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null
    private var renderEpoch = 0

    private val paint = Paint()

    override fun onObservedReadsChanged() {
        observeReads {
            val config = (shadow ?: currentValueOf(LocalBackdropStyle).shadow)()
            surfaceBounds.shadow =
                with(requireDensity()) {
                    if (config is Shadow.Config) {
                        val radius = config.radius.toPx() * 2f
                        val x = config.offset.x.toPx()
                        val y = config.offset.y.toPx()
                        BackdropExtension(
                            radius + max(-x, 0f),
                            radius + max(-y, 0f),
                            radius + max(x, 0f),
                            radius + max(y, 0f),
                        )
                    } else BackdropExtension.None
                }
        }
        invalidateDraw()
    }

    override fun onDensityChange() {
        onObservedReadsChanged()
    }

    override fun ContentDrawScope.draw() {
        val shadow = (shadow ?: currentValueOf(LocalBackdropStyle).shadow)()
        if (shadow !is Shadow.Config) {
            releaseDrawingLayer()
            return drawContent()
        }
        val epoch = currentValueOf(LocalBackdropRenderEpoch)
        if (epoch != renderEpoch) {
            renderEpoch = epoch
            releaseDrawingLayer()
        }
        if (shadowLayer == null) {
            shadowLayer =
                requireGraphicsContext().createGraphicsLayer().apply {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
        }
        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val radius = shadow.radius.toPx()
            val offsetX = shadow.offset.x.toPx()
            val offsetY = shadow.offset.y.toPx()
            val left = radius * 2f + max(-offsetX, 0f)
            val top = radius * 2f + max(-offsetY, 0f)
            val right = radius * 2f + max(offsetX, 0f)
            val bottom = radius * 2f + max(offsetY, 0f)
            val shadowSize =
                IntSize(
                    ceil(size.width + left + right).toInt(),
                    ceil(size.height + top + bottom).toInt(),
                )
            val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)

            configurePaint(shadow)

            shadowLayer.alpha = shadow.alpha
            shadowLayer.blendMode = shadow.blendMode
            shadowLayer.record(shadowSize) {
                translate(left + offsetX, top + offsetY) {
                    val canvas = drawContext.canvas
                    canvas.drawOutline(outline, paint)
                    canvas.translate(-offsetX, -offsetY)
                    canvas.drawOutline(outline, ShadowMaskPaint)
                    canvas.translate(offsetX, offsetY)
                }
            }

            translate(-left, -top) {
                drawLayer(shadowLayer)
            }
        }

        drawContent()
    }

    override fun onAttach() {
        renderEpoch = currentValueOf(LocalBackdropRenderEpoch)
        onObservedReadsChanged()
    }

    override fun onDetach() {
        releaseDrawingLayer()
    }

    private fun releaseDrawingLayer() {
        shadowLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        shadowLayer = null
    }

    private fun DrawScope.configurePaint(shadow: Shadow.Config) {
        paint.setHdrColor(shadow.color)
        paint.blur(shadow.radius.toPx())
    }
}

private val ShadowMaskPaint =
    Paint().apply {
        blendMode = BlendMode.Clear
    }
