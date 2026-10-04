package top.ltfan.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * The surface an effect chain runs against: its size, shape, density, and how far past it the
 * effects reach. Effects read snapshot state and assign [renderEffect] and [padding] directly.
 */
public sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    /** The surface's own size. Effects work in the surface's space, which this describes. */
    public val size: Size

    /**
     * Room the effects reach past the surface, per side, already measured against what this chain
     * needs. A shader receives coordinates in the effect layer, whose origin sits this far before
     * the surface's, so an effect that maps coordinates adds [BackdropExtension.originInNode] to
     * work in the surface's own space.
     */
    public val extension: BackdropExtension

    public val layoutDirection: LayoutDirection

    public val shape: Shape

    /**
     * Extra room the effects need on every side, which the resolved extension takes as a floor: a
     * blur reads the pixels it blends, so it asks for its radius here. Zero when the effects need
     * nothing beyond the surface.
     */
    public var padding: Float

    /**
     * Room the effects cover past the surface on particular sides, which the resolved extension
     * takes as a floor. A ramp that reaches outside the surface asks for that reach here, so the
     * area the effects are drawn into follows the ramp instead of a second, separate measurement.
     */
    public var cover: BackdropInsets

    public var renderEffect: RenderEffect?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    private var tintEffectIndex = 0
    private var lightingEffectIndex = 0

    internal fun nextTintEffectKey(): String = "BackdropTint${tintEffectIndex++}"

    internal fun nextLightingEffectKey(): String = "BackdropLighting${lightingEffectIndex++}"

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var size: Size = Size.Unspecified
    override var extension: BackdropExtension = BackdropExtension.None
    override var padding: Float = 0f

    override var cover: BackdropInsets = BackdropInsets.None
    override var renderEffect: RenderEffect? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    fun update(scope: DrawScope): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = scope.size
        val newLayoutDirection = scope.layoutDirection

        val changed =
            newDensity != density ||
                newFontScale != fontScale ||
                newSize != size ||
                newLayoutDirection != layoutDirection

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        tintEffectIndex = 0
        lightingEffectIndex = 0
        padding = 0f
        renderEffect = null
        effects()
    }

    fun reset() {
        tintEffectIndex = 0
        lightingEffectIndex = 0
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        extension = BackdropExtension.None
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
    }
}
