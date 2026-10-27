package top.ltfan.backdrop

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import top.ltfan.backdrop.internal.createRuntimeShaderEffect

/**
 * A stateful drawing for graph composition and surface phases. Mutable configuration is
 * snapshot-backed. Bounds describe finite output in surface pixels, including antialiasing. The
 * renderer draws into its caller's destination; algorithms needing construction layers create them
 * through [BackdropRenderResources].
 */
@Stable
public interface BackdropDecoration<P : BackdropDecoration<P>> {
    /**
     * Equal keys within one configuration class reuse compatible renderer state. Keys have
     * immutable equality and hash behavior.
     */
    public val rendererKey: Any
        get() = Unit

    public fun bounds(geometry: BackdropGeometry): Rect = geometry.surfaceBounds

    /**
     * Declares the processed source footprint for this drawing, including interpolation neighbors.
     * Null selects a drawing with no processed-source input. The processed domain remains finite;
     * samples beyond it are transparent.
     */
    public fun sampling(geometry: BackdropGeometry): BackdropSampling? = null

    public fun createRenderer(resources: BackdropRenderResources): BackdropDecorationRenderer<P>
}

/** Per-receiver state for direct drawing, with the same resource lifecycle as graph primitives. */
public interface BackdropDecorationRenderer<P : BackdropDecoration<P>> {
    /** Observes preparation inputs after source sampling and output bounds are resolved. */
    public fun prepare(decoration: P, context: BackdropDecorationContext) {}

    /** Draws within the declared output bounds. Borrowed inputs expire when this call returns. */
    public fun draw(scope: DrawScope, decoration: P, context: BackdropDecorationDrawContext)

    /**
     * Drops layer references before the engine releases them; shader namespaces remain retained.
     */
    public fun refresh() {}

    /** Releases value-owned state before the engine closes the resource owner. */
    public fun release() {}
}

@BackdropRenderDsl
public sealed interface BackdropDecorationContext : BackdropGeometry {
    public val extension: BackdropExtension
    public val outputBounds: Rect
    /** Declared source footprint, or null when the decoration has no processed-source input. */
    public val inputBounds: Rect?
}

@BackdropRenderDsl
public sealed interface BackdropDecorationDrawContext : BackdropDecorationContext {
    /** Caller-selected source; surface phases supply the clipped input before surface painting. */
    public val processedSource: BackdropImageInput
}

internal class SurfaceDecoration<P : BackdropDecoration<P>>(val value: P)

internal class DecorationContext(
    private val geometry: BackdropGeometry,
    override val extension: BackdropExtension,
    override val outputBounds: Rect,
    override val inputBounds: Rect?,
    private val source: () -> BackdropImageInput,
) : BackdropDecorationDrawContext, BackdropGeometry by geometry {
    var drawing = false
    var generation = 0
    override val processedSource: BackdropImageInput
        get() {
            check(drawing) { "Processed source access belongs to decoration drawing" }
            check(inputBounds != null) { "Processed source access requires a sampling declaration" }
            val invocation = generation
            return DecorationInput(this, invocation, source)
        }
}

private class DecorationInput(
    private val context: DecorationContext,
    private val generation: Int,
    private val source: () -> BackdropImageInput,
) : BackdropImageInput {
    private fun input(): BackdropImageInput {
        check(context.drawing && context.generation == generation) {
            "Decoration inputs expire after drawing"
        }
        return source()
    }

    override val bounds: Rect
        get() {
            input()
            return checkNotNull(context.inputBounds)
        }

    override fun draw(scope: DrawScope) {
        val source = input()
        val bounds = bounds
        if (!bounds.overlaps(source.bounds)) return
        with(scope) {
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) { source.draw(this) }
        }
    }

    override fun runtimeShaderEffect(
        shader: RuntimeShader,
        uniformName: String,
        origin: Offset,
    ): RenderEffect {
        input()
        check(isRuntimeShaderSupported()) {
            "Runtime shaders are required for filtered decoration inputs"
        }
        return createRuntimeShaderEffect(
            shader,
            uniformName,
            bounds.translate(origin),
            context.outputBounds.translate(origin),
        )
    }
}
