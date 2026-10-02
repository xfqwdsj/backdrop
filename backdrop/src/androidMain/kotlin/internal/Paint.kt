package top.ltfan.backdrop.internal

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativePaint
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.asAndroidRuntimeShader

internal actual fun Paint.blur(radius: Float) {
    this.nativePaint.maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
}

internal actual fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.nativePaint.shader = runtimeShader?.asAndroidRuntimeShader()
}

internal actual fun Paint.setHdrColor(color: Color) {
    this.color = color
}
