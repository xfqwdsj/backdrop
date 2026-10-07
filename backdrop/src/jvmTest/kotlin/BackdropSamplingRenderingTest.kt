package top.ltfan.backdrop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.skiaImageFilter
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.effects.lens
import top.ltfan.backdrop.effects.progressiveBlur
import top.ltfan.backdrop.effects.vibrancy

class BackdropSamplingRenderingTest {
    @Test
    fun derivedBlurSupportPreservesOpaqueEdgesAcrossRadiiAndTileModes() {
        for (radius in listOf(0.25f, 1f, 4f, 8f, 16f, 32f, 64f)) {
            for (mode in
                listOf(TileMode.Decal, TileMode.Clamp, TileMode.Repeated, TileMode.Mirror)) {
                assertOpaque { blur(radius, mode) }
            }
        }
    }

    @Test
    fun composedBlurAndRefractionPreserveOpaqueInput() {
        assertOpaque {
            blur(16f, TileMode.Decal)
            blur(16f, TileMode.Decal)
        }
        assertOpaque {
            vibrancy()
            blur(32f, TileMode.Decal)
            lens(32f, 0.256f, depthEffect = true)
        }
        assertOpaque {
            lens(32f, 100f, chromaticAberration = true)
            blur(16f, TileMode.Decal)
        }
        assertOpaque {
            blur(16f, TileMode.Decal)
            lens(32f, 100f, chromaticAberration = true)
        }
    }

    @Test
    fun progressiveBlurKeepsOpaqueEdgesWithoutRenormalizingClippedTaps() {
        for (edge in
            listOf(BackdropEdge.Top, BackdropEdge.Bottom, BackdropEdge.Start, BackdropEdge.End)) {
            assertOpaque { progressiveBlur(16f, edge) }
        }
    }

    @Test
    fun samplingRoomPreservesGenuineSourceTransparency() {
        val minimum =
            render(0.375f) {
                blur(16f, TileMode.Decal)
                lens(32f, 1f)
            }
        assertTrue(minimum in 0.373f..0.377f, "Source alpha must remain 0.375, got $minimum")
    }

    private fun assertOpaque(effects: BackdropEffectScope.() -> Unit) {
        val minimum = render(1f, effects)
        assertTrue(minimum >= 0.999f, "Opaque source developed a transparent edge: $minimum")
    }

    private fun render(alpha: Float, effects: BackdropEffectScope.() -> Unit): Float {
        val scope =
            object : BackdropEffectScopeImpl() {
                override val shape: Shape = RoundedCornerShape(64.dp)
            }
        scope.size = Size(256f, 256f)
        scope.resolveEffects(effects)
        val room = scope.extension
        val width = ceil(256f + room.left + room.right).toInt()
        val height = ceil(256f + room.top + room.bottom).toInt()
        val info =
            ImageInfo(width, height, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        Surface.makeRaster(info).use { surface ->
            Paint().use { effectPaint ->
                effectPaint.imageFilter = requireNotNull(scope.renderEffect).skiaImageFilter
                surface.canvas.saveLayer(
                    Canvas.SaveLayerRec(
                        paint = effectPaint,
                        saveLayerFlags =
                            Canvas.SaveLayerFlags(Canvas.SaveLayerFlagsSet.F16ColorType),
                    )
                )
                Paint().use { source ->
                    source.color = org.jetbrains.skia.Color.WHITE
                    source.setAlphaf(alpha)
                    surface.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), source)
                }
                surface.canvas.restore()
            }
            Bitmap().use { bitmap ->
                assertTrue(bitmap.allocPixels(info))
                assertTrue(surface.readPixels(bitmap, 0, 0))
                var minimum = alpha
                for (y in room.top.toInt() until room.top.toInt() + 256) {
                    for (x in room.left.toInt() until room.left.toInt() + 256) {
                        minimum = minOf(minimum, bitmap.getAlphaf(x, y))
                    }
                }
                return minimum
            }
        }
    }
}
