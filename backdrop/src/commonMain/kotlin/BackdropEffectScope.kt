package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import top.ltfan.backdrop.effects.gaussianSupport
import top.ltfan.backdrop.internal.pixelAligned
import top.ltfan.backdrop.internal.requiresSampledEffectInputImage

/**
 * Declares ordered optical stages against the surface's size, shape, density and sampling room. The
 * backend executes those stages through native filters and any required intermediate images.
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
        val requiresInputImage: Boolean,
    )

    internal class Pass(var effect: RenderEffect, var inputBounds: Rect)

    internal val passes = mutableListOf<Pass>()
    internal val requiresInputImage: Boolean
        get() = stages.firstOrNull()?.requiresInputImage == true && requiresSampledEffectInputImage

    /** Retains a blur filter and its footprint for one positive-radius declaration slot. */
    internal class NativeBlur(private var radius: Float, private var edgeTreatment: TileMode) {
        var effect = BlurEffect(radius, radius, edgeTreatment)
            private set

        var sampling = sampling(radius)
            private set

        fun update(radius: Float, edgeTreatment: TileMode) {
            if (this.radius == radius && this.edgeTreatment == edgeTreatment) return
            this.radius = radius
            this.edgeTreatment = edgeTreatment
            effect = BlurEffect(radius, radius, edgeTreatment)
            sampling = sampling(radius)
        }

        private fun sampling(radius: Float): BackdropSampling {
            val support = gaussianSupport(radius)
            return BackdropSampling.outsets(support, support, support, support)
        }
    }

    private val nativeBlurs = mutableListOf<NativeBlur>()
    private var nativeBlurIndex = 0

    internal fun obtainNativeBlur(radius: Float, edgeTreatment: TileMode): NativeBlur {
        val index = nativeBlurIndex++
        val retained = nativeBlurs.getOrNull(index)
        if (retained != null) return retained.apply { update(radius, edgeTreatment) }
        return NativeBlur(radius, edgeTreatment).also { nativeBlurs += it }
    }

    private var tintEffectIndex = 0
    private var lightingEffectIndex = 0
    private var runtimeShaderEffectIndex = 0
    private val stages = mutableListOf<Stage>()
    private val compositions = mutableListOf<BackdropFilterComposition?>()

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

    internal var samplingBounds: Rect = Rect.Zero
        private set

    private val stageSamplingBounds = mutableListOf<Rect>()
    private var stageVisibleBounds: Rect = Rect.Zero
    internal val stageOutputBounds: Rect
        get() = stageSamplingBounds.getOrElse(contextStageIndex + 1) { stageVisibleBounds }

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    private var contextStageIndex = 0
    private var contextDensity: Density = Density(1f)
    internal val context: BackdropEffectContext = EffectContext()

    private inner class EffectContext : BackdropEffectContext {
        override val density: Density
            get() {
                if (
                    contextDensity.density != this@BackdropEffectScopeImpl.density ||
                        contextDensity.fontScale != this@BackdropEffectScopeImpl.fontScale
                ) {
                    contextDensity =
                        Density(
                            this@BackdropEffectScopeImpl.density,
                            this@BackdropEffectScopeImpl.fontScale,
                        )
                }
                return contextDensity
            }

        override val size: Size
            get() = this@BackdropEffectScopeImpl.size

        override val shape: Shape
            get() = this@BackdropEffectScopeImpl.shape

        override val layoutDirection: LayoutDirection
            get() = this@BackdropEffectScopeImpl.layoutDirection

        override val extension: BackdropExtension
            get() = resolvedExtension

        override val inputBounds: Rect
            get() = samplingBounds

        override fun obtainRuntimeShader(key: String, string: String): RuntimeShader =
            obtainStageRuntimeShader(contextStageIndex, key, string)
    }

    internal fun obtainStageRuntimeShader(index: Int, key: String, string: String): RuntimeShader =
        obtainRuntimeShader("Deferred#$index:$key", string)

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader =
        runtimeShaderCache.obtainRuntimeShader(key, string)

    override fun cover(insets: BackdropInsets) {
        require(
            insets.left.value.isFinite() &&
                insets.left.value >= 0f &&
                insets.top.value.isFinite() &&
                insets.top.value >= 0f &&
                insets.right.value.isFinite() &&
                insets.right.value >= 0f &&
                insets.bottom.value.isFinite() &&
                insets.bottom.value >= 0f,
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
        requiresInputImage: Boolean = false,
        factory: BackdropEffectScope.() -> RenderEffect,
    ) {
        stages += Stage(sampling, factory, requiresInputImage)
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
        prepareEffects(effects)

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

    internal fun prepareEffects(effects: BackdropEffectScope.() -> Unit) {
        tintEffectIndex = 0
        lightingEffectIndex = 0
        runtimeShaderEffectIndex = 0
        nativeBlurIndex = 0
        stages.clear()
        cover = BackdropInsets.None
        effects()
        while (nativeBlurs.size > nativeBlurIndex) nativeBlurs.removeAt(nativeBlurs.lastIndex)
    }

    private fun resolveStages(visible: Rect, width: Float, height: Float) {
        measureStages(visible, width, height)
        buildEffects(resolvedExtension)
    }

    internal fun measureStages(visible: Rect, width: Float, height: Float) {
        stageVisibleBounds = visible
        stageSamplingBounds.clear()
        val needed =
            stages.asReversed().fold(visible) { output, stage ->
                val requestedInput = stage.sampling.requiredInput(output)
                validate(requestedInput)
                // A downstream lookup reads complete predecessor texels. Propagate that raster
                // extent before evaluating an earlier stage's sampling, including remappings.
                val input = requestedInput.pixelAligned()
                stageSamplingBounds += input
                input
            }
        stageSamplingBounds.reverse()
        val bounds =
            stageSamplingBounds.fold(visible) { bounds, input ->
                val left = minOf(bounds.left, input.left)
                val top = minOf(bounds.top, input.top)
                val right = maxOf(bounds.right, input.right)
                val bottom = maxOf(bounds.bottom, input.bottom)
                when {
                    input.left.toRawBits() == left.toRawBits() &&
                        input.top.toRawBits() == top.toRawBits() &&
                        input.right.toRawBits() == right.toRawBits() &&
                        input.bottom.toRawBits() == bottom.toRawBits() -> input
                    bounds.left.toRawBits() == left.toRawBits() &&
                        bounds.top.toRawBits() == top.toRawBits() &&
                        bounds.right.toRawBits() == right.toRawBits() &&
                        bounds.bottom.toRawBits() == bottom.toRawBits() -> bounds
                    else -> Rect(left, top, right, bottom)
                }
            }
        resolvedExtension =
            BackdropExtension(
                left = ceil(maxOf(0f, -bounds.left)),
                top = ceil(maxOf(0f, -bounds.top)),
                right = ceil(maxOf(0f, bounds.right - width)),
                bottom = ceil(maxOf(0f, bounds.bottom - height)),
            )

        samplingBounds = needed
    }

    internal fun buildEffects(extension: BackdropExtension) {
        resolvedExtension = extension
        val needed = samplingBounds
        var passCount = 0
        while (compositions.size < stages.size) compositions += null
        while (compositions.size > stages.size) compositions.removeAt(compositions.lastIndex)
        var current: RenderEffect? = null
        var inputBounds = needed
        stages.forEachIndexed { index, stage ->
            if (current != null && stage.requiresInputImage && requiresSampledEffectInputImage) {
                setPass(passCount++, current, inputBounds)
                current = null
            }
            contextStageIndex = index
            samplingBounds = stageSamplingBounds[index]
            if (current == null) inputBounds = samplingBounds
            val effect = stage.factory(this)
            val previous = current
            current =
                if (previous == null) {
                    compositions[index] = null
                    effect
                } else {
                    val chain =
                        compositions[index]
                            ?: BackdropFilterComposition().also { compositions[index] = it }
                    chain.compose(previous, effect)
                }
        }
        current?.let { setPass(passCount++, it, inputBounds) }
        while (passes.size > passCount) passes.removeAt(passes.lastIndex)
        samplingBounds = needed
    }

    private fun setPass(index: Int, effect: RenderEffect, input: Rect) {
        val retained = passes.getOrNull(index)
        if (retained == null) passes += Pass(effect, input)
        else {
            retained.effect = effect
            retained.inputBounds = input
        }
    }

    private fun validate(rect: Rect) {
        require(
            rect.left.isFinite() &&
                rect.top.isFinite() &&
                rect.right.isFinite() &&
                rect.bottom.isFinite(),
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
        passes.clear()
        stages.clear()
        compositions.clear()
        stageSamplingBounds.clear()
        stageVisibleBounds = Rect.Zero
        samplingBounds = Rect.Zero
        runtimeShaderCache.clear()
        nativeBlurs.clear()
        nativeBlurIndex = 0
    }
}

internal fun BackdropEffectScope.addEffect(
    sampling: BackdropSampling,
    requiresInputImage: Boolean = false,
    factory: BackdropEffectScope.() -> RenderEffect,
) {
    (this as BackdropEffectScopeImpl).addEffect(sampling, requiresInputImage, factory)
}

internal fun BackdropEffectScope.nextRuntimeShaderEffectKey(key: String): String =
    (this as BackdropEffectScopeImpl).nextRuntimeShaderEffectKey(key)
