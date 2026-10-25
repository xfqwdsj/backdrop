package top.ltfan.backdrop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.highlight.HighlightBoundaryGeometry
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.boundaryUniformCapacity
import top.ltfan.backdrop.internal.staticHighlightTileShader

class HighlightInteriorRejectionTest : PlatformGraphicsTest() {
    @Test
    fun emptyRectangleStaysAwayFromEveryContourIncludingHolesAndConcavities() {
        for (path in paths()) {
            val geometry = HighlightBoundaryGeometry(Outline.Generic(path))
            val empty = geometry.contourFreeBounds
            if (empty.isEmpty) continue
            for (y in 0..20) for (x in 0..20) {
                val point =
                    Offset(empty.left + empty.width * x / 20f, empty.top + empty.height * y / 20f)
                val clearance =
                    minOf(
                        point.x - empty.left,
                        empty.right - point.x,
                        point.y - empty.top,
                        empty.bottom - point.y,
                    )
                assertTrue(geometry.nearestBoundary(point).distance + .001f >= clearance)
            }
        }
        val rounded = HighlightBoundaryGeometry(Outline.Generic(paths().first()))
        assertTrue(rounded.contourFreeBounds.width * rounded.contourFreeBounds.height > 1800f)
    }

    @Test
    fun rejectingCertifiedTransparentPixelsPreservesShaderOutputAcrossWidthUpdates() {
        for (path in paths()) {
            val geometry = HighlightBoundaryGeometry(Outline.Generic(path))
            if (geometry.isEmpty) {
                assertTrue(geometry.contourFreeBounds.isEmpty)
                continue
            }
            val capacity = boundaryUniformCapacity(geometry.segmentCount)
            val shader =
                RuntimeShader(
                    staticHighlightTileShader(
                        geometry,
                        HighlightStyle.Solid(Color.White),
                        0,
                        segmentCapacity = capacity,
                    ),
                )
            geometry.configureShader(shader)
            shader.setFloatUniform("segments", geometry.paddedSegments(capacity))
            shader.setFloatUniform("segmentNormalSigns", geometry.paddedNormalSigns(capacity))
            shader.setFloatUniform("tileBounds", 0f, 0f, 80f, 80f)
            shader.setFloatUniform("directionVector", 0f, -1f)
            shader.setFloatUniform("directionEnabled", 1f)
            shader.setFloatUniform("directionMode", 0f)
            shader.setFloatUniform("directionStrengthRange", .25f, 1f)
            shader.setFloatUniform("directionStrengthCurve", BackdropCurve.Linear.shaderControls())
            shader.setFloatUniform("directionWidthRange", .5f, 2f)
            shader.setFloatUniform("directionWidthCurve", BackdropCurve.Linear.shaderControls())
            shader.setFloatUniform("falloffCurve", BackdropCurve.Default.shaderControls())
            shader.setColorUniform("color", Color.White)
            for (width in listOf(.25f, 2f, 12f, 40f)) {
                shader.setFloatUniform("width", width)
                shader.setFloatUniform("emptyBoundaryBounds", 0f, 0f, 0f, 0f)
                val expected = renderTestShader(shader, 80, 80)
                val empty = geometry.contourFreeBounds
                shader.setFloatUniform(
                    "emptyBoundaryBounds",
                    empty.left,
                    empty.top,
                    empty.right,
                    empty.bottom,
                )
                val actual = renderTestShader(shader, 80, 80)
                assertContentEquals(expected, actual, "width=$width, empty=$empty")
            }
        }
    }

    private fun paths(): List<Path> =
        listOf(
            Path().apply { addRoundRect(RoundRect(Rect(3f, 5f, 77f, 75f), CornerRadius(18f))) },
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(3f, 5f, 77f, 75f))
                addRect(Rect(25f, 25f, 55f, 55f))
            },
            Path().apply {
                moveTo(3f, 5f)
                lineTo(77f, 5f)
                lineTo(77f, 75f)
                lineTo(45f, 75f)
                lineTo(45f, 25f)
                lineTo(35f, 25f)
                lineTo(35f, 75f)
                lineTo(3f, 75f)
                close()
            },
            Path().apply {
                addRect(Rect(3f, 5f, 25f, 75f))
                addRect(Rect(55f, 5f, 77f, 75f))
            },
            Path().apply {
                addRoundRect(RoundRect(Rect(-8f, 3f, 77f, 75f), CornerRadius(18f, 6f)))
            },
            Path(),
        )
}
