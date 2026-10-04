package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalTestApi::class)
class BackdropShapeRenderingTest {
    @Test
    fun customShapesShareTheSampledAndDirectRenderingOutline() {
        val shape =
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline =
                    Outline.Generic(
                        Path().apply {
                            moveTo(size.width / 2f, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                    )
            }
        for (insets in
            listOf(
                BackdropInsets.all(20.dp),
                BackdropInsets.all(80.dp),
                BackdropInsets(10.dp, 30.dp, 45.dp, 0.dp),
                BackdropInsets.all((-10).dp),
            )) runComposeUiTest {
            val direct = mutableStateOf(false)
            setContent {
                val background = rememberLayerBackdrop()
                Box(Modifier.size(300.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(background))
                    val modifier = Modifier.offset(100.dp, 100.dp).size(80.dp)
                    if (direct.value)
                        Canvas(modifier) {
                            clipToGrownShape(shape, insets) {
                                drawRect(Color.Red, Offset(-80f, -80f), Size(240f, 240f))
                            }
                        }
                    else
                        Box(
                            modifier.drawPlainBackdrop(
                                background,
                                { shape },
                                effects = {},
                                insets = { insets },
                                onDrawSurface = {
                                    drawRect(Color.Red, Offset(-80f, -80f), Size(240f, 240f))
                                },
                            )
                        )
                }
            }
            val sampled = onNodeWithTag("root").captureToImage().toPixelMap()
            runOnIdle { direct.value = true }
            val fallback = onNodeWithTag("root").captureToImage().toPixelMap()
            var difference = 0f
            for (y in 0 until sampled.height) for (x in 0 until sampled.width) {
                difference = maxOf(difference, abs(sampled[x, y].alpha - fallback[x, y].alpha))
            }
            assertTrue(difference <= 3f / 255f, "insets=$insets difference=$difference")
            assertTrue(
                sampled[(105 - insets.left.value).toInt(), (105 - insets.top.value).toInt()].alpha <
                    .01f,
                "Triangle corners stay clear",
            )
        }
    }
}
