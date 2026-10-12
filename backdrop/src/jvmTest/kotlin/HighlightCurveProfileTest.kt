package top.ltfan.backdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightCurveProfileTest {
    @Test
    fun depthCurveChangesInteriorFalloffWhilePreservingTheBoundaryAndInnerEdge() {
        val linear = highlightBandProfile(3f, 6f, BackdropCurve.Linear)
        val cubic =
            highlightBandProfile(
                3f,
                6f,
                BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 0f),
            )

        assertEquals(0.5f, linear, 0.001f)
        assertTrue(cubic < linear * 0.3f, "p³ response should fade the middle of the band")
        assertEquals(
            0.5f * (1f - 0.25f / 6f),
            highlightBandProfile(0f, 6f, BackdropCurve.Linear),
            0.001f,
        )
        assertEquals(0f, highlightBandProfile(6.5f, 6f, BackdropCurve.Linear))
    }

    @Test
    fun subpixelBandRetainsItsFractionalPixelCoverage() {
        val profile = highlightBandProfile(0f, 0.25f, BackdropCurve.Linear)

        assertEquals(0.125f, profile, 0.001f)
    }
}
