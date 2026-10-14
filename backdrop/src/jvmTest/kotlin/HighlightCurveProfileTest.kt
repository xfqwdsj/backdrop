package top.ltfan.backdrop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightResponse

class HighlightCurveProfileTest {
    @Test
    fun directionDefaultsRetainSideWidthAndFullAlignedWidth() {
        val direction = HighlightDirection()
        assertEquals(.6f, direction.widthScale.transform(0f), .001f)
        assertEquals(1f, direction.widthScale.transform(1f), .001f)
        assertEquals(.35f, direction.strength.transform(0f), .001f)
        assertEquals(1f, direction.strength.transform(1f), .001f)
    }

    @Test
    fun widthAndColorStrengthCurvesRemainIndependent() {
        val direction =
            HighlightDirection(
                widthScale = HighlightResponse(.2f, 1.2f),
                strength = HighlightResponse(.4f, 1.5f),
            )
        assertEquals(.2f, direction.widthScale.transform(0f), .001f)
        assertEquals(1.2f, direction.widthScale.transform(1f), .001f)
        assertEquals(.4f, direction.strength.transform(0f), .001f)
        assertEquals(1.5f, direction.strength.transform(1f), .001f)
        assertEquals(2.dp, Highlight(width = 2.dp).width)
        assertEquals(1.2f, HighlightResponse(1.2f, 1.2f).transform(.5f), .001f)
        assertFailsWith<IllegalArgumentException> { HighlightResponse(-.01f, 1f) }
        assertFailsWith<IllegalArgumentException> { HighlightResponse(Float.NaN, 1f) }
    }
}
