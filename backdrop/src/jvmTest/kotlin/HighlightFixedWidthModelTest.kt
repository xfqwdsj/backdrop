package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightAngularCut
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightEmission
import top.ltfan.backdrop.highlight.HighlightSample
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.renderStaticHighlightPixels
import top.ltfan.backdrop.internal.FlattenedBoundaryGeometry

class HighlightFixedWidthModelTest : SkikoGraphicsTest() {
    @Test
    fun fixedWidthProfileFallsToZeroOutsideTheOutlineBand() {
        val geometry = FlattenedBoundaryGeometry(Path().apply { addRect(Rect(0f, 0f, 32f, 32f)) })
        val pixels =
            renderStaticHighlightPixels(
                geometry,
                Size(32f, 32f),
                widthPx = 2f,
                falloff = Highlight().falloff,
                direction = HighlightDirection.Uniform,
                style = HighlightStyle.Solid(Color.White),
                exclusions = emptySet(),
            )
        assertTrue(pixels[(0 * 32 + 16) * 4 + 3] > 0f)
        assertEquals(0f, pixels[(3 * 32 + 16) * 4 + 3], .001f)
    }

    @Test
    fun styleUsesNearestBoundaryCoordinates() {
        var calls = 0
        var observed: HighlightSample? = null
        val style =
            object : HighlightStyle {
                override val color: Color = Color.White
                override val shaderSource: String =
                    "half4 shadeHighlight(float2 position,float2 outwardNormal,float projection,half4 color){return color;} float highlightStrength(float2 position,float2 outwardNormal,float projection){return 1.0;}"

                override fun shade(sample: HighlightSample): HighlightEmission {
                    calls++
                    observed = sample
                    return HighlightEmission(Color.White)
                }
            }
        renderStaticHighlightPixels(
            FlattenedBoundaryGeometry(Path().apply { addRect(Rect(0f, 0f, 1f, 1f)) }),
            Size(1f, 1f),
            widthPx = 2f,
            falloff = Highlight().falloff,
            direction = HighlightDirection.Uniform,
            style = style,
            exclusions = emptySet(),
        )
        assertEquals(1, calls)
        val sample = requireNotNull(observed)
        assertTrue(
            sample.position.x == 0f ||
                sample.position.x == 1f ||
                sample.position.y == 0f ||
                sample.position.y == 1f,
        )
    }

    @Test
    fun subpixelWidthsScaleCoverageWithoutMovingTheContour() {
        val geometry = FlattenedBoundaryGeometry(Path().apply { addRect(Rect(0f, 0f, 4f, 4f)) })
        fun edgeAlpha(width: Float): Float =
            renderStaticHighlightPixels(
                geometry,
                Size(4f, 4f),
                widthPx = width,
                falloff = Highlight().falloff,
                direction = HighlightDirection.Uniform,
                style = HighlightStyle.Solid(Color.White),
                exclusions = emptySet(),
            )[(1 * 4 + 0) * 4 + 3]

        val quarterPixel = edgeAlpha(.25f)
        val halfPixel = edgeAlpha(.5f)
        assertTrue(quarterPixel > 0f)
        assertTrue(halfPixel > quarterPixel)
        assertEquals(.5f, quarterPixel / halfPixel, .02f)
    }

    @Test
    fun holesRemainUnlitAndInternalContourReceivesItsOwnBand() {
        val outer = Path().apply { addRect(Rect(0f, 0f, 12f, 12f)) }
        val hole = Path().apply { addRect(Rect(4f, 4f, 8f, 8f)) }
        val geometry =
            FlattenedBoundaryGeometry(Path().apply { op(outer, hole, PathOperation.Difference) })
        val pixels =
            renderStaticHighlightPixels(
                geometry,
                Size(12f, 12f),
                widthPx = 2f,
                falloff = Highlight().falloff,
                direction = HighlightDirection.Uniform,
                style =
                    HighlightStyle.Solid(Color(2f, 1f, .5f, colorSpace = ColorSpaces.ExtendedSrgb)),
                exclusions = setOf(HighlightAngularCut(270f)),
            )
        assertEquals(0f, pixels[(5 * 12 + 5) * 4 + 3], .001f)
        assertTrue(pixels[(5 * 12 + 3) * 4 + 3] > 0f)
    }
}
