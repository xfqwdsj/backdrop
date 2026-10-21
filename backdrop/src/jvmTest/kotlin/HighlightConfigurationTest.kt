package top.ltfan.backdrop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightAngularCut
import top.ltfan.backdrop.highlight.HighlightColorResponse

class HighlightConfigurationTest {
    @Test
    fun exclusionsValidateAngularRangesAndFeather() {
        assertEquals(-180f, HighlightAngularCut(-180f, 180f, 0f).angle)
        assertFailsWith<IllegalArgumentException> { HighlightAngularCut(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { HighlightAngularCut(0f, 180.1f) }
        assertFailsWith<IllegalArgumentException> { HighlightAngularCut(0f, 60f, 121f) }
        assertFailsWith<IllegalArgumentException> {
            HighlightAngularCut(0f, 0f, Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun colorResponseAmountMustBeFiniteAndBounded() {
        assertEquals(.5f, HighlightColorResponse().amount)
        assertFailsWith<IllegalArgumentException> { HighlightColorResponse(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { HighlightColorResponse(-.1f) }
        assertFailsWith<IllegalArgumentException> { HighlightColorResponse(1.1f) }
    }

    @Test
    fun highlightBoundsAndOpacityMustBeFiniteAndNonNegative() {
        assertFailsWith<IllegalArgumentException> { Highlight(width = Float.NaN.dp) }
        assertFailsWith<IllegalArgumentException> { Highlight(width = (-1).dp) }
        assertFailsWith<IllegalArgumentException> { Highlight(opacity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Highlight(opacity = 1.01f) }
        assertEquals(2.dp, Highlight().width)
    }
}
