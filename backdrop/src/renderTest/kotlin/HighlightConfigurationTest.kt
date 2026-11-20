package top.ltfan.backdrop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightAngularCut

class HighlightConfigurationTest {
    @Test
    fun basicAndContourHaveIndependentWidthDefaults() {
        assertEquals(1.dp, Highlight.Basic().width)
        assertEquals(2.dp, Highlight.Contour().width)
        assertEquals(2.dp, Highlight().width)
        assertEquals(2.dp, Highlight {}.width)
        val custom = Highlight.Basic(width = 3.dp)
        assertEquals(3.dp, custom.copy(opacity = .5f).width)
        assertFailsWith<IllegalArgumentException> { Highlight.Basic(width = Float.NaN.dp) }
    }

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
    fun highlightDistancesLightAndOpacityMustBeFinite() {
        assertFailsWith<IllegalArgumentException> { Highlight(width = Float.NaN.dp) }
        assertFailsWith<IllegalArgumentException> { Highlight(width = (-1).dp) }
        assertFailsWith<IllegalArgumentException> { Highlight(blurRadius = Float.NaN.dp) }
        assertFailsWith<IllegalArgumentException> { Highlight(blurRadius = (-1).dp) }
        assertFailsWith<IllegalArgumentException> { Highlight(baseBrightness = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Highlight(baseBrightness = -0.1f) }
        assertFailsWith<IllegalArgumentException> {
            Highlight(environmentGain = Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { Highlight(environmentGain = -0.1f) }
        assertFailsWith<IllegalArgumentException> { Highlight(opacity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Highlight(opacity = 1.01f) }
        assertEquals(2.dp, Highlight().width)
        assertEquals(12.dp, Highlight().blurRadius)
        assertEquals(.12f, Highlight().baseBrightness)
        assertEquals(.22f, Highlight().environmentGain)
    }
}
