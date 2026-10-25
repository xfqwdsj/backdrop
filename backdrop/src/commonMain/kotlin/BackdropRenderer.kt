package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
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
import androidx.compose.ui.util.packInts
import androidx.compose.ui.util.unpackInt1
import androidx.compose.ui.util.unpackInt2
import kotlin.math.ceil
import kotlin.math.floor
import top.ltfan.backdrop.internal.SurfaceClipPath
import top.ltfan.backdrop.internal.chain
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.internal.clipToExtendedShape
import top.ltfan.backdrop.internal.extendedSurfaceClipBounds
import top.ltfan.backdrop.internal.inverseSourceTransform

/**
 * Evaluates image declarations through recorded layers and platform effects.
 *
 * The caller owns snapshot observation, frame invalidation and the graphics context. Use this
 * renderer on the context's drawing thread, and close it before replacing that context. Resolve a
 * declaration and call [beginFrame] before drawing. Begin another frame when source or drawing
 * inputs change. Each image is recorded once per frame. [close] releases native resources; a later
 * [resolve] can reuse the renderer. Modifier-based rendering supplies window coordinates and
 * interaction lighting.
 */
public class BackdropRenderer(private val context: () -> GraphicsContext) : AutoCloseable {
    private var resolved = false
    private var frameReady = false
    private val outlinePath = SurfaceClipPath()
    private val layers = mutableMapOf<Int, GraphicsLayer>()
    private val primitives = mutableMapOf<Int, PrimitiveInstance>()
    private val primitiveNeedsPrepare = mutableSetOf<Int>()
    private val primitiveContexts = mutableMapOf<Int, PrimitiveContext>()
    private lateinit var primitiveGeometry: PrimitiveGeometry
    internal var interactionFrame = ProcessedBackdropFrame()

    private class EffectScope : BackdropEffectScopeImpl() {
        var resolvedShape: Shape = RectangleShape
        override val shape: Shape
            get() = resolvedShape
    }

    private val effects = mutableMapOf<Int, EffectScope>()
    private var compositePaint: Paint? = null
    private var graph: List<RenderImageNode> = emptyList()
    private val domains = mutableListOf<Rect>()
    private var requests = arrayOfNulls<Rect>(0)
    private var recorded = BooleanArray(0)
    private var aliases = IntArray(0)
    private var reachable = BooleanArray(0)
    private var storageOrigins = LongArray(0)
    private var storageSizes = LongArray(0)
    private var consumers = IntArray(0)
    private var filterInputs = IntArray(0)
    private var filterRequests = arrayOfNulls<Rect>(0)
    private var filterEffects = arrayOfNulls<RenderEffect>(0)
    private var inlinedSources = BooleanArray(0)
    private var inlineDrawing = BooleanArray(0)
    private var fused = BooleanArray(0)
    private var resolvedScope: BackdropRenderScope? = null
    private val scope: BackdropRenderScope
        get() = checkNotNull(resolvedScope) { "Resolve a declaration before accessing its images" }

    /** Sampling and decoration padding resolved around the declaration's measured surface. */
    public var extension: BackdropExtension = BackdropExtension.None
        private set

    private var surfaceRegion: Rect = Rect.Zero
    internal var visible: Rect = Rect.Zero
        private set

    /**
     * Seals [scope] and resolves its outputs for finite, non-inverted surface-pixel [requested]
     * bounds. Call [beginFrame] after resolution. Re-resolve after geometry or effect inputs
     * change. Drawing inputs need only [beginFrame]. A failed resolution requires a successful
     * resolution before drawing again.
     */
    public fun resolve(scope: BackdropRenderScope, requested: Rect) {
        resolved = false
        frameReady = false
        require(
            requested.left.isFinite() &&
                requested.top.isFinite() &&
                requested.right.isFinite() &&
                requested.bottom.isFinite() &&
                requested.width.isFinite() &&
                requested.height.isFinite() &&
                requested.left <= requested.right &&
                requested.top <= requested.bottom,
        ) {
            "Requested bounds must be finite and non-inverted"
        }
        scope.finishDeclaration()
        this.resolvedScope = scope
        graph = scope.graph
        if (recorded.size != graph.size) {
            recorded = BooleanArray(graph.size)
            reachable = BooleanArray(graph.size)
            aliases = IntArray(graph.size)
            storageOrigins = LongArray(graph.size)
            storageSizes = LongArray(graph.size)
            consumers = IntArray(graph.size)
            filterInputs = IntArray(graph.size)
            filterRequests = arrayOfNulls(graph.size)
            filterEffects = arrayOfNulls(graph.size)
            inlinedSources = BooleanArray(graph.size)
            fused = BooleanArray(graph.size)
            inlineDrawing = BooleanArray(graph.size)
        }
        reachable.fill(false)
        fun visit(index: Int) {
            if (reachable[index]) return
            reachable[index] = true
            when (val node = graph[index]) {
                is RenderImageNode.Isolation -> visit(node.input)
                is RenderImageNode.DeferredPrimitive ->
                    node.possibleInputs.forEach { visit(it.index) }
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
        val effectIterator = effects.iterator()
        while (effectIterator.hasNext()) {
            val entry = effectIterator.next()
            val index = entry.key
            if (
                index in graph.indices &&
                    reachable[index] &&
                    graph[index] is RenderImageNode.Process
            )
                continue
            val effect = entry.value
            effectIterator.remove()
            effect.reset()
        }
        var coverage = requested
        for ((index, node) in graph.withIndex()) {
            if (reachable[index] && node is RenderImageNode.Process) {
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
        val clipBounds =
            if (graph.indices.any { reachable[it] && graph[it] is RenderImageNode.SurfaceClip })
                extendedSurfaceClipBounds(
                        scope.shape,
                        scope.size,
                        BackdropExtension(
                            -coverage.left,
                            -coverage.top,
                            coverage.right - scope.size.width,
                            coverage.bottom - scope.size.height,
                        ),
                        scope.density,
                        scope.layoutDirection,
                    )
                    .also(::validate)
            else Rect.Zero
        primitiveGeometry =
            PrimitiveGeometry(
                scope.density,
                scope.size,
                scope.shape,
                scope.layoutDirection,
                coverage,
            )
        graph = graph.mapIndexed { index, node ->
            if (reachable[index] && node is RenderImageNode.DeferredPrimitive)
                node.resolve(primitiveGeometry)
            else node
        }
        // Primitive output bounds describe visible pixels independently of their input footprints.
        domains.clear()
        for ((index, node) in graph.withIndex()) {
            domains +=
                if (!reachable[index]) coverage
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
                        is RenderImageNode.Isolation -> domains[node.input]
                        is RenderImageNode.Mask -> domains[node.input]
                        is RenderImageNode.Clip -> domains[node.input]
                        is RenderImageNode.Transform -> {
                            inverseSourceTransform(node.matrix, Matrix())
                            node.matrix.map(domains[node.input])
                        }
                        else -> coverage
                    }
        }
        visible = domains[scope.current.index]
        validate(visible)
        if (requests.size == graph.size) requests.fill(null)
        else requests = arrayOfNulls(graph.size)
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
                is RenderImageNode.Isolation -> request(node.input, output)
                is RenderImageNode.DrawWithContent ->
                    request(node.input, node.sampling.requiredInput(output))
                is RenderImageNode.SurfaceClip ->
                    request(node.input, intersection(output, clipBounds.inflate(1f)))
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
        for ((index, effect) in effects) {
            if (requests[index] != null) effect.buildEffects(extension)
        }
        for (index in graph.indices) {
            val requestedOutput = requests[index] ?: continue
            val node = graph[index]
            val output =
                if (node is RenderImageNode.SurfaceClip)
                    intersection(requestedOutput, clipBounds.inflate(1f))
                else requestedOutput
            val filtered = node is RenderImageNode.Process
            // Native effects retain the shared filter coordinate space. Other nodes only store
            // their requested pixels, with integer origins preserving surface-local sampling.
            val left = if (filtered) -extension.left else floor(output.left)
            val top = if (filtered) -extension.top else floor(output.top)
            val right = if (filtered) scope.size.width + extension.right else ceil(output.right)
            val bottom = if (filtered) scope.size.height + extension.bottom else ceil(output.bottom)
            storageOrigins[index] = packInts(left.toInt(), top.toInt())
            storageSizes[index] = packInts(ceil(right - left).toInt(), ceil(bottom - top).toInt())
        }
        // Identity stages and SrcOver draws with alpha 1 over transparent bases share input
        // storage.
        for (index in graph.indices) {
            aliases[index] = index
            if (requests[index] == null) continue
            val input =
                when (val node = graph[index]) {
                    is RenderImageNode.Process ->
                        if (effects.getValue(index).renderEffect == null) node.input else index
                    is RenderImageNode.Composite ->
                        if (
                            graph[node.destination] == RenderImageNode.Empty &&
                                node.alpha == 1f &&
                                node.blendMode == BlendMode.SrcOver
                        )
                            node.source
                        else index
                    else -> index
                }
            aliases[index] = aliases[input]
        }
        resolveFilterRecordings()
        val layerIterator = layers.iterator()
        while (layerIterator.hasNext()) {
            val entry = layerIterator.next()
            val index = entry.key
            if (
                index in graph.indices &&
                    requests[index] != null &&
                    aliases[index] == index &&
                    !fused[index] &&
                    !inlineDrawing[index]
            )
                continue
            val layer = entry.value
            layerIterator.remove()
            context().releaseGraphicsLayer(layer)
        }
        primitiveContexts.clear()
        val primitiveIterator = primitives.iterator()
        while (primitiveIterator.hasNext()) {
            val entry = primitiveIterator.next()
            val index = entry.key
            if (
                index in graph.indices &&
                    requests[index] != null &&
                    graph[index] is RenderImageNode.Primitive<*> &&
                    !intersection(requests[index]!!, domains[index]).isEmpty
            )
                continue
            val primitive = entry.value
            primitiveIterator.remove()
            primitive.close()
        }
        for (index in graph.indices) {
            if (requests[index] == null) continue
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
                            layers.getValue(aliases[id])
                        }
                    },
                    interactionFrame,
                )
            primitiveContexts[index] = primitiveContext
            preparePrimitive(index, node, primitiveContext)
        }
        resolved = true
    }

    private fun resolveFilterRecordings() {
        consumers.fill(0)
        fused.fill(false)
        inlinedSources.fill(false)
        filterEffects.fill(null)
        filterRequests.fill(null)
        fun use(index: Int) {
            consumers[aliases[index]]++
        }
        // Output selections are readers too: a filter observed by foreground or export must
        // retain a separately drawable image even when its graph edge has one consumer.
        use(scope.current.index)
        scope.foregroundImage?.let { use(it.index) }
        scope.exportedImage?.let { use(it.index) }
        for (index in graph.indices) {
            if (requests[index] == null || aliases[index] != index) continue
            when (val node = graph[index]) {
                is RenderImageNode.Isolation -> use(node.input)
                is RenderImageNode.DrawWithContent -> use(node.input)
                is RenderImageNode.SurfaceClip -> use(node.input)
                is RenderImageNode.ShapeClip -> use(node.input)
                is RenderImageNode.Paint -> node.input?.let(::use)
                is RenderImageNode.Process -> use(node.input)
                is RenderImageNode.Composite -> {
                    use(node.destination)
                    use(node.source)
                }
                is RenderImageNode.Mask -> {
                    use(node.input)
                    use(node.mask)
                }
                is RenderImageNode.Clip -> use(node.input)
                is RenderImageNode.Transform -> use(node.input)
                is RenderImageNode.Primitive<*> -> node.inputs.forEach { use(it.image.index) }
                else -> Unit
            }
        }
        for (index in graph.indices) {
            if (requests[index] == null || aliases[index] != index) continue
            val node = graph[index] as? RenderImageNode.Process ?: continue
            val own = effects.getValue(index)
            val input = aliases[node.input]
            filterInputs[index] = input
            filterRequests[index] = own.samplingBounds
            filterEffects[index] = own.renderEffect
            val previous = filterEffects[input]
            if (previous != null && consumers[input] == 1) {
                filterInputs[index] = filterInputs[input]
                filterRequests[index] = filterRequests[input]
                filterEffects[index] = previous.chain(checkNotNull(own.renderEffect))
                fused[input] = true
            }
            val source = filterInputs[index]
            if (graph[source] === RenderImageNode.Source && consumers[source] == 1) {
                inlinedSources[index] = true
                fused[source] = true
            }
        }
        inlineDrawing.fill(false)
        val imageInputs = BooleanArray(graph.size)
        fun retain(index: Int) {
            imageInputs[aliases[index]] = true
        }
        retain(scope.current.index)
        scope.foregroundImage?.let { retain(it.index) }
        scope.exportedImage?.let { retain(it.index) }
        for (index in graph.indices) {
            if (requests[index] == null) continue
            when (val node = graph[index]) {
                is RenderImageNode.Primitive<*> -> node.inputs.forEach { retain(it.image.index) }
                is RenderImageNode.DrawWithContent -> retain(node.input)
                is RenderImageNode.Mask -> {
                    retain(node.input)
                    retain(node.mask)
                }
                else -> Unit
            }
        }
        for (index in graph.indices) {
            if (
                requests[index] == null ||
                    aliases[index] != index ||
                    imageInputs[index] ||
                    consumers[index] != 1 ||
                    fused[index]
            )
                continue
            inlineDrawing[index] =
                when (val node = graph[index]) {
                    RenderImageNode.Empty,
                    RenderImageNode.Source -> true
                    is RenderImageNode.SurfaceClip,
                    is RenderImageNode.ShapeClip,
                    is RenderImageNode.Clip,
                    is RenderImageNode.Transform -> true
                    is RenderImageNode.Paint -> node.compositingStrategy == CompositingStrategy.Auto
                    is RenderImageNode.Primitive<*> ->
                        node.primitive.compositingStrategy == CompositingStrategy.Auto
                    is RenderImageNode.Composite ->
                        node.alpha == 1f && node.blendMode == BlendMode.SrcOver
                    else -> false
                }
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

    /** Visible surface-pixel bounds of [image], or the declaration's background when omitted. */
    public fun outputBounds(image: BackdropImage? = null): Rect = domains[imageIndex(image)]

    internal fun outputBounds(index: Int): Rect = domains[index]

    private fun imageIndex(image: BackdropImage?): Int {
        check(resolved) { "Resolve a declaration before accessing its images" }
        val selected = image ?: scope.current
        scope.validateImage(selected)
        require(requests[selected.index] != null) {
            "Image must be reachable from a selected output"
        }
        return selected.index
    }

    /**
     * Records a selected background, foreground or exported [image] and its dependencies. Recording
     * occurs in surface coordinates without presenting the result on [target].
     */
    public fun prepare(
        target: DrawScope,
        image: BackdropImage? = null,
        source: DrawScope.(Rect) -> Unit,
    ) {
        val index = imageIndex(image)
        requireOutput(index)
        check(frameReady) { "Begin a frame after resolving or refreshing the declaration" }
        requireTarget(target)
        if (domains[index].isEmpty || checkNotNull(requests[index]).isEmpty) return
        prepare(target, index, source)
    }

    /**
     * Presents a selected background, foreground or exported [image] in surface coordinates.
     * [source] draws requested source pixels in the same coordinates; it receives the full sampling
     * footprint and may be invoked for shared inputs. Target density and layout direction must
     * match the declaration's geometry.
     */
    public fun draw(
        target: DrawScope,
        image: BackdropImage? = null,
        source: DrawScope.(Rect) -> Unit,
    ) {
        val index = imageIndex(image)
        requireOutput(index)
        check(frameReady) { "Begin a frame after resolving or refreshing the declaration" }
        requireTarget(target)
        if (domains[index].isEmpty || checkNotNull(requests[index]).isEmpty) return
        draw(target, index, source)
    }

    private fun requireOutput(index: Int) {
        require(
            index == scope.current.index ||
                index == scope.foregroundImage?.index ||
                index == scope.exportedImage?.index,
        ) {
            "Select the image as the background, foreground or export before resolving"
        }
    }

    private fun requireTarget(target: DrawScope) {
        require(
            target.density == scope.density.density &&
                target.fontScale == scope.density.fontScale &&
                target.layoutDirection == scope.layoutDirection,
        ) {
            "Drawing density and layout direction must match the resolved declaration"
        }
    }

    /** Invalidates recorded images while retaining geometry, effects and reusable resources. */
    public fun beginFrame() {
        check(resolved) { "Resolve a declaration before beginning a frame" }
        recorded.fill(false)
        frameReady = true
    }

    internal fun prepare(
        target: DrawScope,
        index: Int,
        source: DrawScope.(Rect) -> Unit,
    ) {
        render(index, target, source)
    }

    internal fun draw(
        target: DrawScope,
        index: Int,
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
        val selected = aliases[index]
        if (selected != index) return render(selected, target, source)
        if (recorded[index]) return layers.getValue(index)
        val node = graph[index]
        prepareInputs(index, target, source)
        val layer =
            layers.getOrPut(index) {
                context().createGraphicsLayer()
            }
        val strategy =
            when (node) {
                is RenderImageNode.SurfaceClip,
                is RenderImageNode.ShapeClip,
                is RenderImageNode.Clip,
                is RenderImageNode.Transform -> CompositingStrategy.Auto
                is RenderImageNode.Paint -> node.compositingStrategy
                is RenderImageNode.Composite ->
                    if (node.blendMode == BlendMode.SrcOver) CompositingStrategy.Auto
                    else CompositingStrategy.Offscreen
                is RenderImageNode.Primitive<*> -> node.primitive.compositingStrategy
                else -> CompositingStrategy.Offscreen
            }
        // Geometry wrappers and associative SrcOver composition preserve their children's
        // isolation and recorded clipping commands.
        layer.compositingStrategy = strategy
        layer.clip = false
        val request =
            if (node is RenderImageNode.Process) checkNotNull(filterRequests[index])
            else requests[index]!!
        val placement = storageOrigins[index]
        layer.topLeft = IntOffset(unpackInt1(placement), unpackInt2(placement))
        layer.alpha = 1f
        layer.blendMode = BlendMode.SrcOver
        val dimensions = storageSizes[index]
        val storage = IntSize(unpackInt1(dimensions), unpackInt2(dimensions))
        layer.renderEffect = if (node is RenderImageNode.Process) filterEffects[index] else null
        layer.record(scope.density, scope.layoutDirection, storage) {
            val ds =
                object : DrawScope by this {
                    override val size: Size
                        get() = scope.size
                }
            with(this) {
                val origin = storageOrigins[index]
                translate(-unpackInt1(origin).toFloat(), -unpackInt2(origin).toFloat()) {
                    clipRect(request.left, request.top, request.right, request.bottom) {
                        val storageSize = drawContext.size
                        drawContext.size = scope.size
                        try {
                            drawNode(index, ds, source)
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

    private fun prepareInputs(index: Int, target: DrawScope, source: DrawScope.(Rect) -> Unit) {
        val node = graph[index]
        fun prepareNode(id: Int) {
            val selected = aliases[id]
            if (inlineDrawing[selected]) prepareInputs(selected, target, source)
            else render(selected, target, source)
        }
        when (node) {
            is RenderImageNode.Isolation -> prepareNode(node.input)
            is RenderImageNode.DrawWithContent -> prepareNode(node.input)
            is RenderImageNode.SurfaceClip -> prepareNode(node.input)
            is RenderImageNode.ShapeClip -> prepareNode(node.input)
            is RenderImageNode.Paint -> node.input?.let(::prepareNode)
            is RenderImageNode.Process ->
                if (!inlinedSources[index]) prepareNode(filterInputs[index])
            is RenderImageNode.Composite -> {
                prepareNode(node.destination)
                prepareNode(node.source)
            }
            is RenderImageNode.Mask -> {
                prepareNode(node.input)
                prepareNode(node.mask)
            }
            is RenderImageNode.Clip -> prepareNode(node.input)
            is RenderImageNode.Transform -> prepareNode(node.input)
            is RenderImageNode.Primitive<*> ->
                node.inputs.forEach {
                    if (requests[it.image.index] != null) prepareNode(it.image.index)
                }
            else -> Unit
        }
    }

    private fun DrawScope.drawNode(
        index: Int,
        ds: DrawScope,
        source: DrawScope.(Rect) -> Unit,
    ) {
        val request =
            if (graph[index] is RenderImageNode.Process) checkNotNull(filterRequests[index])
            else checkNotNull(requests[index])
        fun drawImage(id: Int, alpha: Float = 1f, mode: BlendMode = BlendMode.SrcOver) {
            val selected = aliases[id]
            val isolated = alpha != 1f || mode != BlendMode.SrcOver
            val canvas = drawContext.canvas
            if (isolated) {
                val paint = compositePaint ?: Paint().also { compositePaint = it }
                paint.alpha = alpha
                paint.blendMode = mode
                canvas.saveLayer(checkNotNull(requests[id]), paint)
            }
            val domain = checkNotNull(requests[id])
            try {
                clipRect(domain.left, domain.top, domain.right, domain.bottom) {
                    if (inlineDrawing[selected]) drawNode(selected, ds, source)
                    else drawLayer(layers.getValue(selected))
                }
            } finally {
                if (isolated) canvas.restore()
            }
        }
        when (val node = graph[index]) {
            RenderImageNode.Source -> source(request)
            RenderImageNode.Empty -> Unit
            is RenderImageNode.DeferredPrimitive ->
                error("Primitive declarations resolve before drawing")
            is RenderImageNode.Isolation -> drawImage(node.input)
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
                    outlinePath,
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
            is RenderImageNode.Process ->
                if (inlinedSources[index]) source(request) else drawImage(filterInputs[index])
            is RenderImageNode.Composite -> {
                drawImage(node.destination)
                drawImage(node.source, node.alpha, node.blendMode)
            }
            is RenderImageNode.Mask -> {
                drawImage(node.input)
                drawImage(
                    node.mask,
                    mode = if (node.inverse) BlendMode.DstOut else BlendMode.DstIn,
                )
            }
            is RenderImageNode.Clip -> clipPath(node.path) { drawImage(node.input) }
            is RenderImageNode.Transform ->
                withTransform({ transform(node.matrix) }) {
                    drawImage(node.input)
                }
            is RenderImageNode.Primitive<*> -> {
                val primitiveContext = primitiveContexts[index]
                if (primitiveContext != null) {
                    if (primitives[index] == null || index in primitiveNeedsPrepare)
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
                            primitives.getValue(index).draw(ds, node, primitiveContext)
                        }
                    } finally {
                        primitiveContext.drawing = false
                    }
                }
            }
        }
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

    /** Releases recorded layers and refreshes primitives while retaining resolved geometry. */
    public fun refresh() {
        frameReady = false
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

    /** Releases renderer-owned resources. Resolve a declaration before using the renderer again. */
    public override fun close() {
        resolved = false
        frameReady = false
        var failure: Throwable? = null
        fun dispose(block: () -> Unit) {
            try {
                block()
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        if (layers.isNotEmpty()) {
            dispose {
                val graphics = context()
                layers.values.forEach { layer -> dispose { graphics.releaseGraphicsLayer(layer) } }
            }
            layers.clear()
        }
        dispose { closePrimitives() }
        primitiveContexts.clear()
        primitiveNeedsPrepare.clear()
        effects.values.forEach { effect -> dispose { effect.reset() } }
        effects.clear()
        filterEffects.fill(null)
        filterRequests.fill(null)
        recorded.fill(false)
        resolvedScope = null
        graph = emptyList()
        interactionFrame.clear()
        extension = BackdropExtension.None
        visible = Rect.Zero
        failure?.let { throw it }
    }
}

private fun union(a: Rect, b: Rect): Rect {
    if (a.isEmpty) return b
    if (b.isEmpty || a === b) return a
    val left = minOf(a.left, b.left)
    val top = minOf(a.top, b.top)
    val right = maxOf(a.right, b.right)
    val bottom = maxOf(a.bottom, b.bottom)
    return when {
        a.hasBounds(left, top, right, bottom) -> a
        b.hasBounds(left, top, right, bottom) -> b
        else -> Rect(left, top, right, bottom)
    }
}

// Raw-bit comparisons retain the union's signed-zero coordinates when reusing immutable bounds.
private fun Rect.hasBounds(left: Float, top: Float, right: Float, bottom: Float): Boolean =
    this.left.toRawBits() == left.toRawBits() &&
        this.top.toRawBits() == top.toRawBits() &&
        this.right.toRawBits() == right.toRawBits() &&
        this.bottom.toRawBits() == bottom.toRawBits()

private fun validate(rect: Rect) {
    require(
        rect.left.isFinite() &&
            rect.top.isFinite() &&
            rect.right.isFinite() &&
            rect.bottom.isFinite() &&
            rect.width.isFinite() &&
            rect.height.isFinite() &&
            rect.left <= rect.right &&
            rect.top <= rect.bottom,
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
