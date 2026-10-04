package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackdropMaskRenderingTest {
    @Test
    fun fillAndStrengthMultiplyForFlatAndProgressiveMasks() {
        for (ramped in listOf(false, true)) for (gradient in listOf(false, true)) runComposeUiTest {
            val fill = Color.Blue.copy(alpha = .5f)
            setContent {
                Canvas(Modifier.size(80.dp).testTag("root")) {
                    val cache = BackdropMaskRampCache()
                    val ramp = BackdropRamp(from = 1f, to = 1f)
                    if (gradient)
                        drawBackdropMask(
                            ramped,
                            Brush.linearGradient(listOf(fill, fill)),
                            ramp,
                            size,
                            cache,
                            BackdropEdge.Bottom,
                            size,
                            0f,
                            alpha = .4f,
                        )
                    else
                        drawBackdropMask(
                            ramped,
                            fill,
                            ramp,
                            size,
                            cache,
                            BackdropEdge.Bottom,
                            size,
                            0f,
                            alpha = .4f,
                        )
                }
            }
            val pixel = onNodeWithTag("root").captureToImage().toPixelMap()[40, 40]
            assertTrue(pixel.blue > .98f && pixel.red < .01f)
            assertTrue(
                abs(pixel.alpha - .2f) <= 1f / 255f,
                "ramped=$ramped gradient=$gradient pixel=$pixel",
            )
        }
    }
}
