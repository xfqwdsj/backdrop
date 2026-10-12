package top.ltfan.backdrop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.ltfan.backdrop.highlight.EnvironmentHighlight
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightExclusion

class HighlightConfigurationTest {
    @Test
    fun exclusionsValidateAngularRangesAndFeather() {
        assertEquals(-180f, HighlightExclusion.Direction(-180f, 180f, 0f).angle)
        assertFailsWith<IllegalArgumentException> { HighlightExclusion.Direction(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { HighlightExclusion.Direction(0f, 180.1f) }
        assertFailsWith<IllegalArgumentException> { HighlightExclusion.Direction(0f, 60f, 121f) }
        assertFailsWith<IllegalArgumentException> {
            HighlightExclusion.Direction(0f, 0f, Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun environmentContributionsValidateFiniteStrengthThresholdAndWidth() {
        assertEquals(0.2f, EnvironmentHighlight().brightness.strength)
        assertEquals(4.dp, EnvironmentHighlight().blurRadius)
        assertFailsWith<IllegalArgumentException> { EnvironmentHighlight(blurRadius = (-1).dp) }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentHighlight(blurRadius = Float.NaN.dp)
        }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentHighlight(blurRadius = Float.POSITIVE_INFINITY.dp)
        }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentHighlight.Contribution(Float.NaN, 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentHighlight.Contribution(1f, Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { EnvironmentHighlight.Thickness((-1).dp, 0f) }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentHighlight.Thickness(1.dp, Float.NaN)
        }
    }

    @Test
    fun highlightBoundsAndOpacityMustBeFiniteAndNonNegative() {
        assertFailsWith<IllegalArgumentException> { Highlight(width = Float.NaN.dp) }
        assertFailsWith<IllegalArgumentException> {
            Highlight(width = 1.dp, blurRadius = Float.POSITIVE_INFINITY.dp)
        }
        assertFailsWith<IllegalArgumentException> { Highlight(alpha = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Highlight(alpha = 1.01f) }
    }
}
