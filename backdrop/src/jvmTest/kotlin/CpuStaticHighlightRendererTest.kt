package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightExclusion
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.renderStaticHighlightPixels
import top.ltfan.backdrop.internal.GenericEnvironmentGeometry

class CpuStaticHighlightRendererTest {
    private val geometry =
        GenericEnvironmentGeometry(Path().apply { addRect(Rect(0f, 0f, 32f, 32f)) })

    @Test
    fun plainKeepsUniformWidthWhileDirectionalStyleUsesLocalNormals() {
        val plain = render(HighlightStyle.Plain(Color.White))
        val directional =
            render(HighlightStyle.Default(), direction = HighlightDirection(angle = 0f))
        assertTrue(plain[rgba(16, 0) + 3] > 0f)
        assertEquals(0f, directional[rgba(16, 0) + 3])
        assertTrue(directional[rgba(0, 16) + 3] > 0f)
    }

    @Test
    fun ambientKeepsDarkSideAlphaAndBrightSideColor() {
        val ambient = HighlightStyle.Ambient(Color.White.copy(alpha = 0.4f))
        val pixels = render(ambient, direction = HighlightDirection(angle = 90f))
        val top = rgba(16, 0)
        val bottom = rgba(16, 31)
        assertEquals(0.3375f, pixels[top + 3], 0.01f)
        assertEquals(0f, pixels[top], 0.01f)
        assertTrue(pixels[bottom] > 0f)
    }

    @Test
    fun exclusionsMaskAfterBlurAndKeepHdrInFloatPixels() {
        val hdr =
            Color(
                2f,
                1f,
                0.5f,
                colorSpace = ColorSpaces.ExtendedSrgb,
            )
        val pixels =
            render(
                HighlightStyle.Plain(hdr),
                blur = 1.5f,
                exclusions = setOf(HighlightExclusion.Top),
            )
        assertEquals(0f, pixels[rgba(16, 0) + 3])
        assertTrue(pixels[rgba(0, 16)] > pixels[rgba(0, 16) + 3] + 0.1f)
    }

    private fun render(
        style: HighlightStyle,
        direction: HighlightDirection = HighlightDirection(),
        blur: Float = 0f,
        exclusions: Set<HighlightExclusion> = emptySet(),
    ): FloatArray =
        renderStaticHighlightPixels(
            geometry = geometry,
            size = Size(32f, 32f),
            width = 2f,
            blurRadius = blur,
            style = style,
            direction = direction,
            depthCurve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 1f),
            exclusions = exclusions,
        )

    private fun rgba(x: Int, y: Int): Int = (y * 32 + x) * 4
}
