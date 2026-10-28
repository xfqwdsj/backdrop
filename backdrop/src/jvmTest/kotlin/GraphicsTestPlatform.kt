package top.ltfan.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage as captureWindowImage
import androidx.compose.ui.unit.IntRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

@OptIn(InternalComposeUiApi::class)
internal actual fun initializeTestGraphics() {
    registerSkikoComposeImplementation()
}

internal actual fun renderTestShader(
    shader: RuntimeShader,
    width: Int,
    height: Int,
    premultiplied: Boolean,
    tiles: List<IntRect>?,
): FloatArray {
    val info =
        ImageInfo(
            width,
            height,
            ColorType.RGBA_F16,
            if (premultiplied) ColorAlphaType.PREMUL else ColorAlphaType.UNPREMUL,
            ColorSpace.sRGB,
        )
    Surface.makeRaster(info).use { surface ->
        surface.canvas.clear(0)
        Paint().use { paint ->
            shader.asSkikoRuntimeShader().makeShader().use { native ->
                paint.shader = native
                if (tiles != null) {
                    PathBuilder().use { clip ->
                        for ((left, top, right, bottom) in tiles) clip.addRect(
                            Rect(
                                left.toFloat(),
                                top.toFloat(),
                                right.toFloat(),
                                bottom.toFloat(),
                            ),
                        )
                        clip.snapshot().use { path ->
                            surface.canvas.clipPath(path, ClipMode.INTERSECT, false)
                        }
                    }
                }
                surface.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)
            }
        }
        Bitmap().use { bitmap ->
            check(bitmap.allocPixels(info))
            check(surface.readPixels(bitmap, 0, 0))
            val bytes = checkNotNull(bitmap.readPixels(info, width * 8))
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            return FloatArray(width * height * 4) {
                testHalfToFloat(buffer.short.toInt() and 0xffff)
            }
        }
    }
}

internal actual fun prepareRenderTestWindow(test: ComposeUiTest) {}

@Composable
internal actual fun RenderTestContent(content: @Composable () -> Unit) {
    content()
}

internal actual fun SemanticsNodeInteraction.captureToImage(): ImageBitmap = captureWindowImage()

internal actual val touchGesturesReuseComposePointerIds: Boolean = true

internal actual fun layeredBlurDifferenceLimits(): List<FloatArray> =
    listOf(
        floatArrayOf(.04f, .25f, .22f),
        floatArrayOf(.015f, .23f, .45f),
        floatArrayOf(.05f, .40f, .56f),
        floatArrayOf(.012f, .13f, .15f),
    )
