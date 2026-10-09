package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberCombinedBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalTestApi::class)
class LayerBackdropTransformRenderingTest {
    @Test
    fun ancestorScaleTracksLayerBackdropCoordinatesWithoutRecomposition() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        val scale = mutableFloatStateOf(1f)
        var compositions = 0

        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                SideEffect { compositions++ }
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp).size(140.dp, 120.dp).graphicsLayer {
                            val value = scale.floatValue
                            scaleX = value
                            scaleY = (1f + value) / 2f
                            transformOrigin = TransformOrigin(0.3f, 1.4f)
                        }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .drawPlainBackdrop(
                                    backdrop = source,
                                    shape = { RectangleShape },
                                    effects = {},
                                    exportedBackdrop = output,
                                )
                        )
                    }
                }
            }
        }
        waitForIdle()
        val initialCompositions = compositions
        assertGradientSamples(output, outerScaleX = 1f, outerScaleY = 1f)

        runOnIdle { scale.floatValue = 0.8f }
        waitForIdle()

        assertEquals(
            initialCompositions,
            compositions,
            "The graphicsLayer state update must not recompose",
        )
        assertGradientSamples(output, outerScaleX = 0.8f, outerScaleY = 0.9f)
    }

    @Test
    fun explicitConsumerLayerBlockTracksItsOwnTransform() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp)
                            .size(140.dp, 120.dp)
                            .drawPlainBackdrop(
                                backdrop = source,
                                shape = { RectangleShape },
                                effects = {},
                                layerBlock = {
                                    scaleX = 0.8f
                                    scaleY = 0.9f
                                    transformOrigin = TransformOrigin(0.3f, 1.4f)
                                },
                                exportedBackdrop = output,
                            )
                    )
                }
            }
        }
        waitForIdle()
        assertGradientSamples(
            output,
            innerScaleX = 0.8f,
            innerScaleY = 0.9f,
            innerPivotX = 0.3f * 140f,
            innerPivotY = 1.4f * 120f,
        )
    }

    @Test
    fun arbitraryNonUniformLayerBlockScaleRemainsPlanarForCombinedSources() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                val combined = rememberCombinedBackdrop(source, source)
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp)
                            .size(140.dp, 120.dp)
                            .drawPlainBackdrop(
                                backdrop = combined,
                                shape = { RectangleShape },
                                effects = {},
                                layerBlock = {
                                    scaleX = 1.000548f
                                    scaleY = 0.970076f
                                    transformOrigin = TransformOrigin(0.3f, 1.4f)
                                },
                                exportedBackdrop = output,
                            )
                    )
                }
            }
        }
        waitForIdle()

        assertGradientSamples(
            output,
            innerScaleX = 1.000548f,
            innerScaleY = 0.970076f,
            innerPivotX = 0.3f * 140f,
            innerPivotY = 1.4f * 120f,
        )
    }

    @Test
    fun arbitraryNonUniformAncestorScaleUpdatesWithoutRecomposition() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        val dynamicScaleX = mutableFloatStateOf(1f)
        val dynamicScaleY = mutableFloatStateOf(1f)
        var compositions = 0
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                SideEffect { compositions++ }
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp).size(140.dp, 120.dp).graphicsLayer {
                            scaleX = dynamicScaleX.floatValue
                            scaleY = dynamicScaleY.floatValue
                            transformOrigin = TransformOrigin(0.3f, 1.4f)
                        }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .drawPlainBackdrop(
                                    backdrop = source,
                                    shape = { RectangleShape },
                                    effects = {},
                                    exportedBackdrop = output,
                                )
                        )
                    }
                }
            }
        }
        waitForIdle()
        val initialCompositions = compositions
        assertGradientSamples(output, outerScaleX = 1f, outerScaleY = 1f)

        runOnIdle {
            dynamicScaleX.floatValue = 1.000548f
            dynamicScaleY.floatValue = 0.970076f
        }
        waitForIdle()

        assertEquals(
            initialCompositions,
            compositions,
            "The graphicsLayer update must not recompose",
        )
        assertGradientSamples(output, outerScaleX = 1.000548f, outerScaleY = 0.970076f)
    }

    @Test
    fun ancestorAndConsumerLayerTransformsComposeInSourceCoordinates() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp).size(140.dp, 120.dp).graphicsLayer {
                            scaleX = 0.8f
                            scaleY = 0.9f
                            transformOrigin = TransformOrigin(0.3f, 1.4f)
                        }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .drawPlainBackdrop(
                                    backdrop = source,
                                    shape = { RectangleShape },
                                    effects = {},
                                    layerBlock = {
                                        scaleX = 0.75f
                                        scaleY = 0.85f
                                        transformOrigin = TransformOrigin(0.6f, 0.2f)
                                    },
                                    exportedBackdrop = output,
                                )
                        )
                    }
                }
            }
        }
        waitForIdle()
        assertGradientSamples(
            output,
            innerScaleX = 0.75f,
            innerScaleY = 0.85f,
            innerPivotX = 0.6f * 140f,
            innerPivotY = 0.2f * 120f,
            outerScaleX = 0.8f,
            outerScaleY = 0.9f,
        )
    }

    @Test
    fun ancestorRotationAndTranslationTrackSourceCoordinates() = runComposeUiTest {
        lateinit var output: LayerBackdrop
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp).size(140.dp, 120.dp).graphicsLayer {
                            rotationZ = 20f
                            translationX = 7f
                            translationY = -5f
                            transformOrigin = TransformOrigin(0.3f, 1.4f)
                        }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .drawPlainBackdrop(
                                    backdrop = source,
                                    shape = { RectangleShape },
                                    effects = {},
                                    exportedBackdrop = output,
                                )
                        )
                    }
                }
            }
        }
        waitForIdle()

        val pixels = output.graphicsLayer.toImageBitmap().toPixelMap()
        val pivotX = 0.3f * 140f
        val pivotY = 1.4f * 120f
        val radians = Math.toRadians(20.0).toFloat()
        val c = cos(radians)
        val s = sin(radians)
        val origin = output.layerOffset
        for ((x, y) in listOf(40 to 30, 90 to 70)) {
            val dx = x + 0.5f - pivotX
            val dy = y + 0.5f - pivotY
            val rootX = 60f + pivotX + c * dx - s * dy + 7f
            val rootY = 70f + pivotY + s * dx + c * dy - 5f
            val actual = pixels[origin.x.toInt() + x, origin.y.toInt() + y]
            assertEquals(1f, actual.alpha, 0.01f, "Unexpected alpha at ($x, $y): $actual")
            assertClose(rootX / 300f, actual.red, "rotated x mapping at ($x, $y)")
            assertClose(rootY / 300f, actual.green, "rotated y mapping at ($x, $y)")
        }
    }

    @Test
    fun expandedIntermediateSourceOffsetIsAppliedBeforeCoordinateMapping() = runComposeUiTest {
        lateinit var intermediate: LayerBackdrop
        lateinit var output: LayerBackdrop
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f)
            ) {
                val source =
                    rememberLayerBackdrop(tileMode = androidx.compose.ui.graphics.TileMode.Decal)
                intermediate = rememberLayerBackdrop()
                output = rememberLayerBackdrop()
                Box(Modifier.size(300.dp)) {
                    GradientSource(source)
                    Box(
                        Modifier.fillMaxSize()
                            .drawPlainBackdrop(
                                backdrop = source,
                                shape = { RectangleShape },
                                effects = {},
                                insets = { BackdropInsets(left = 11.dp, top = 17.dp) },
                                exportedBackdrop = intermediate,
                            )
                    )
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                    Box(
                        Modifier.offset(60.dp, 70.dp).size(140.dp, 120.dp).graphicsLayer {
                            scaleX = 0.8f
                            scaleY = 0.9f
                            transformOrigin = TransformOrigin(0.3f, 1.4f)
                        }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .drawPlainBackdrop(
                                    backdrop = intermediate,
                                    shape = { RectangleShape },
                                    effects = {},
                                    exportedBackdrop = output,
                                )
                        )
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(11f, intermediate.layerOffset.x, 0.01f)
        assertEquals(17f, intermediate.layerOffset.y, 0.01f)
        assertGradientSamples(output, outerScaleX = 0.8f, outerScaleY = 0.9f)
    }

    @androidx.compose.runtime.Composable
    private fun GradientSource(source: LayerBackdrop) {
        Box(Modifier.fillMaxSize().layerBackdrop(source)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color(0f, 0f, 0.1f), Color(1f, 0f, 0.1f)),
                        startX = 0f,
                        endX = 300f,
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0f, 0f, 0.1f), Color(0f, 1f, 0.1f)),
                        startY = 0f,
                        endY = 300f,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    private suspend fun assertGradientSamples(
        output: LayerBackdrop,
        innerScaleX: Float = 1f,
        innerScaleY: Float = 1f,
        innerPivotX: Float = 0f,
        innerPivotY: Float = 0f,
        outerScaleX: Float = 1f,
        outerScaleY: Float = 1f,
        outerPivotX: Float = 0.3f * 140f,
        outerPivotY: Float = 1.4f * 120f,
    ) {
        val pixels = output.graphicsLayer.toImageBitmap().toPixelMap()
        val origin = output.layerOffset
        for ((x, y) in listOf(40 to 30, 90 to 70)) {
            val localX = x + 0.5f
            val localY = y + 0.5f
            val withinConsumerX = innerPivotX + innerScaleX * (localX - innerPivotX)
            val withinConsumerY = innerPivotY + innerScaleY * (localY - innerPivotY)
            val rootX = 60f + outerPivotX + outerScaleX * (withinConsumerX - outerPivotX)
            val rootY = 70f + outerPivotY + outerScaleY * (withinConsumerY - outerPivotY)
            val actual = pixels[origin.x.toInt() + x, origin.y.toInt() + y]
            assertEquals(1f, actual.alpha, 0.01f, "Unexpected alpha at ($x, $y): $actual")
            assertClose(rootX / 300f, actual.red, "x mapping at ($x, $y)")
            assertClose(rootY / 300f, actual.green, "y mapping at ($x, $y)")
            assertClose(0.2f, actual.blue, "blue at ($x, $y)")
        }
    }

    private fun assertClose(expected: Float, actual: Float, label: String) {
        assertEquals(
            expected,
            actual,
            0.006f,
            "$label: expected $expected, got $actual (absolute error ${abs(expected - actual)})",
        )
    }
}
