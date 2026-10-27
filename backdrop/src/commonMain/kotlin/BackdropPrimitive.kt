package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Configuration for a graph image. Mutable properties must be snapshot-backed. [P] is the
 * implementing configuration type. Bounds and input sampling are deterministic within a resolution.
 * Coordinates use surface-local pixels. Input footprints include interpolation neighbors.
 */
@Stable
public interface BackdropPrimitive<P : BackdropPrimitive<P>> {
    /**
     * Offscreen preserves an isolated image for destination-dependent drawing. Auto permits SrcOver
     * composition directly into a single consumer; dependencies still retain images.
     */
    public val compositingStrategy: CompositingStrategy
        get() = CompositingStrategy.Offscreen

    /**
     * Identifies compatible renderer state within this configuration class. Equal keys reuse the
     * renderer with updated parameters; different renderer implementations or generic type
     * arguments require different keys. Keys have immutable equality and hash behavior.
     */
    public val rendererKey: Any
        get() = Unit

    /** Conservative finite output bounds. Pixels outside these bounds are transparent. */
    public fun bounds(geometry: BackdropGeometry): Rect

    /** Creates one primitive renderer per reachable declaration and [BackdropRenderer] instance. */
    public fun createRenderer(resources: BackdropRenderResources): BackdropPrimitiveRenderer<P>
}

/**
 * Per-receiver state. Resource creation belongs in the factory or [prepare], not
 * [BackdropPrimitive.bounds].
 */
@BackdropRenderDsl
public interface BackdropPrimitiveRenderer<P : BackdropPrimitive<P>> {
    /** Runs under observation after final graph storage and all input requests are resolved. */
    public fun prepare(primitive: P, context: BackdropPrimitiveContext) {}

    /**
     * Draws within the output bounds using this primitive’s compositing contract. Inputs and the
     * context are valid only during this call.
     */
    public fun draw(scope: DrawScope, primitive: P, context: BackdropPrimitiveDrawContext)

    /**
     * Drops owned-layer references for a display refresh. The engine releases owned layers after
     * this callback and keeps shader-cache entries. Resource pools invalidate their entry values
     * after this callback; retain the pool objects and drop separately cached value references.
     * Prepare runs before the next draw. An implementation with no separate transient state uses an
     * empty callback.
     */
    public fun refresh()

    /**
     * Releases renderer-owned resources before engine-owned layers and shader caches are cleared.
     */
    public fun release() {}
}

/** One explicit graph dependency and its complete input footprint for a requested output region. */
@Immutable
public data class BackdropPrimitiveInput(
    public val image: BackdropImage,
    public val sampling: BackdropSampling = BackdropSampling.Identity,
)

/** Geometry used to declare output bounds, independently of storage padding. */
@BackdropRenderDsl
public sealed interface BackdropGeometry {
    public val density: Density
    public val size: Size
    public val shape: Shape
    public val layoutDirection: LayoutDirection
    /** Visible surface request, including caller insets and declared effect coverage. */
    public val surfaceBounds: Rect
}

/** Read-only preparation context whose values are valid during the active invocation. */
@BackdropRenderDsl
public sealed interface BackdropPrimitiveContext : BackdropGeometry {
    public val extension: BackdropExtension
    public val outputBounds: Rect
    public val requestedBounds: Rect
    public val inputBounds: List<Rect>
}

/** Same-frame access to declared dependencies through native graphics resources. */
@BackdropRenderDsl
public sealed interface BackdropPrimitiveDrawContext : BackdropPrimitiveContext {
    public fun input(index: Int): BackdropImageInput
}

/**
 * A borrowed, read-only image view. Draw it in surface coordinates or use it as one runtime-shader
 * input. The view is valid only in the active renderer draw invocation.
 */
public sealed interface BackdropImageInput {
    public val bounds: Rect

    public fun draw(scope: DrawScope)

    /**
     * Creates a platform effect for this input and the active consumer's output domain. [origin]
     * locates the surface origin in the target layer. Record storage covering the required output
     * and input sampling footprint with the same translation. Runtime shaders must be supported;
     * callers choose their fallback and own any additional native effect stages.
     */
    public fun runtimeShaderEffect(
        shader: RuntimeShader,
        uniformName: String,
        origin: Offset = Offset.Zero,
    ): RenderEffect
}

/**
 * Per-instance resources. Layers created here belong to this receiver and are automatically
 * released on omission, renderer-key replacement, refresh or [BackdropRenderer.close], including
 * failed construction/preparation. Refresh releases layers after
 * [BackdropPrimitiveRenderer.refresh] or [BackdropDecorationRenderer.refresh] drops layer
 * references and keeps shader-cache entries. A renderer may release an owned layer earlier;
 * borrowed and already released layers are rejected.
 */
public sealed interface BackdropRenderResources : RuntimeShaderCache {
    public fun createGraphicsLayer(): GraphicsLayer

    public fun releaseGraphicsLayer(layer: GraphicsLayer)

    /**
     * Creates a pool owned by this renderer. Entry factories receive isolated resource owners;
     * [dispose] releases value-owned resources before automatic layer and shader cleanup. Keep the
     * pool in the renderer and update retained values when parameters change. Display refresh
     * invalidates values automatically while preserving each retained key's shader namespace.
     */
    public fun <K : Any, V : Any> resourcePool(
        dispose: (V) -> Unit = {},
        create: BackdropRenderResources.(K) -> V,
    ): BackdropResourcePool<K, V>
}

/** Draws a primitive at the declaration position, with external alpha and blend applied once. */
public fun <P : BackdropPrimitive<P>> BackdropImageScope.primitive(
    primitive: P,
    inputs: List<BackdropPrimitiveInput> = emptyList(),
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    val dependencies = inputs.toList()
    dependencies.forEach { checkImage(it.image) }
    drawPrimitive(RenderImageNode.Primitive(primitive, dependencies), alpha, blendMode)
}
