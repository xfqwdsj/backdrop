package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.interaction.interactionLight
import top.ltfan.backdrop.shadow.innerShadow
import top.ltfan.backdrop.shadow.shadow

/**
 * Declares fixed surface phases around the selected input. Unset slots inherit appearance at the
 * receiving modifier. The same declaration is available inside images and explicit layers. At the
 * root, foreground follows child content; inside an image it completes that image.
 */
public fun BackdropImageScope.surface(block: BackdropSurfaceScope.() -> Unit = {}) {
    val config = surfaceScope().apply(block)
    fun style(): BackdropStyle = resolveStyle()
    if (!config.explicitEffects) {
        val radius = style().blurRadius
        config.effects { blur(radius().toPx()) }
    }
    val contour = config.contour ?: style().highlight()
    val outer = config.outer ?: style().shadow()
    val inner = config.inner ?: style().innerShadow()
    val lighting = config.lighting ?: style().interactionLighting()
    val fill =
        config.fill
            ?: run {
                val color = style().fill
                { bounds: Rect -> drawRect(color(), bounds.topLeft, bounds.size) }
            }
    if (this is BackdropRenderScope) styleInsets = { resolveStyle().insets() }
    config.finishDeclaration()
    val original = current
    val material = image {
        replace(original)
        effects { config.effectBlocks.forEach { it(this) } }
        clipSurface()
    }
    group {
        config.behindDrawings.forEach { it.declare(this, material) }
        shadow(outer)
        draw(material)
        paintSurface(CompositingStrategy.Auto) { room ->
            val bounds =
                Rect(
                    room.extension.originInNode,
                    Size(
                        room.size.width + room.extension.left + room.extension.right,
                        room.size.height + room.extension.top + room.extension.bottom,
                    ),
                )
            fill(this, bounds)
            config.paints.forEach { it(this, room) }
        }
        config.surfaceDrawings.forEach { it.declare(this, material) }
        if (lighting is InteractionLighting.Config) interactionLight(lighting)
    }
    val front: BackdropImageScope.() -> Unit = {
        highlight(contour, colorSource = material, interactionLighting = lighting)
        innerShadow(inner)
        config.foregroundDrawings.forEach { it.declare(this, material) }
        if (config.foregroundPaints.isNotEmpty())
            paintSurface(CompositingStrategy.Auto) { room ->
                config.foregroundPaints.forEach { it(this, room) }
            }
    }
    if (this is BackdropRenderScope) foreground(front) else draw(image(front))
}

private fun <P : BackdropDecoration<P>> SurfaceDecoration<P>.declare(
    target: BackdropImageScope,
    material: BackdropImage,
) {
    target.deferredPrimitive(listOf(material)) { geometry ->
        val sampling = value.sampling(geometry)
        RenderImageNode.Primitive(
            DecorationPrimitive(value),
            if (sampling == null) emptyList()
            else listOf(BackdropPrimitiveInput(material, sampling)),
        )
    }
}

private class DecorationPrimitive<P : BackdropDecoration<P>>(val value: P) :
    BackdropPrimitive<DecorationPrimitive<P>> {
    override val compositingStrategy: CompositingStrategy = CompositingStrategy.Auto
    override val rendererKey: Any = value::class to value.rendererKey

    override fun bounds(geometry: BackdropGeometry) = value.bounds(geometry)

    override fun createRenderer(
        resources: BackdropRenderResources,
    ): BackdropPrimitiveRenderer<DecorationPrimitive<P>> {
        val renderer = value.createRenderer(resources)
        return object : BackdropPrimitiveRenderer<DecorationPrimitive<P>> {
            private var drawing: DecorationContext? = null
            private lateinit var input: BackdropPrimitiveDrawContext

            override fun prepare(
                primitive: DecorationPrimitive<P>,
                context: BackdropPrimitiveContext,
            ) {
                val next =
                    DecorationContext(
                        context,
                        context.extension,
                        context.outputBounds,
                        context.inputBounds.firstOrNull(),
                    ) {
                        input.input(0)
                    }
                drawing = next
                renderer.prepare(primitive.value, next)
            }

            override fun draw(
                scope: DrawScope,
                primitive: DecorationPrimitive<P>,
                context: BackdropPrimitiveDrawContext,
            ) {
                input = context
                val active = checkNotNull(drawing)
                active.generation++
                active.drawing = true
                try {
                    renderer.draw(scope, primitive.value, active)
                } finally {
                    active.drawing = false
                }
            }

            override fun refresh() {
                renderer.refresh()
            }

            override fun release() {
                renderer.release()
            }
        }
    }
}
