package top.ltfan.backdrop.internal

import androidx.compose.ui.graphics.Paint
import top.ltfan.backdrop.RuntimeShader

internal expect fun Paint.blur(radius: Float)

/** Per-side support for the mask blur configured by [Paint.blur]. */
internal expect fun maskBlurSupport(radius: Float): Float

internal expect fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?)

/** Sets an extended-range paint color without packing RGB into ARGB8. */
internal expect fun Paint.setHdrColor(color: androidx.compose.ui.graphics.Color)
