package top.ltfan.backdrop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun boundedSearchAndInteriorRejectionPreserveShaderOutputAcrossWidthUpdates() {
        for ((pathIndex, path) in paths().withIndex()) {
            val geometry = HighlightBoundaryGeometry(Outline.Generic(path))
            if (geometry.isEmpty) {
                assertTrue(geometry.contourFreeBounds.isEmpty)
                continue
            }
            val capacity = boundaryUniformCapacity(geometry.segmentCount)
            for (groupSize in listOf(0, 4, 8).filter { it == 0 || capacity % it == 0 }) {
                fun shaderSource(certifiedNormalsOnly: Boolean): String =
                    staticHighlightTileShader(
                        geometry,
                        HighlightStyle.Solid(Color.White),
                        0,
                        segmentCapacity = capacity,
                        segmentGroupSize = groupSize,
                        certifiedNormalsOnly = certifiedNormalsOnly,
                    )
                fun unboundedSource(certifiedNormalsOnly: Boolean): String =
                    shaderSource(certifiedNormalsOnly)
                        .replace(
                            "nearestBoundaryWithPosition(p, conservativeUpper + geometryScale * 0.000004, nearestPosition)",
                            "nearestBoundaryWithPosition(p, nearestPosition)",
                        )
                val unbounded = RuntimeShader(unboundedSource(false))
                val variants = mutableListOf("bounded" to RuntimeShader(shaderSource(false)))
                if (geometry.certifiesNormalsFor(Size(80f, 80f))) {
                    variants += "certified" to RuntimeShader(unboundedSource(true))
                    variants += "bounded-certified" to RuntimeShader(shaderSource(true))
                }
                for (configured in listOf(unbounded) + variants.map { it.second }) {
                    geometry.configureShader(configured)
                    if (groupSize > 0)
                        configured.setFloatUniform(
                            "segmentGroupBounds",
                            geometry.segmentGroupBounds(capacity, groupSize),
                        )
                    configured.setFloatUniform("segments", geometry.paddedSegments(capacity))
                    configured.setFloatUniform(
                        "segmentNormalSigns",
                        geometry.paddedNormalSigns(capacity),
                    )
                    configured.setFloatUniform("tileBounds", 0f, 0f, 80f, 80f)
                    configured.setFloatUniform("directionVector", 0f, -1f)
                    configured.setFloatUniform("directionEnabled", 1f)
                    configured.setFloatUniform("directionMode", 0f)
                    configured.setFloatUniform("directionStrengthRange", .25f, 1f)
                    configured.setFloatUniform(
                        "directionStrengthCurve",
                        BackdropCurve.Linear.shaderControls(),
                    )
                    configured.setFloatUniform("directionWidthRange", .5f, 2f)
                    configured.setFloatUniform(
                        "directionWidthCurve",
                        BackdropCurve.Linear.shaderControls(),
                    )
                    configured.setFloatUniform(
                        "falloffCurve",
                        BackdropCurve.Default.shaderControls(),
                    )
                    configured.setColorUniform("color", Color.White)
                }
                for (width in listOf(.25f, 2f, 12f, 40f)) {
                    unbounded.setFloatUniform("width", width)
                    unbounded.setFloatUniform("emptyBoundaryBounds", 0f, 0f, 0f, 0f)
                    val expected = renderTestShader(unbounded, 80, 80)
                    fun assertCornerEmission(pixels: FloatArray, context: String) {
                        if (pathIndex != 0 || width != .25f) return
                        val boundary = geometry.nearestBoundary(Offset(77.5f, 77.5f))
                        val magnitude = maxOf(0f, -boundary.outwardNormal.y)
                        val bandWidth = width * (.5f + 1.5f * magnitude)
                        val emission =
                            highlightBandProfile(
                                boundary.distance,
                                bandWidth,
                                BackdropCurve.Default,
                            ) * (.25f + .75f * magnitude)
                        assertEquals(emission, pixels[(77 * 80 + 77) * 4 + 3], .00002f, context)
                    }
                    assertCornerEmission(expected, "unbounded, group=$groupSize")
                    for ((variant, shader) in variants) {
                        shader.setFloatUniform("width", width)
                        for (empty in listOf(Rect.Zero, geometry.contourFreeBounds)) {
                            shader.setFloatUniform(
                                "emptyBoundaryBounds",
                                empty.left,
                                empty.top,
                                empty.right,
                                empty.bottom,
                            )
                            val actual = renderTestShader(shader, 80, 80)
                            val context =
                                "path=$pathIndex, width=$width, group=$groupSize, variant=$variant, empty=$empty"
                            assertCornerEmission(actual, context)
                            assertEquals(expected.size, actual.size, context)
                            for (index in expected.indices) {
                                if (expected[index].toBits() != actual[index].toBits()) {
                                    assertEquals(
                                        expected[index].toBits(),
                                        actual[index].toBits(),
                                        "$context, pixel=(${index / 4 % 80},${index / 320}), channel=${index % 4}, expected=${expected[index]}, actual=${actual[index]}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun certifiedContour(): Path =
        Path().apply {
            moveTo(18f, 0f)
            lineTo(62f, 0f)
            cubicTo(80f, 0f, 80f, 0f, 80f, 18f)
            lineTo(80f, 62f)
            cubicTo(80f, 80f, 80f, 80f, 62f, 80f)
            lineTo(18f, 80f)
            cubicTo(0f, 80f, 0f, 80f, 0f, 62f)
            lineTo(0f, 18f)
            cubicTo(0f, 0f, 0f, 0f, 18f, 0f)
            close()
        }

    @Test
    fun normalSpecializationRequiresTheCompleteTileAndAllSegmentsToBeCertified() {
        val geometry = HighlightBoundaryGeometry(Outline.Generic(certifiedContour()))
        assertTrue(geometry.certifiesNormalsFor(Size(80f, 80f)))
        assertTrue(geometry.certifiesNormalsFor(Size(40f, 60f)))
        assertTrue(!geometry.certifiesNormalsFor(Size(80.01f, 80f)))
        assertTrue(!geometry.certifiesNormalsFor(Size(80f, 80.01f)))
        assertTrue(!geometry.certifiesNormalsFor(Size(-1f, 80f)))
        assertTrue(!geometry.certifiesNormalsFor(Size(Float.NaN, 80f)))
        assertTrue(!geometry.certifiesNormalsFor(Size(80f, Float.POSITIVE_INFINITY)))
        val uncertain =
            HighlightBoundaryGeometry(
                Outline.Generic(Path().apply { addRect(Rect(0f, 0f, 32f, 32f)) }),
            )
        assertTrue(uncertain.cpuGeometry().normalSigns.any { it == 0f })
        assertTrue(!uncertain.certifiesNormalsFor(Size(32f, 32f)))
        assertTrue(
            !HighlightBoundaryGeometry(Outline.Rectangle(Rect(0f, 0f, 80f, 80f)))
                .certifiesNormalsFor(Size(80f, 80f)),
        )
        assertTrue(
            !HighlightBoundaryGeometry(Outline.Generic(Path())).certifiesNormalsFor(Size.Zero),
        )
    }

    private fun paths(): List<Path> =
        listOf(
            certifiedContour(),
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
