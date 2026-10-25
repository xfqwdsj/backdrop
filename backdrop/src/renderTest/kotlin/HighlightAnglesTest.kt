package top.ltfan.backdrop

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightAngleDomain
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.exclusionWeight
import top.ltfan.backdrop.highlight.highlightAngles

class HighlightAnglesTest {
    @Test
    fun unsignedFoldsWhileDirectedPreservesTheTwoSides() {
        val unsigned =
            highlightAngles(HighlightAngleDomain.Unsigned) {
                widthScale(0f, 180f, 0f, 2f)
                brightness(0f, 180f, 1f)
            }
        val directed = highlightAngles {
            widthScale(0f, 180f, 0f, 2f)
            brightness(0f, 180f, 1f)
        }
        assertEquals(1f, unsigned.widthScaleAt(90f))
        assertEquals(1f, unsigned.widthScaleAt(270f))
        assertEquals(0f, directed.widthScaleAt(270f))
        assertEquals(0f, directed.brightnessAt(270f))
        assertEquals(unsigned.widthScaleAt(90f), unsigned.widthScaleAt(-90f))
    }

    @Test
    fun declarationsOverwriteIndependentChannelsAndHardClearBoth() {
        val profile = highlightAngles {
            widthScale(0f, 360f, 2f)
            brightness(0f, 360f, 3f)
            widthScale(30f, 90f, 4f)
            clear(setOf(40f..50f, 180f..200f))
            brightness(45f, 46f, 5f)
        }
        assertEquals(4f, profile.widthScaleAt(35f))
        assertEquals(3f, profile.brightnessAt(35f))
        assertEquals(0f, profile.widthScaleAt(45.5f))
        assertEquals(5f, profile.brightnessAt(45.5f))
        assertEquals(0f, profile.brightnessAt(190f))
        assertEquals(2f, profile.widthScaleAt(250f))
    }

    @Test
    fun sharedEndpointsAndCircularSeamHaveNoUncoveredStripe() {
        val profile = highlightAngles {
            widthScale(0f, 180f, 1f)
            widthScale(180f, 360f, 1f)
            brightness(0f, 180f, 1f)
            brightness(180f, 360f, 1f)
        }
        for (i in -100..100) {
            val delta = i * .00001f
            for (angle in listOf(delta, 180f + delta, 360f + delta)) {
                assertEquals(1f, profile.widthScaleAt(angle), "angle=$angle")
                assertEquals(1f, profile.brightnessAt(angle), "angle=$angle")
            }
        }
    }

    @Test
    fun mirrorReversesBezierAndPreservesTheReflectedResponse() {
        val profile = highlightAngles {
            widthScale(10f, 110f, .2f, 2f, BackdropCurve.CubicBezier(.1f, .3f, .7f, .8f))
            brightness(10f, 110f, 1f)
            mirror()
        }
        for (i in 0..100) {
            val angle = 10f + i
            assertEquals(profile.widthScaleAt(angle), profile.widthScaleAt(360f - angle), .001f)
            assertEquals(profile.brightnessAt(angle), profile.brightnessAt(360f - angle))
        }
    }

    @Test
    fun disabledRangesRotateWithDirectionAndUnsignedCutsBothSides() {
        fun normal(angle: Float): Offset {
            val radians = angle * (PI / 180).toFloat()
            return Offset(cos(radians), sin(radians))
        }
        val directed =
            Highlight(direction = HighlightDirection(angle = 40f), disabledAngles = setOf(20f..30f))
        val unsigned = directed.copy(disabledAngleDomain = HighlightAngleDomain.Unsigned)
        assertEquals(0f, exclusionWeight(normal(65f), directed.cuts))
        assertEquals(1f, exclusionWeight(normal(15f), directed.cuts))
        assertEquals(0f, exclusionWeight(normal(15f), unsigned.cuts))
        assertEquals(1f, exclusionWeight(normal(40f), unsigned.cuts))
        val rotated = directed.copy(direction = HighlightDirection(angle = 140f))
        assertEquals(0f, exclusionWeight(normal(165f), rotated.cuts))
        assertEquals(1f, exclusionWeight(normal(65f), rotated.cuts))
    }

    @Test
    fun pointCutsRemainClosedAcrossRotations() {
        for (i in 0..3599) {
            val angle = i / 10f
            val radians = angle * (PI / 180).toFloat()
            val config =
                Highlight(
                    direction = HighlightDirection(angle = angle),
                    disabledAngles = setOf(0f..0f),
                )
            assertEquals(
                0f,
                exclusionWeight(Offset(cos(radians), sin(radians)), config.cuts),
                "angle=$angle",
            )
        }
    }

    @Test
    fun domainsRejectInvalidRangesAndProfilesSnapshotDeclarations() {
        assertFailsWith<IllegalArgumentException> {
            highlightAngles(HighlightAngleDomain.Unsigned) { widthScale(0f, 181f, 1f) }
        }
        assertFailsWith<IllegalArgumentException> { highlightAngles { brightness(330f, 30f, 1f) } }
        assertFailsWith<IllegalArgumentException> {
            highlightAngles { widthScale(0f, 90f, Float.NaN) }
        }
        assertFailsWith<IllegalArgumentException> {
            Highlight(
                disabledAngleDomain = HighlightAngleDomain.Unsigned,
                disabledAngles = setOf(170f..190f),
            )
        }
        val ranges = mutableSetOf(20f..30f)
        val config = Highlight(disabledAngles = ranges)
        ranges.clear()
        assertEquals(setOf(20f..30f), config.disabledAngles)
    }
}
