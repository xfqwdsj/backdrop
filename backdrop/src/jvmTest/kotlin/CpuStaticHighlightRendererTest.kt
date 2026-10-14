package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightExclusion
import top.ltfan.backdrop.highlight.HighlightResponse
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.renderStaticHighlightPixels
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry

class CpuStaticHighlightRendererTest {
    private val geometry =
        FlattenedBoundaryGeometry(Path().apply { addRect(Rect(0f, 0f, 32f, 32f)) })

    @Test
    fun outputUsesOnlyConfiguredFixedWidth() {
        val pixels = render(HighlightStyle.Solid(Color.White))
        assertTrue(pixels[rgba(16, 1) + 3] > 0f)
        assertEquals(0f, pixels[rgba(16, 3) + 3], .001f)
    }

    @Test
    fun profileIncludesTheFullHalfPixelFootprintAtItsOuterEdge() {
        val pixels = render(HighlightStyle.Solid(Color.White), widthPx = 2.25f)

        assertTrue(pixels[rgba(16, 2) + 3] > 0f)
        assertEquals(0f, pixels[rgba(16, 3) + 3], .001f)
    }

    @Test
    fun twoToneBlackRetainsActionWithinFixedWidth() {
        val style = HighlightStyle.TwoTone(Color.White.copy(alpha = .4f))
        val pixels = render(style, direction = HighlightDirection(angle = 270f))
        val bottomEdge = rgba(16, 31)
        assertEquals(0f, pixels[bottomEdge], .001f)
        assertTrue(pixels[bottomEdge + 3] > 0f)
        assertEquals(0f, pixels[rgba(16, 28) + 3], .001f)
    }

    @Test
    fun exclusionsAndExtendedRgbApplyAtNearestBoundary() {
        val hdr = Color(2f, 1f, 0.5f, colorSpace = ColorSpaces.ExtendedSrgb)
        val pixels = render(HighlightStyle.Solid(hdr), exclusions = setOf(HighlightExclusion.Top))
        assertEquals(0f, pixels[rgba(16, 0) + 3])
        assertTrue(pixels[rgba(0, 16)] > pixels[rgba(0, 16) + 3] + 0.1f)
    }

    @Test
    fun directionScalesWidthIndependentlyFromColorStrength() {
        val style = HighlightStyle.Solid(Color.White)
        val thinSide =
            render(
                style,
                HighlightDirection(
                    angle = 270f,
                    mode = HighlightDirection.Mode.Single,
                    widthScale = HighlightResponse(.5f, .5f),
                ),
            )
        val wide =
            render(
                style,
                HighlightDirection(
                    angle = 270f,
                    mode = HighlightDirection.Mode.Single,
                    strength = HighlightResponse(.2f, .2f),
                    widthScale = HighlightResponse(2f, 2f),
                ),
            )
        val stronger =
            render(
                style,
                HighlightDirection(
                    angle = 270f,
                    mode = HighlightDirection.Mode.Single,
                    strength = HighlightResponse(1f, 1f),
                    widthScale = HighlightResponse(2f, 2f),
                ),
            )

        assertEquals(0f, thinSide[rgba(16, 1) + 3], .001f)
        assertTrue(wide[rgba(16, 1) + 3] > 0f)
        assertTrue(stronger[rgba(16, 1) + 3] > wide[rgba(16, 1) + 3])
        assertEquals(0f, wide[rgba(16, 4) + 3], .001f)
        assertEquals(0f, stronger[rgba(16, 4) + 3], .001f)
    }

    private fun render(
        style: HighlightStyle,
        direction: HighlightDirection = HighlightDirection.None,
        exclusions: Set<HighlightExclusion> = emptySet(),
        widthPx: Float = 2f,
    ): FloatArray =
        renderStaticHighlightPixels(
            geometry = geometry,
            size = Size(32f, 32f),
            widthPx = widthPx,
            falloff = Highlight().falloff,
            direction = direction,
            style = style,
            exclusions = exclusions,
        )

    private fun rgba(x: Int, y: Int): Int = (y * 32 + x) * 4
}
