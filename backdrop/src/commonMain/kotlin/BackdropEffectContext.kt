package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Read-only geometry and shader access for constructing one effect stage. Properties describe the
 * active build invocation and remain valid for that factory call. Shader cache keys are local to
 * each stage position, so stages have independent mutable uniforms.
 */
@BackdropRenderDsl
public sealed interface BackdropEffectContext : RuntimeShaderCache {
    public val density: Density
    public val size: Size
    public val shape: Shape
    public val layoutDirection: LayoutDirection
    /** Final storage extension shared by the consuming image graph. */
    public val extension: BackdropExtension
    /**
     * Required complete input texels for this stage, in surface-local pixels before storage
     * translation.
     */
    public val inputBounds: Rect
}
