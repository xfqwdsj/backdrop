package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import top.ltfan.backdrop.internal.createRuntimeShaderEffect

internal class PrimitiveGeometry(
    override val density: Density,
    override val size: Size,
    override val shape: Shape,
    override val layoutDirection: LayoutDirection,
    override val surfaceBounds: Rect,
) : BackdropGeometry

internal class PrimitiveContext(
    private val geometry: BackdropGeometry,
    override val extension: BackdropExtension,
    override val outputBounds: Rect,
    override val requestedBounds: Rect,
    override val inputBounds: List<Rect>,
    private val images: (Int, Int) -> BackdropImageInput,
    val interactionFrame: ProcessedBackdropFrame,
) : BackdropPrimitiveDrawContext, BackdropGeometry by geometry {
    var drawing: Boolean = false
    var generation: Int = 0
        private set

    fun beginDraw() {
        generation++
        drawing = true
    }

    override fun input(index: Int): BackdropImageInput {
        check(drawing) { "Primitive inputs are available only during drawing" }
        return images(index, generation)
    }
}

internal class PrimitiveImage(
    override val bounds: Rect,
    private val context: PrimitiveContext,
    private val generation: Int,
    private val layer: () -> GraphicsLayer,
) : BackdropImageInput {
    override fun draw(scope: DrawScope) {
        check(context.drawing && generation == context.generation) {
            "Primitive input views expire after drawing"
        }
        with(scope) {
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) { drawLayer(layer()) }
        }
    }

    override fun runtimeShaderEffect(
        shader: RuntimeShader,
        uniformName: String,
        origin: Offset,
    ): RenderEffect {
        check(context.drawing && generation == context.generation) {
            "Primitive input views expire after drawing"
        }
        check(isRuntimeShaderSupported()) {
            "Runtime shaders are required for filtered primitive inputs"
        }
        require(origin.x.isFinite() && origin.y.isFinite()) {
            "Primitive input origin must be finite"
        }
        val storageBounds = bounds.translate(origin)
        require(
            storageBounds.left.isFinite() &&
                storageBounds.top.isFinite() &&
                storageBounds.right.isFinite() &&
                storageBounds.bottom.isFinite(),
        ) {
            "Primitive input bounds must be finite"
        }
        return createRuntimeShaderEffect(shader, uniformName, storageBounds)
    }
}

internal class PrimitiveResources(private val context: () -> GraphicsContext) :
    BackdropRenderResources {
    private val layers = mutableSetOf<GraphicsLayer>()
    private val shaders = RuntimeShaderCacheImpl()
    private val pools = mutableListOf<BackdropResourcePool<*, *>>()
    private var closed = false
    private var cleaning = false

    override fun createGraphicsLayer(): GraphicsLayer {
        check(!closed && !cleaning) { "Primitive resources are unavailable during release" }
        return context().createGraphicsLayer().also { layers += it }
    }

    override fun releaseGraphicsLayer(layer: GraphicsLayer) {
        check(!closed && layers.remove(layer)) {
            "Only a live layer owned by this primitive can be released"
        }
        context().releaseGraphicsLayer(layer)
    }

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        check(!closed && !cleaning) { "Primitive resources are unavailable during release" }
        return shaders.obtainRuntimeShader(key, string)
    }

    override fun <K : Any, V : Any> resourcePool(
        dispose: (V) -> Unit,
        create: BackdropRenderResources.(K) -> V,
    ): BackdropResourcePool<K, V> {
        check(!closed && !cleaning) { "Primitive resources are unavailable during release" }
        return BackdropResourcePool({ PrimitiveResources(context) }, create, dispose).also {
            pools += it
        }
    }

    fun releaseLayers() = cleanup(refresh = true)

    fun close() {
        if (closed) return
        try {
            cleanup(refresh = false)
        } finally {
            closed = true
            pools.clear()
            shaders.clear()
        }
    }

    private fun cleanup(refresh: Boolean) {
        cleaning = true
        try {
            var failure: Throwable? = null
            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
            for (pool in pools) attempt { if (refresh) pool.refresh() else pool.close() }
            for (layer in layers.toList()) attempt { releaseGraphicsLayer(layer) }
            failure?.let { throw it }
        } finally {
            cleaning = false
        }
    }
}

internal interface PrimitiveInstance {
    fun matches(node: RenderImageNode.Primitive<*>): Boolean

    fun prepare(node: RenderImageNode.Primitive<*>, context: BackdropPrimitiveContext)

    fun draw(
        scope: DrawScope,
        node: RenderImageNode.Primitive<*>,
        context: BackdropPrimitiveDrawContext,
    )

    fun refresh()

    fun close()
}

internal fun <P : BackdropPrimitive<P>> RenderImageNode.Primitive<P>.createInstance(
    graphics: () -> GraphicsContext,
): PrimitiveInstance {
    val type = primitive::class
    val key = primitive.rendererKey
    val resources = PrimitiveResources(graphics)
    val renderer =
        try {
            primitive.createRenderer(resources)
        } catch (failure: Throwable) {
            resources.close()
            throw failure
        }
    return object : PrimitiveInstance {
        override fun matches(node: RenderImageNode.Primitive<*>): Boolean =
            node.primitive::class == type && node.primitive.rendererKey == key

        @Suppress("UNCHECKED_CAST")
        private fun parameters(node: RenderImageNode.Primitive<*>): P {
            check(matches(node)) { "Primitive renderer type and key must match its configuration" }
            return node.primitive as P
        }

        override fun prepare(
            node: RenderImageNode.Primitive<*>,
            context: BackdropPrimitiveContext,
        ) {
            renderer.prepare(parameters(node), context)
        }

        override fun draw(
            scope: DrawScope,
            node: RenderImageNode.Primitive<*>,
            context: BackdropPrimitiveDrawContext,
        ) {
            renderer.draw(scope, parameters(node), context)
        }

        override fun refresh() {
            try {
                renderer.refresh()
            } finally {
                resources.releaseLayers()
            }
        }

        override fun close() {
            try {
                renderer.release()
            } finally {
                resources.close()
            }
        }
    }
}
