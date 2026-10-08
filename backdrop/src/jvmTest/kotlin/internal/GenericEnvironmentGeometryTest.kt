package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenericEnvironmentGeometryTest {
    @Test
    fun openContourIsClosedForFillBoundary() {
        val path =
            Path().apply {
                moveTo(1f, 2f)
                lineTo(8f, 2f)
                lineTo(8f, 9f)
            }

        val geometry = GenericEnvironmentGeometry(path)

        assertEquals(3, geometry.segmentCount)
        assertEquals(8f, geometry.segments[8])
        assertEquals(9f, geometry.segments[9])
        assertEquals(1f, geometry.segments[10])
        assertEquals(2f, geometry.segments[11])
    }

    @Test
    fun collinearQuadraticOvershootIsSubdividedAgainstFiniteChord() {
        val path =
            Path().apply {
                moveTo(0f, 0f)
                quadraticTo(100f, 1f, 10f, 0f)
                lineTo(0f, -2f)
                close()
            }

        val geometry = GenericEnvironmentGeometry(path)

        assertTrue(geometry.segmentCount > 2)
        assertTrue((0 until geometry.segmentCount).any { geometry.segments[it * 4 + 2] > 10f })
    }

    @Test
    fun closedLoopCubicIsFlattenedWithoutPrematureEndpointFallback() {
        val path =
            Path().apply {
                moveTo(0f, 0f)
                cubicTo(80f, 120f, -80f, 120f, 0f, 0f)
                close()
            }

        val geometry = GenericEnvironmentGeometry(path)

        assertTrue(geometry.segmentCount > 4)
        assertTrue(geometry.segments.any { it > 1f })
    }

    @Test
    fun unionRemovesInternalOverlappingContourBoundaries() {
        val path =
            Path().apply {
                addRect(Rect(0f, 0f, 12f, 10f))
                addRect(Rect(8f, 0f, 20f, 10f))
            }

        val geometry = GenericEnvironmentGeometry(path)

        assertFalse(
            (0 until geometry.segmentCount).any { index ->
                val offset = index * 4
                geometry.segments[offset] == geometry.segments[offset + 2] &&
                    geometry.segments[offset] in 8f..12f
            }
        )
        assertEquals(Rect(0f, 0f, 20f, 10f), geometry.bounds)
    }

    @Test
    fun roundedRectangleConicsAreFlattenedByTheirWeights() {
        val path =
            Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, 30f, 20f), CornerRadius(8f, 8f)))
            }

        val geometry = GenericEnvironmentGeometry(path)

        assertTrue(geometry.segmentCount > 8)
        assertEquals(Rect(0f, 0f, 30f, 20f), geometry.bounds)
    }
}
