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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Configuration for an independently rendered image. Mutable properties must be snapshot-backed.
 * [P] is the implementing configuration type. Bounds and input sampling are deterministic within a
 * resolution. Coordinates use surface-local pixels. Input footprints include interpolation
 * neighbors.
 */
@Stable
public interface BackdropPrimitive<P : BackdropPrimitive<P>> {
    /**
     * Identifies compatible renderer state within this configuration class. Equal keys reuse the
     * renderer with updated parameters; different renderer implementations or generic type
     * arguments require different keys. Keys have immutable equality and hash behavior.
     */
    public val rendererKey: Any
        get() = Unit

    /** Conservative finite output bounds. Pixels outside these bounds are transparent. */
    public fun bounds(geometry: BackdropPrimitiveGeometry): Rect

    /** Creates one renderer for one reachable declaration in one receiving modifier. */
    public fun createRenderer(resources: BackdropPrimitiveResources): BackdropPrimitiveRenderer<P>
}

/**
 * Per-receiver state. Resource creation belongs in the factory or [prepare], not
 * [BackdropPrimitive.bounds].
 */
@BackdropRenderDsl
public interface BackdropPrimitiveRenderer<P : BackdropPrimitive<P>> {
    /** Runs under observation after final graph storage and all input requests are resolved. */
    public fun prepare(primitive: P, context: BackdropPrimitiveContext) {}

    /** Draws one isolated output. Input images and the context are valid only during this call. */
    public fun draw(scope: DrawScope, primitive: P, context: BackdropPrimitiveDrawContext)

    /**
     * Drops owned-layer references for a display refresh. The engine releases owned layers after
     * this callback and keeps shader-cache entries. Prepare runs before the next draw. A stateless
     * renderer implements an empty callback.
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
public sealed interface BackdropPrimitiveGeometry {
    public val density: Density
    public val size: Size
    public val shape: Shape
    public val layoutDirection: LayoutDirection
    /** Visible surface request, including caller insets and declared effect coverage. */
    public val surfaceBounds: Rect
}

/** Read-only preparation context. Read values during the invocation rather than retaining it. */
@BackdropRenderDsl
public sealed interface BackdropPrimitiveContext : BackdropPrimitiveGeometry {
    public val extension: BackdropExtension
    public val outputBounds: Rect
    public val requestedBounds: Rect
    public val inputBounds: List<Rect>
}

/** Same-frame access to declared dependencies. No rendered pixels are read back. */
@BackdropRenderDsl
public sealed interface BackdropPrimitiveDrawContext : BackdropPrimitiveContext {
    public fun input(index: Int): BackdropPrimitiveImage
}

/**
 * A borrowed, read-only image view. Draw it in surface coordinates or use it as one runtime-shader
 * input. The view is valid only in the active primitive draw invocation.
 */
public sealed interface BackdropPrimitiveImage {
    public val bounds: Rect

    public fun draw(scope: DrawScope)

    /**
     * Creates a platform effect for this input's sampling bounds. [origin] locates the surface
     * origin in the target layer. Runtime shaders must be supported; callers choose their fallback.
     */
    public fun runtimeShaderEffect(
        shader: RuntimeShader,
        uniformName: String,
        origin: Offset = Offset.Zero,
    ): RenderEffect
}

/**
 * Per-instance resources. Layers created here belong to this receiver and are automatically
 * released on omission, renderer-key replacement, refresh or detach, including failed
 * construction/preparation. Refresh releases layers after [BackdropPrimitiveRenderer.refresh] drops
 * its references and keeps shader-cache entries. A renderer may release an owned layer earlier;
 * borrowed and already released layers are rejected.
 */
public sealed interface BackdropPrimitiveResources : RuntimeShaderCache {
    public fun createGraphicsLayer(): GraphicsLayer

    public fun releaseGraphicsLayer(layer: GraphicsLayer)
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
