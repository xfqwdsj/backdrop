package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.coverRamp
import top.ltfan.backdrop.effects.progressiveBlur

@OptIn(ExperimentalTestApi::class)
class BackdropRampRenderingTest {
    @Test
    fun blurAndMaskShareAnchorsWithInsetsAndSamplingRoom() {
        for (direction in LayoutDirection.entries) for (edge in BackdropEdge.entries) for (span in
            listOf((-20f)..100f, 20f..60f, (-20f)..60f, 20f..100f)) for (inset in
            listOf(0, 24, -12)) runComposeUiTest {
            val mask = mutableStateOf(false)
            val ramp = BackdropRamp(anchors = { span })
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val bg = rememberLayerBackdrop()
                    Box(Modifier.size(200.dp, 200.dp).testTag("root")) {
                        Box(Modifier.fillMaxSize().layerBackdrop(bg))
                        Box(
                            Modifier.offset(50.dp, 50.dp)
                                .size(80.dp, 80.dp)
                                .drawPlainBackdrop(
                                    bg,
                                    { RectangleShape },
                                    insets = { BackdropInsets(top = inset.dp) },
                                    effects = {
                                        if (mask.value) coverRamp(ramp, edge)
                                        else {
                                            val actual = this
                                            val inspection =
                                                object : BackdropEffectScopeImpl() {
                                                    override val shape: Shape
                                                        get() = RectangleShape

                                                    override fun obtainRuntimeShader(
                                                        key: String,
                                                        string: String,
                                                    ): RuntimeShader {
                                                        // Expose the intensity computed by the
                                                        // actual production shader, retaining its
                                                        // coordinate, anchors and curve
                                                        // calculations verbatim.
                                                        val inspected =
                                                            string.replace(
                                                                "return blur(coord, mix(0.0, blurRadius, intensity));",
                                                                "return vec4(intensity, intensity, intensity, 1.0);",
                                                            )
                                                        return actual.obtainRuntimeShader(
                                                            key,
                                                            inspected,
                                                        )
                                                    }
                                                }
                                            inspection.size = size
                                            inspection.density = density
                                            inspection.fontScale = fontScale
                                            inspection.layoutDirection = layoutDirection
                                            inspection.extension = extension
                                            inspection.progressiveBlur(16.dp.toPx(), edge, ramp)
                                            cover = inspection.cover
                                            renderEffect = inspection.renderEffect
                                            padding = 12f
                                        }
                                    },
                                    onDrawSurface = { room ->
                                        if (mask.value) {
                                            translate(
                                                room.extension.originInNode.x,
                                                room.extension.originInNode.y,
                                            ) {
                                                drawBackdropMask(
                                                    true,
                                                    Color.White,
                                                    ramp,
                                                    Size(
                                                        room.size.width +
                                                            room.extension.left +
                                                            room.extension.right,
                                                        room.size.height +
                                                            room.extension.top +
                                                            room.extension.bottom,
                                                    ),
                                                    BackdropMaskRampCache(),
                                                    edge,
                                                    room.size,
                                                    if (edge.side(layoutDirection).isVertical)
                                                        room.extension.top
                                                    else room.extension.left,
                                                )
                                            }
                                        }
                                    },
                                )
                        )
                    }
                }
            }
            val shader = onNodeWithTag("root").captureToImage().toPixelMap()
            runOnIdle { mask.value = true }
            val brush = onNodeWithTag("root").captureToImage().toPixelMap()
            var maximum = 0f
            for (along in 52..127) {
                val x = if (edge.side(direction).isVertical) 90 else along
                val y = if (edge.side(direction).isVertical) along else 90
                maximum =
                    maxOf(maximum, abs(shader[x, y].red - brush[x, y].red * brush[x, y].alpha))
            }
            assertTrue(
                maximum < .01f,
                "direction=$direction edge=$edge span=$span inset=$inset maxDiff=$maximum",
            )
        }
    }
}
