package top.ltfan.backdrop.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropImageScope
import top.ltfan.backdrop.BackdropPrimitive
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropPrimitiveGeometry
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropPrimitiveResources
import top.ltfan.backdrop.PrimitiveContext
import top.ltfan.backdrop.highlight.HighlightLinearColor
import top.ltfan.backdrop.highlight.toHighlightLinearExtendedSrgb
import top.ltfan.backdrop.internal.clipOutline
import top.ltfan.backdrop.primitive

/** Receiver settings for shared interaction light. */
@Stable
public sealed interface InteractionLighting {
    public companion object {
        @Stable public val Default: InteractionLighting = Config()
    }

    @Immutable public data object None : InteractionLighting

    @Immutable
    public data class Config(
        public val interiorOpacity: Float = 0.15f,
        public val contourOpacity: Float = 0.5f,
    ) : InteractionLighting {
        init {
            require(interiorOpacity.isFinite() && interiorOpacity in 0f..1f) {
                "Interior opacity must be finite and within 0..1"
            }
            require(contourOpacity.isFinite() && contourOpacity in 0f..1f) {
                "Contour opacity must be finite and within 0..1"
            }
        }
    }
}

/** Creates receiver settings for shared interaction light. */
public fun InteractionLighting(
    interiorOpacity: Float = 0.15f,
    contourOpacity: Float = 0.5f,
): InteractionLighting = InteractionLighting.Config(interiorOpacity, contourOpacity)

/** Provides a window-local registry for shared interaction lights. */
@Composable
public fun ProvideInteractionLighting(
    isolate: Boolean = true,
    content: @Composable () -> Unit,
) {
    val parent = LocalInteractionRegistry.current
    val registry = if (!isolate && parent != null) parent else remember { InteractionRegistry() }
    val ownsRegistry = isolate || parent == null
    DisposableEffect(registry, ownsRegistry) {
        onDispose { if (ownsRegistry) registry.clear() }
    }
    CompositionLocalProvider(LocalInteractionRegistry provides registry, content = content)
}

internal val LocalInteractionRegistry: ProvidableCompositionLocal<InteractionRegistry?> =
    compositionLocalOf {
        null
    }

internal class InteractionRegistry {
    val lights = mutableStateListOf<InteractionLightEntry>()

    fun clear() = lights.clear()
}

internal class InteractionLightEntry(
    val token: Any,
    val window: Any,
    x: Float,
    y: Float,
    radiusPx: Float,
    color: Color,
    falloff: BackdropCurve,
    intensity: Float,
) {
    var x: Float by mutableFloatStateOf(x)
    var y: Float by mutableFloatStateOf(y)
    var radiusPx: Float by mutableFloatStateOf(radiusPx)
    var color: Color by mutableStateOf(color)
        private set

    var linearColor: HighlightLinearColor by mutableStateOf(color.toHighlightLinearExtendedSrgb())
        private set

    var falloff: BackdropCurve by mutableStateOf(falloff)
    var intensity: Float by mutableFloatStateOf(intensity)

    fun updateColor(value: Color) {
        if (color == value) return
        color = value
        linearColor = value.toHighlightLinearExtendedSrgb()
    }
}

internal fun InteractionLightEntry.isFiniteForRendering(): Boolean {
    if (!x.isFinite() || !y.isFinite() || !radiusPx.isFinite() || !intensity.isFinite())
        return false
    val linear = linearColor
    return linear.red.isFinite() &&
        linear.green.isFinite() &&
        linear.blue.isFinite() &&
        linear.alpha.isFinite()
}

/** Draws interior interaction lighting with additive blending by default. */
public fun BackdropImageScope.interactionLight(
    config: InteractionLighting.Config,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.Plus,
) {
    primitive(InteractionPrimitive(config), alpha = alpha, blendMode = blendMode)
}

private data class InteractionPrimitive(val config: InteractionLighting.Config) :
    BackdropPrimitive<InteractionPrimitive> {
    override fun bounds(geometry: BackdropPrimitiveGeometry): Rect = geometry.surfaceBounds

    override fun createRenderer(
        resources: BackdropPrimitiveResources,
    ): BackdropPrimitiveRenderer<InteractionPrimitive> = Renderer()

    private class Renderer : BackdropPrimitiveRenderer<InteractionPrimitive> {
        private val renderer = InteractionFieldRenderer()
        private val path = Path()

        override fun draw(
            scope: DrawScope,
            primitive: InteractionPrimitive,
            context: BackdropPrimitiveDrawContext,
        ) {
            val frame = (context as PrimitiveContext).interactionFrame
            val coordinates = frame.receiverCoordinates ?: return
            if (primitive.config.interiorOpacity <= 0f) return
            val canvas = scope.drawContext.canvas
            canvas.save()
            try {
                canvas.clipOutline(
                    context.shape.createOutline(
                        context.size,
                        context.layoutDirection,
                        context.density,
                    ),
                    path,
                )
                renderer.draw(
                    scope,
                    coordinates,
                    frame.interactionLights,
                    primitive.config.interiorOpacity,
                )
            } finally {
                canvas.restore()
            }
        }

        override fun refresh() {
            renderer.release()
        }

        override fun release() {
            renderer.release()
        }
    }
}
