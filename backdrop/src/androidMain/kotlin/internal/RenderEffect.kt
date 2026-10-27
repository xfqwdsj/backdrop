package top.ltfan.backdrop.internal

import android.graphics.RenderEffect as AndroidRenderEffect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.asAndroidRuntimeShader

// Android's runtime-shader effect exposes no sampling radius or input-domain control. The
// executor records sampled predecessors as GPU images instead of composing their native filters.
internal actual val requiresSampledEffectInputImage: Boolean = true

@RequiresApi(Build.VERSION_CODES.S)
internal actual fun RenderEffect?.chain(other: RenderEffect): RenderEffect {
    return if (this != null) {
        AndroidRenderEffect.createChainEffect(
                other.asAndroidRenderEffect(),
                this.asAndroidRenderEffect(),
            )
            .asComposeRenderEffect()
    } else {
        other
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal actual fun createRuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String,
    inputBounds: Rect?,
    outputBounds: Rect?,
): RenderEffect {
    // Stage bounds are enforced by the executor's pixel-aligned input recording. This native
    // factory only binds that recording; its API accepts neither input nor output bounds.
    return AndroidRenderEffect.createRuntimeShaderEffect(
            runtimeShader.asAndroidRuntimeShader(),
            uniformShaderName,
        )
        .asComposeRenderEffect()
}

@RequiresApi(Build.VERSION_CODES.S)
internal actual fun createColorFilterEffect(
    renderEffect: RenderEffect?,
    colorFilter: ColorFilter,
): RenderEffect {
    return if (renderEffect != null) {
        AndroidRenderEffect.createColorFilterEffect(
                colorFilter.asAndroidColorFilter(),
                renderEffect.asAndroidRenderEffect(),
            )
            .asComposeRenderEffect()
    } else {
        AndroidRenderEffect.createColorFilterEffect(colorFilter.asAndroidColorFilter())
            .asComposeRenderEffect()
    }
}
