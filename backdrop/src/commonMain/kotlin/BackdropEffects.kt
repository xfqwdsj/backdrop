package top.ltfan.backdrop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import top.ltfan.backdrop.effects.LayeredBlurPrimitive
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.gaussianSupport
import top.ltfan.backdrop.effects.progressiveBlur
import top.ltfan.backdrop.effects.progressiveBlurGeometry
import top.ltfan.backdrop.effects.validateProgressiveBlur

/**
 * Explicit blur algorithms. Algorithm selection and unsupported-platform policy belong to callers.
 */
@Immutable
public sealed interface BackdropBlur {
    @Immutable public data object None : BackdropBlur

    @Immutable
    public data class Uniform(
        public val radius: Dp,
        public val edgeTreatment: TileMode = TileMode.Clamp,
    ) : BackdropBlur {
        init {
            validateRadius(radius)
        }
    }

    @Immutable
    public data class Progressive(
        public val radius: Dp,
        public val edge: BackdropEdge,
        public val ramp: BackdropRamp = BackdropRamp(),
    ) : BackdropBlur {
        init {
            validateRadius(radius)
            validateProgressiveBlur(radius.value, ramp)
        }
    }

    @Immutable
    public data class LayeredProgressive(
        public val radius: Dp,
        public val edge: BackdropEdge,
        public val ramp: BackdropRamp = BackdropRamp(),
        public val levels: Int = 8,
    ) : BackdropBlur {
        init {
            validateRadius(radius)
            validateProgressiveBlur(radius.value, ramp)
            require(levels >= 2) { "Layered progressive blur needs at least two levels" }
        }
    }
}

private fun validateRadius(radius: Dp) {
    require(radius.value.isFinite() && radius.value >= 0f) {
        "Blur radius must be finite and non-negative"
    }
}

/**
 * Brightness, contrast and saturation use the same units as the native color-controls operation.
 */
@Immutable
public data class BackdropColorControls(
    public val brightness: Float = 0f,
    public val contrast: Float = 1f,
    public val saturation: Float = 1f,
) {
    public companion object {
        public val None: BackdropColorControls = BackdropColorControls()
    }

    init {
        require(brightness.isFinite() && contrast.isFinite() && saturation.isFinite()) {
            "Color controls must be finite"
        }
    }
}

/**
 * Lens distances use dp; supported shapes and platform behavior follow the pixel-based lens effect.
 */
@Immutable
public data class BackdropLens(
    public val refractionHeight: Dp,
    public val refractionAmount: Dp,
    public val depthEffect: Boolean = false,
    public val chromaticAberration: Boolean = false,
) {
    init {
        validateRadius(refractionHeight)
        validateRadius(refractionAmount)
    }

    public companion object {
        public val None: BackdropLens = BackdropLens(0.dp, 0.dp)
    }
}

/** Applies one explicit algorithm to the current image. */
public fun BackdropImageScope.blur(config: BackdropBlur): Unit = blur { config }

/**
 * Observes parameters during effect resolution. Switching between layered and filter algorithms
 * changes graph structure and redeclares a modifier graph. Uniform and shader-progressive blur
 * share the filter structure. Independent renderer hosts redeclare when switching that structure;
 * parameter changes within it reuse effects and compatible renderer resources.
 */
public fun BackdropImageScope.blur(config: () -> BackdropBlur) {
    val layered = blurTopology(config)
    if (!layered.value) {
        effects {
            check(!layered.value) { "Blur topology changed without redeclaration" }
            filterBlur(config())
        }
    } else layeredBlur(config, layered)
}

internal fun blurTopology(config: () -> BackdropBlur): State<Boolean> =
    derivedStateOf(structuralEqualityPolicy()) { config() is BackdropBlur.LayeredProgressive }

internal fun BackdropEffectScope.filterBlur(value: BackdropBlur) {
    when (value) {
        BackdropBlur.None -> Unit
        is BackdropBlur.Uniform -> blur(value.radius.toPx(), value.edgeTreatment)
        is BackdropBlur.Progressive -> progressiveBlur(value.radius.toPx(), value.edge, value.ramp)
        is BackdropBlur.LayeredProgressive -> error("Layered blur requires an image declaration")
    }
}

internal fun BackdropImageScope.layeredBlur(config: () -> BackdropBlur, layered: State<Boolean>) {
    effects {
        check(layered.value) { "Blur topology changed without redeclaration" }
        val value = config() as BackdropBlur.LayeredProgressive
        if (value.radius.value > 0f && isRenderEffectSupported()) {
            progressiveBlurGeometry(size, layoutDirection, value.edge, value.ramp)?.let {
                cover(it.coverage)
            }
        }
    }
    val original = current
    replace(
        image {
            deferredPrimitive(listOf(original)) { geometry ->
                val value = config() as BackdropBlur.LayeredProgressive
                val radius = with(geometry.density) { value.radius.toPx() }
                val rampGeometry =
                    with(geometry.density) {
                        progressiveBlurGeometry(
                            geometry.size,
                            geometry.layoutDirection,
                            value.edge,
                            value.ramp,
                        )
                    }
                if (radius == 0f || !isRenderEffectSupported() || rampGeometry == null) {
                    RenderImageNode.Primitive(
                        IdentityBlur,
                        listOf(BackdropPrimitiveInput(original)),
                    )
                } else {
                    val lastLevel =
                        ceil(maxOf(value.ramp.from, value.ramp.to) * (value.levels - 1)).toInt()
                    val support =
                        if (lastLevel == 0) 0f
                        else gaussianSupport(radius * lastLevel / (value.levels - 1))
                    RenderImageNode.Primitive(
                        LayeredBlurPrimitive(
                            radius,
                            value.ramp,
                            value.levels,
                            rampGeometry.start,
                            rampGeometry.end,
                        ),
                        listOf(
                            BackdropPrimitiveInput(
                                original,
                                BackdropSampling.outsets(support, support, support, support),
                            ),
                        ),
                    )
                }
            }
        },
    )
}

private data object IdentityBlur : BackdropPrimitive<IdentityBlur> {
    override val compositingStrategy = CompositingStrategy.Auto

    override fun bounds(geometry: BackdropGeometry) = geometry.surfaceBounds

    override fun createRenderer(resources: BackdropRenderResources) =
        object : BackdropPrimitiveRenderer<IdentityBlur> {
            override fun draw(
                scope: DrawScope,
                primitive: IdentityBlur,
                context: BackdropPrimitiveDrawContext,
            ) {
                context.input(0).draw(scope)
            }

            override fun refresh() {}
        }
}
