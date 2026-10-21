package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import top.ltfan.backdrop.BackdropEffectScopeImpl
import top.ltfan.backdrop.BackdropExtension
import top.ltfan.backdrop.BackdropRenderScope
import top.ltfan.backdrop.BackdropRoom
import top.ltfan.backdrop.PrimitiveContext
import top.ltfan.backdrop.PrimitiveGeometry
import top.ltfan.backdrop.PrimitiveImage
import top.ltfan.backdrop.PrimitiveInstance
import top.ltfan.backdrop.ProcessedBackdropFrame
import top.ltfan.backdrop.RenderImageNode
import top.ltfan.backdrop.createInstance

/** Evaluates a same-frame graph entirely through recorded layers and platform effects. */
internal class BackdropGraphRenderer(private val context: () -> GraphicsContext) {
    private val outlinePath = Path()
    private val layers = mutableMapOf<Int, GraphicsLayer>()
    private val primitives = mutableMapOf<Int, PrimitiveInstance>()
    private val primitiveNeedsPrepare = mutableSetOf<Int>()
    private val primitiveContexts = mutableMapOf<Int, PrimitiveContext>()
    private lateinit var primitiveGeometry: PrimitiveGeometry
    var interactionFrame = ProcessedBackdropFrame()

    private class EffectScope : BackdropEffectScopeImpl() {
        var resolvedShape: Shape = RectangleShape
        override val shape: Shape
            get() = resolvedShape
    }

    private val effects = mutableMapOf<Int, EffectScope>()
    private var compositePaint: Paint? = null
    private var graph: List<RenderImageNode> = emptyList()
    private var domains: List<Rect> = emptyList()
    private var requests = arrayOfNulls<Rect>(0)
    private var recorded = BooleanArray(0)
    private lateinit var scope: BackdropRenderScope
    var extension: BackdropExtension = BackdropExtension.None
        private set

    private var surfaceRegion: Rect = Rect.Zero
    var visible: Rect = Rect.Zero
        private set

    fun resolve(scope: BackdropRenderScope, requested: Rect) {
        scope.finishDeclaration()
        this.scope = scope
        graph = scope.graph.toList()
        if (recorded.size != graph.size) recorded = BooleanArray(graph.size)
        val reachable = mutableSetOf<Int>()
        fun visit(index: Int) {
            if (!reachable.add(index)) return
            when (val node = graph[index]) {
                is RenderImageNode.DrawWithContent -> visit(node.input)
                is RenderImageNode.SurfaceClip -> visit(node.input)
                is RenderImageNode.ShapeClip -> visit(node.input)
                is RenderImageNode.Paint -> node.input?.let(::visit)
                is RenderImageNode.Process -> visit(node.input)
                is RenderImageNode.Composite -> {
                    visit(node.destination)
                    visit(node.source)
                }
                is RenderImageNode.Mask -> {
                    visit(node.input)
                    visit(node.mask)
                }
                is RenderImageNode.Clip -> visit(node.input)
                is RenderImageNode.Transform -> visit(node.input)
                is RenderImageNode.Primitive<*> -> node.inputs.forEach { visit(it.image.index) }
                else -> Unit
            }
        }
        visit(scope.current.index)
        scope.foregroundImage?.let { visit(it.index) }
        scope.exportedImage?.let { visit(it.index) }
        effects.keys
            .toList()
            .filter { it !in reachable || graph[it] !is RenderImageNode.Process }
            .forEach { effects.remove(it)!!.reset() }
        var coverage = requested
        for ((index, node) in graph.withIndex()) {
            if (index in reachable && node is RenderImageNode.Process) {
                val effectScope = effects.getOrPut(index) { EffectScope() }
                effectScope.resolvedShape = scope.shape
                effectScope.size = scope.size
                effectScope.density = scope.density.density
                effectScope.fontScale = scope.density.fontScale
                effectScope.layoutDirection = scope.layoutDirection
                effectScope.prepareEffects(node.effects)
                effects[index] = effectScope
                with(scope.density) {
                    coverage =
                        union(
                            coverage,
                            Rect(
                                requested.left - effectScope.cover.left.toPx(),
                                requested.top - effectScope.cover.top.toPx(),
                                requested.right + effectScope.cover.right.toPx(),
                                requested.bottom + effectScope.cover.bottom.toPx(),
                            ),
                        )
                }
            }
        }
        surfaceRegion = coverage
        primitiveGeometry =
            PrimitiveGeometry(
                scope.density,
                scope.size,
                scope.shape,
                scope.layoutDirection,
                coverage,
            )
        // Primitive output bounds describe visible pixels independently of their input footprints.
        val domains = mutableListOf<Rect>()
        for ((index, node) in graph.withIndex()) {
            domains +=
                if (index !in reachable) coverage
                else
                    when (node) {
                        RenderImageNode.Empty -> Rect.Zero
                        is RenderImageNode.Primitive<*> ->
                            node.primitive.bounds(primitiveGeometry).also(::validate)
                        is RenderImageNode.Composite ->
                            union(domains[node.destination], domains[node.source])
                        is RenderImageNode.Paint ->
                            node.input?.let { union(domains[it], coverage) } ?: coverage
                        is RenderImageNode.Process -> union(domains[node.input], coverage)
                        is RenderImageNode.Mask -> domains[node.input]
                        is RenderImageNode.Clip -> domains[node.input]
                        is RenderImageNode.Transform -> {
                            inverseSourceTransform(node.matrix, Matrix())
                            node.matrix.map(domains[node.input])
                        }
                        else -> coverage
                    }
        }
        this.domains = domains
        visible = domains[scope.current.index]
        validate(visible)
        requests = arrayOfNulls(graph.size)
        request(scope.current.index, visible)
        scope.foregroundImage?.let { request(it.index, domains[it.index]) }
        scope.exportedImage?.let { request(it.index, domains[it.index]) }
        val primitiveSampling = mutableMapOf<Int, List<Rect>>()
        var bounds = visible
        for (index in graph.indices.reversed()) {
            val output = requests[index] ?: continue
            bounds = union(bounds, output)
            when (val node = graph[index]) {
                is RenderImageNode.Process -> {
                    val effectScope = effects.getValue(index)
                    effectScope.measureStages(output, scope.size.width, scope.size.height)
                    request(node.input, effectScope.samplingBounds)
                    bounds =
                        union(
                            bounds,
                            Rect(
                                -effectScope.extension.left,
                                -effectScope.extension.top,
                                scope.size.width + effectScope.extension.right,
                                scope.size.height + effectScope.extension.bottom,
                            ),
                        )
                }
                is RenderImageNode.DrawWithContent ->
                    request(node.input, node.sampling.requiredInput(output))
                is RenderImageNode.SurfaceClip -> request(node.input, output)
                is RenderImageNode.ShapeClip -> request(node.input, output)
                is RenderImageNode.Paint -> node.input?.let { request(it, output) }
                is RenderImageNode.Composite -> {
                    request(node.destination, output)
                    request(node.source, output)
                }
                is RenderImageNode.Mask -> {
                    request(node.input, output)
                    request(node.mask, output)
                }
                is RenderImageNode.Clip -> request(node.input, output)
                is RenderImageNode.Transform ->
                    request(
                        node.input,
                        inverseSourceTransform(node.matrix, Matrix()).map(output).inflate(1f),
                    )
                is RenderImageNode.Primitive<*> -> {
                    val region = intersection(output, domains[index])
                    if (!region.isEmpty) {
                        val required =
                            node.inputs.map { it.sampling.requiredInput(region).also(::validate) }
                        primitiveSampling[index] = required
                        node.inputs.forEachIndexed { i, input ->
                            request(input.image.index, required[i])
                        }
                    }
                }
                else -> Unit
            }
        }
        extension =
            BackdropExtension(
                ceil(maxOf(0f, -bounds.left)),
                ceil(maxOf(0f, -bounds.top)),
                ceil(maxOf(0f, bounds.right - scope.size.width)),
                ceil(maxOf(0f, bounds.bottom - scope.size.height)),
            )
        effects.filterKeys { requests[it] != null }.values.forEach { it.buildEffects(extension) }
        val live = graph.indices.filter { requests[it] != null }.toSet()
        layers.keys
            .toList()
            .filter { it !in live }
            .forEach {
                context().releaseGraphicsLayer(layers.remove(it)!!)
            }
        primitiveContexts.clear()
        primitives.keys
            .toList()
            .filter {
                it !in live ||
                    graph[it] !is RenderImageNode.Primitive<*> ||
                    intersection(requests[it]!!, domains[it]).isEmpty
            }
            .forEach { primitives.remove(it)!!.close() }
        for (index in live) {
            val node = graph[index] as? RenderImageNode.Primitive<*> ?: continue
            val requestedOutput = intersection(requests[index]!!, domains[index])
            if (requestedOutput.isEmpty) continue
            val inputBounds = primitiveSampling.getValue(index)
            val primitiveContext =
                PrimitiveContext(
                    primitiveGeometry,
                    extension,
                    domains[index],
                    requestedOutput,
                    inputBounds,
                    images = { input, generation ->
                        val id = node.inputs[input].image.index
                        PrimitiveImage(
                            inputBounds[input],
                            primitiveContexts.getValue(index),
                            generation,
                        ) {
                            layers.getValue(id)
                        }
                    },
                    interactionFrame,
                )
            primitiveContexts[index] = primitiveContext
            preparePrimitive(index, node, primitiveContext)
        }
    }

    private fun preparePrimitive(
        index: Int,
        node: RenderImageNode.Primitive<*>,
        context: PrimitiveContext,
    ) {
        var instance = primitives[index]
        if (instance != null && !instance.matches(node)) {
            primitives.remove(index)
            instance.close()
            instance = null
        }
        val selected = instance ?: node.createInstance(this.context).also { primitives[index] = it }
        try {
            selected.prepare(node, context)
            primitiveNeedsPrepare.remove(index)
        } catch (failure: Throwable) {
            primitives.remove(index)
            try {
                selected.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun outputBounds(index: Int): Rect = domains[index]

    fun beginFrame() {
        recorded.fill(false)
    }

    fun prepare(
        target: DrawScope,
        index: Int = scope.current.index,
        source: DrawScope.(Rect) -> Unit,
    ) {
        render(index, target, source)
    }

    fun draw(
        target: DrawScope,
        index: Int = scope.current.index,
        source: DrawScope.(Rect) -> Unit,
    ) {
        if (domains[index].isEmpty) return
        val layer = render(index, target, source)
        with(target) {
            val domain = domains[index]
            clipRect(domain.left, domain.top, domain.right, domain.bottom) {
                drawLayer(layer)
            }
        }
    }

    private fun render(
        index: Int,
        target: DrawScope,
        source: DrawScope.(Rect) -> Unit,
    ): GraphicsLayer {
        if (recorded[index]) return layers.getValue(index)
        val node = graph[index]
        fun input(id: Int): GraphicsLayer = render(id, target, source)
        // Record dependencies before their consumer; nesting recording callbacks is unsupported.
        when (node) {
            is RenderImageNode.DrawWithContent -> input(node.input)
            is RenderImageNode.SurfaceClip -> input(node.input)
            is RenderImageNode.ShapeClip -> input(node.input)
            is RenderImageNode.Paint -> node.input?.let(::input)
            is RenderImageNode.Process -> input(node.input)
            is RenderImageNode.Composite -> {
                input(node.destination)
                input(node.source)
            }
            is RenderImageNode.Mask -> {
                input(node.input)
                input(node.mask)
            }
            is RenderImageNode.Clip -> input(node.input)
            is RenderImageNode.Transform -> input(node.input)
            is RenderImageNode.Primitive<*> ->
                node.inputs.forEach {
                    if (requests[it.image.index] != null) input(it.image.index)
                }
            else -> Unit
        }
        val layer =
            layers.getOrPut(index) {
                context().createGraphicsLayer().apply {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            }
        layer.topLeft = IntOffset(-extension.left.toInt(), -extension.top.toInt())
        layer.alpha = 1f
        layer.blendMode = BlendMode.SrcOver
        val storage =
            IntSize(
                ceil(scope.size.width + extension.left + extension.right).toInt(),
                ceil(scope.size.height + extension.top + extension.bottom).toInt(),
            )
        layer.renderEffect =
            if (node is RenderImageNode.Process) effects.getValue(index).renderEffect else null
        val request =
            if (node is RenderImageNode.Process) effects.getValue(index).samplingBounds
            else requests[index]!!
        layer.record(scope.density, scope.layoutDirection, storage) {
            val ds =
                object : DrawScope by this {
                    override val size: Size
                        get() = scope.size
                }
            with(this) {
                translate(extension.left, extension.top) {
                    clipRect(request.left, request.top, request.right, request.bottom) {
                        fun drawImage(
                            id: Int,
                            alpha: Float = 1f,
                            mode: BlendMode = BlendMode.SrcOver,
                        ) {
                            val canvas = drawContext.canvas
                            val isolated = alpha != 1f || mode != BlendMode.SrcOver
                            if (isolated) {
                                val paint = compositePaint ?: Paint().also { compositePaint = it }
                                paint.alpha = alpha
                                paint.blendMode = mode
                                canvas.saveLayer(
                                    Rect(
                                        -extension.left,
                                        -extension.top,
                                        scope.size.width + extension.right,
                                        scope.size.height + extension.bottom,
                                    ),
                                    paint,
                                )
                            }
                            val image = layers.getValue(id)
                            val domain = requests[id]!!
                            clipRect(domain.left, domain.top, domain.right, domain.bottom) {
                                drawLayer(image)
                            }
                            if (isolated) canvas.restore()
                        }
                        // Brushes consult drawContext.size as well as DrawScope.size.
                        // Sampling room must not change surface-local drawing coordinates.
                        val storageSize = drawContext.size
                        drawContext.size = scope.size
                        try {
                            when (node) {
                                RenderImageNode.Source -> source(request)
                                RenderImageNode.Empty -> Unit
                                is RenderImageNode.DrawWithContent ->
                                    node.draw(
                                        ds,
                                        { drawImage(node.input) },
                                        BackdropRoom(scope.size, extension),
                                    )
                                is RenderImageNode.SurfaceClip -> {
                                    val coverage = surfaceRegion
                                    clipToExtendedShape(
                                        scope.shape,
                                        scope.size,
                                        BackdropExtension(
                                            -coverage.left,
                                            -coverage.top,
                                            coverage.right - scope.size.width,
                                            coverage.bottom - scope.size.height,
                                        ),
                                        Path(),
                                    ) {
                                        drawImage(node.input)
                                    }
                                }
                                is RenderImageNode.ShapeClip -> {
                                    val canvas = drawContext.canvas
                                    canvas.save()
                                    canvas.clipOutline(
                                        node.shape.createOutline(
                                            scope.size,
                                            scope.layoutDirection,
                                            scope.density,
                                        ),
                                        outlinePath,
                                    )
                                    drawImage(node.input)
                                    canvas.restore()
                                }
                                is RenderImageNode.Paint -> {
                                    node.input?.let { drawImage(it) }
                                    if (node.surfaceClip) {
                                        val coverage = surfaceRegion
                                        clipToExtendedShape(
                                            scope.shape,
                                            scope.size,
                                            BackdropExtension(
                                                -coverage.left,
                                                -coverage.top,
                                                coverage.right - scope.size.width,
                                                coverage.bottom - scope.size.height,
                                            ),
                                            outlinePath,
                                        ) {
                                            node.draw(ds, BackdropRoom(scope.size, extension))
                                        }
                                    } else node.draw(ds, BackdropRoom(scope.size, extension))
                                }
                                is RenderImageNode.Process -> drawImage(node.input)
                                is RenderImageNode.Composite -> {
                                    drawImage(node.destination)
                                    drawImage(node.source, node.alpha, node.blendMode)
                                }
                                is RenderImageNode.Mask -> {
                                    drawImage(node.input)
                                    drawImage(
                                        node.mask,
                                        mode =
                                            if (node.inverse) BlendMode.DstOut else BlendMode.DstIn,
                                    )
                                }
                                is RenderImageNode.Clip ->
                                    clipPath(node.path) { drawImage(node.input) }
                                is RenderImageNode.Transform ->
                                    withTransform({ transform(node.matrix) }) {
                                        drawImage(node.input)
                                    }
                                is RenderImageNode.Primitive<*> -> {
                                    val primitiveContext = primitiveContexts[index]
                                    if (primitiveContext != null) {
                                        if (
                                            primitives[index] == null ||
                                                index in primitiveNeedsPrepare
                                        )
                                            preparePrimitive(index, node, primitiveContext)
                                        primitiveContext.beginDraw()
                                        try {
                                            val output = primitiveContext.requestedBounds
                                            clipRect(
                                                output.left,
                                                output.top,
                                                output.right,
                                                output.bottom,
                                            ) {
                                                primitives
                                                    .getValue(index)
                                                    .draw(ds, node, primitiveContext)
                                            }
                                        } finally {
                                            primitiveContext.drawing = false
                                        }
                                    }
                                }
                            }
                        } finally {
                            drawContext.size = storageSize
                        }
                    }
                }
            }
        }
        recorded[index] = true
        return layer
    }

    private fun request(index: Int, rect: Rect) {
        validate(rect)
        requests[index] = requests[index]?.let { union(it, rect) } ?: rect
    }

    private fun closePrimitives() {
        var failure: Throwable? = null
        for (index in primitives.keys.toList()) {
            try {
                primitives.remove(index)!!.close()
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    fun refresh() {
        if (layers.isNotEmpty()) {
            val graphics = context()
            layers.values.forEach(graphics::releaseGraphicsLayer)
            layers.clear()
        }
        recorded.fill(false)
        primitives.forEach { (index, instance) ->
            instance.refresh()
            primitiveNeedsPrepare += index
        }
    }

    fun release() {
        if (layers.isNotEmpty()) {
            val graphics = context()
            layers.values.forEach(graphics::releaseGraphicsLayer)
            layers.clear()
        }
        closePrimitives()
        primitiveContexts.clear()
        primitiveNeedsPrepare.clear()
        effects.values.forEach { it.reset() }
        effects.clear()
        recorded.fill(false)
    }
}

private fun union(a: Rect, b: Rect): Rect =
    when {
        a.isEmpty -> b
        b.isEmpty -> a
        else ->
            Rect(
                minOf(a.left, b.left),
                minOf(a.top, b.top),
                maxOf(a.right, b.right),
                maxOf(a.bottom, b.bottom),
            )
    }

private fun validate(rect: Rect) {
    require(
        listOf(rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height).all {
            it.isFinite()
        } && rect.left <= rect.right && rect.top <= rect.bottom,
    ) {
        "Invalid render bounds: $rect"
    }
}

private fun intersection(a: Rect, b: Rect): Rect {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    return Rect(
        left,
        top,
        maxOf(left, minOf(a.right, b.right)),
        maxOf(top, minOf(a.bottom, b.bottom)),
    )
}
