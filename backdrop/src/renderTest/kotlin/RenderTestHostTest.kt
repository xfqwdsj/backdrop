package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Validates the pixel-test host independently of Backdrop's rendering graph. */
class RenderTestHostTest {
    @Test
    fun capturedContentUsesSurfacePixelsWithoutWindowDecorations() = runRenderTest {
        setContent { Box(Modifier.size(80.dp).background(Color.Red).testTag("host")) }
        val image = onNodeWithTag("host").captureToImage()
        assertEquals(80, image.width)
        assertEquals(80, image.height)
        assertEquals(Color.Red, image.toPixelMap()[40, 40])
    }
}
