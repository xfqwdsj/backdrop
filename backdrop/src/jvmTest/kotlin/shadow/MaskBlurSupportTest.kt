package top.ltfan.backdrop.shadow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.internal.maskBlurSupport

class MaskBlurSupportTest {
    @Test
    fun skiaSigmaIsExpandedByThreeSigmaAndAntialiasCoverage() {
        assertEquals(1f, maskBlurSupport(0f))
        assertEquals(4f, maskBlurSupport(1f))
        assertEquals(31f, maskBlurSupport(10f))
    }

    @Test
    fun supportContainsRasterizedFractionalAntialiasedShadows() {
        val info = ImageInfo(384, 384, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        for (radius in listOf(1f, 10f, 24f)) {
            val source = Rect(128.25f, 128.75f, 255.25f, 255.75f)
            val support = maskBlurSupport(radius)
            Surface.makeRaster(info).use { surface ->
                Paint().use { paint ->
                    paint.isAntiAlias = true
                    paint.color = org.jetbrains.skia.Color.WHITE
                    paint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius)
                    surface.canvas.drawRect(source, paint)
                }
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocPixels(info))
                    assertTrue(surface.readPixels(bitmap, 0, 0))
                    var drawn = false
                    for (y in 0 until info.height) for (x in 0 until info.width) {
                        if (bitmap.getAlphaf(x, y) > 0f) {
                            drawn = true
                            assertTrue(
                                x + 0.5f >= source.left - support &&
                                    x + 0.5f <= source.right + support
                            )
                            assertTrue(
                                y + 0.5f >= source.top - support &&
                                    y + 0.5f <= source.bottom + support
                            )
                        }
                    }
                    assertTrue(drawn)
                }
            }
        }
    }

    @Test
    fun invalidAndDisabledBlurRadiiKeepOnlyAntialiasCoverage() {
        assertEquals(1f, maskBlurSupport(-1f))
        assertEquals(1f, maskBlurSupport(Float.NaN))
        assertEquals(1f, maskBlurSupport(Float.POSITIVE_INFINITY))
    }
}
