package top.ltfan.backdrop.effects

import androidx.compose.ui.graphics.RenderEffect
import org.intellij.lang.annotations.Language
import top.ltfan.backdrop.BackdropEffectScope
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.internal.RuntimeShaderEffect
import top.ltfan.backdrop.internal.chain
import top.ltfan.backdrop.isRenderEffectSupported
import top.ltfan.backdrop.isRuntimeShaderSupported

public fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    renderEffect = renderEffect.chain(effect)
}

public fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    @Language("AGSL") shaderString: String,
    uniformShaderName: String,
    block: RuntimeShader.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) return

    val effect =
        RuntimeShaderEffect(
            runtimeShader = obtainRuntimeShader(key, shaderString).apply(block),
            uniformShaderName = uniformShaderName,
        )
    renderEffect = renderEffect.chain(effect)
}
