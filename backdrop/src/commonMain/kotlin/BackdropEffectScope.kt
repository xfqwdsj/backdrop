package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import top.ltfan.backdrop.internal.chain

/**
 * The surface an effect chain runs against: its size, shape, density, and resolved sampling room.
 */
public sealed interface BackdropEffectScope : Density, RuntimeShaderCache {
    public val size: Size
    /** Final recording extension, available to deferred runtime shader uniform blocks. */
    public val extension: BackdropExtension
    public val layoutDirection: LayoutDirection
    public val shape: Shape
    public val cover: BackdropInsets

    /** Adds visible coverage beyond the surface. Requests from effects are combined per side. */
    public fun cover(insets: BackdropInsets)
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {
    private data class Stage(
        val sampling: BackdropSampling,
        val factory: BackdropEffectScope.() -> RenderEffect,
    )

    private var tintEffectIndex = 0
    private var lightingEffectIndex = 0
    private var runtimeShaderEffectIndex = 0
    private val stages = mutableListOf<Stage>()

    internal fun nextTintEffectKey(): String = "BackdropTint${tintEffectIndex++}"

    internal fun nextLightingEffectKey(): String = "BackdropLighting${lightingEffectIndex++}"

    internal fun nextRuntimeShaderEffectKey(key: String): String =
        "${key}#${runtimeShaderEffectIndex++}"

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var size: Size = Size.Unspecified
    private var resolvedExtension: BackdropExtension = BackdropExtension.None
    override val extension: BackdropExtension
        get() = resolvedExtension

    final override var cover: BackdropInsets = BackdropInsets.None
        private set

    internal var renderEffect: RenderEffect? = null
        private set

    internal var samplingBounds: Rect = Rect.Zero
        private set

    private val stageSamplingBounds = mutableListOf<Rect>()

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader =
        runtimeShaderCache.obtainRuntimeShader(key, string)

    override fun cover(insets: BackdropInsets) {
        require(
            listOf(insets.left, insets.top, insets.right, insets.bottom).all {
                it.value.isFinite() && it.value >= 0f
            }
        ) {
            "Coverage insets must be finite and non-negative: $insets"
        }
        cover =
            BackdropInsets(
                left = maxOf(cover.left, insets.left),
                top = maxOf(cover.top, insets.top),
                right = maxOf(cover.right, insets.right),
                bottom = maxOf(cover.bottom, insets.bottom),
            )
    }

    internal fun addEffect(
        sampling: BackdropSampling,
        factory: BackdropEffectScope.() -> RenderEffect,
    ) {
        stages += Stage(sampling, factory)
    }

    internal fun update(scope: DrawScope): Boolean {
        val changed =
            density != scope.density ||
                fontScale != scope.fontScale ||
                size != scope.size ||
                layoutDirection != scope.layoutDirection
        if (changed) {
            density = scope.density
            fontScale = scope.fontScale
            size = scope.size
            layoutDirection = scope.layoutDirection
        }
        return changed
    }

    internal fun resolveEffects(effects: BackdropEffectScope.() -> Unit) =
        resolveEffects(effects, BackdropInsets.None)

    internal fun resolveEffects(
        effects: BackdropEffectScope.() -> Unit,
        requested: BackdropInsets,
    ) {
        tintEffectIndex = 0
        lightingEffectIndex = 0
        runtimeShaderEffectIndex = 0
        stages.clear()
        cover = BackdropInsets.None
        renderEffect = null
        effects()

        val left = (requested.left + cover.left).toPx()
        val top = (requested.top + cover.top).toPx()
        val right = (requested.right + cover.right).toPx()
        val bottom = (requested.bottom + cover.bottom).toPx()
        val width = if (size.width.isFinite()) size.width else 0f
        val height = if (size.height.isFinite()) size.height else 0f
        val requestedVisible =
            Rect(
                if (left == 0f) 0f else -left,
                if (top == 0f) 0f else -top,
                width + right,
                height + bottom,
            )
        val visible =
            if (
                requestedVisible.left > requestedVisible.right ||
                    requestedVisible.top > requestedVisible.bottom
            )
                Rect.Zero
            else requestedVisible
        validate(visible)
        resolveStages(visible, width, height)
    }

    private fun resolveStages(visible: Rect, width: Float, height: Float) {
        stageSamplingBounds.clear()
        val needed =
            stages.asReversed().fold(visible) { output, stage ->
                val input = stage.sampling.requiredInput(output)
                validate(input)
                stageSamplingBounds += input
                input
            }
        stageSamplingBounds.reverse()
        val bounds =
            stageSamplingBounds.fold(visible) { bounds, input ->
                Rect(
                    minOf(bounds.left, input.left),
                    minOf(bounds.top, input.top),
                    maxOf(bounds.right, input.right),
                    maxOf(bounds.bottom, input.bottom),
                )
            }
        resolvedExtension =
            BackdropExtension(
                left = ceil(maxOf(0f, -bounds.left)),
                top = ceil(maxOf(0f, -bounds.top)),
                right = ceil(maxOf(0f, bounds.right - width)),
                bottom = ceil(maxOf(0f, bounds.bottom - height)),
            )

        renderEffect =
            stages.foldIndexed(null) { index, current, stage ->
                samplingBounds = stageSamplingBounds[index]
                current.chain(stage.factory(this))
            }
        samplingBounds = needed
    }

    private fun validate(rect: Rect) {
        require(
            rect.left.isFinite() &&
                rect.top.isFinite() &&
                rect.right.isFinite() &&
                rect.bottom.isFinite()
        ) {
            "Sampling regions must have finite bounds: $rect"
        }
        require(rect.left <= rect.right && rect.top <= rect.bottom) {
            "Sampling region is inverted: $rect"
        }
    }

    internal fun reset() {
        tintEffectIndex = 0
        lightingEffectIndex = 0
        runtimeShaderEffectIndex = 0
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        resolvedExtension = BackdropExtension.None
        layoutDirection = LayoutDirection.Ltr
        cover = BackdropInsets.None
        stages.clear()
        stageSamplingBounds.clear()
        renderEffect = null
        samplingBounds = Rect.Zero
        runtimeShaderCache.clear()
    }
}

internal fun BackdropEffectScope.addEffect(
    sampling: BackdropSampling,
    factory: BackdropEffectScope.() -> RenderEffect,
) {
    (this as BackdropEffectScopeImpl).addEffect(sampling, factory)
}

internal fun BackdropEffectScope.nextRuntimeShaderEffectKey(key: String): String =
    (this as BackdropEffectScopeImpl).nextRuntimeShaderEffectKey(key)

internal val BackdropEffectScope.renderEffect: RenderEffect?
    get() = (this as BackdropEffectScopeImpl).renderEffect
