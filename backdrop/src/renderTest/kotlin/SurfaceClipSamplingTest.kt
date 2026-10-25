package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.effect
import top.ltfan.backdrop.shadow.Shadow
import top.ltfan.backdrop.shadow.shadow

class SurfaceClipSamplingTest : PlatformGraphicsTest() {
    @Test
    fun shadowCoverageDoesNotEnlargeClippedMaterialSampling() {
        val scope = BackdropRenderScope(1f, 1f, Size(80f, 80f), RectangleShape, LayoutDirection.Ltr)
        var input: Rect? = null
        var room: BackdropExtension? = null
        with(scope) {
            val material = image {
                source()
                effects {
                    effect(BackdropSampling.outsets(4f, 4f, 4f, 4f)) {
                        input = inputBounds
                        room = extension
                        BlurEffect(1f, 1f)
                    }
                }
                clipSurface()
            }
            group {
                shadow(Shadow(radius = 48.dp))
                draw(material)
            }
        }
        BackdropRenderer { error("Resolving does not allocate layers") }
            .use { renderer ->
                renderer.resolve(scope, Rect(0f, 0f, 80f, 80f))
                // The extra pixel conservatively retains the clip's antialiasing coverage.
                assertEquals(Rect(-5f, -5f, 85f, 85f), input)
                assertEquals(renderer.extension, room)
                assertTrue(
                    renderer.extension.left > 5f,
                    "The shadow retains its complete output room",
                )
            }
    }
}
