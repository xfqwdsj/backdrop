package top.ltfan.backdrop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackdropCurveTest {
    @Test
    fun cubicCurveKeepsExactEndpointsAndMatchesItsControls() {
        val curve = BackdropCurve.CubicBezier(1f / 3f, 0f, 2f / 3f, 0f)
        assertEquals(0f, curve.transform(0f))
        assertEquals(1f, curve.transform(1f))
        assertEquals(0.125f, curve.transform(0.5f), 0.0001f)
        assertEquals(0.015625f, curve.transform(0.25f), 0.0001f)
        assertEquals(0.354f, curve.transform(0.7071f), 0.002f)
    }

    @Test
    fun linearAndDegenerateCubicAreIdentity() {
        assertEquals(0.37f, BackdropCurve.Linear.transform(0.37f))
        val diagonal = BackdropCurve.CubicBezier(0f, 0f, 1f, 1f)
        assertEquals(0.37f, diagonal.transform(0.37f), 0.0001f)
    }

    @Test
    fun controlsMustBeFiniteAndOrdered() {
        assertFailsWith<IllegalArgumentException> {
            BackdropCurve.CubicBezier(0.8f, 0f, 0.2f, 1f)
        }
        assertFailsWith<IllegalArgumentException> {
            BackdropCurve.CubicBezier(0f, 0.8f, 1f, 0.2f)
        }
        assertFailsWith<IllegalArgumentException> {
            BackdropCurve.CubicBezier(Float.NaN, 0f, 1f, 1f)
        }
    }
}
