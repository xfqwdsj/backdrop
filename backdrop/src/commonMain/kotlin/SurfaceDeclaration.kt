package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
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
    if (this is BackdropRenderScope) {
        check(!styledSurfaceDeclared) { "surface and styledSurface cannot own the same root" }
        surfaceDeclared = true
    }
    val style = BackdropStyleResolver(null, resolveStyle)
    val contour = config.contour ?: style.highlight()
    val outer = config.outer ?: style.shadow()
    val inner = config.inner ?: style.innerShadow()
    val lighting = config.lighting ?: style.interactionLighting()
    val inheritedFill = if (config.fill == null) style::fill else null
    val fillCache = BackdropFillRampCache()
    if (this is BackdropRenderScope) styleInsets = style::insets
    config.finishDeclaration()
    val original = current
    val material = image {
        replace(original)
        if (config.explicitEffects) {
            effects { config.effectBlocks.forEach { it(this) } }
            if (inheritedFill != null) fillCoverage(inheritedFill)
        } else styleEffects(style, inheritedFill)
        clipSurface()
    }
    group {
        config.behindDrawings.forEach { decoration(it.value, material) }
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
            val explicitFill = config.fill
            if (explicitFill != null) explicitFill(this, bounds)
            else drawStyledFill(checkNotNull(inheritedFill).invoke(), room, fillCache)
            config.paints.forEach { it(this, room) }
        }
        config.surfaceDrawings.forEach { decoration(it.value, material) }
        if (lighting is InteractionLighting.Config) interactionLight(lighting)
    }
    val front: BackdropImageScope.() -> Unit = {
        highlight(contour, colorSource = material, interactionLighting = lighting)
        innerShadow(inner)
        config.foregroundDrawings.forEach { decoration(it.value, material) }
        if (config.foregroundPaints.isNotEmpty())
            paintSurface(CompositingStrategy.Auto) { room ->
                config.foregroundPaints.forEach { it(this, room) }
            }
    }
    if (this is BackdropRenderScope) foreground(front) else draw(image(front))
}

/**
 * Appends a stateful decoration at this graph position, using [processedSource] for declared
 * sampling. The caller owns source selection and clipping. Auto compositing and renderer resource
 * reuse match surface decorations; sampled images retain their own recording boundary.
 */
public fun <P : BackdropDecoration<P>> BackdropImageScope.decoration(
    value: P,
    processedSource: BackdropImage = current,
) {
    deferredPrimitive(listOf(processedSource)) { geometry ->
        val sampling = value.sampling(geometry)
        RenderImageNode.Primitive(
            DecorationPrimitive(value),
            if (sampling == null) emptyList()
            else listOf(BackdropPrimitiveInput(processedSource, sampling)),
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
