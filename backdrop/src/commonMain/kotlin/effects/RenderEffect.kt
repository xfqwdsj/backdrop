package top.ltfan.backdrop.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RenderEffect
import org.intellij.lang.annotations.Language
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.BackdropEffectScopeImpl
import top.ltfan.backdrop.BackdropSampling
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.addEffect
import top.ltfan.backdrop.internal.RuntimeShaderEffect
import top.ltfan.backdrop.isRenderEffectSupported
import top.ltfan.backdrop.isRuntimeShaderSupported
import top.ltfan.backdrop.nextRuntimeShaderEffectKey

/** Adds a platform render effect with its declared input sampling behavior. */
public fun BackdropEffectScope.effect(effect: RenderEffect, sampling: BackdropSampling) {
    if (!isRenderEffectSupported()) return
    addEffect(sampling) { effect }
}

/** Adds a runtime shader and declares the input region it samples for each output region. */
public fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    @Language("AGSL") shaderString: String,
    uniformShaderName: String,
    sampling: BackdropSampling,
    block: RuntimeShader.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) return
    val stageKey = nextRuntimeShaderEffectKey(key)
    addEffect(sampling) {
        RuntimeShaderEffect(
            runtimeShader = obtainRuntimeShader(stageKey, shaderString).apply(block),
            uniformShaderName = uniformShaderName,
            inputBounds =
                (this as BackdropEffectScopeImpl)
                    .samplingBounds
                    .translate(Offset(extension.left, extension.top)),
        )
    }
}
