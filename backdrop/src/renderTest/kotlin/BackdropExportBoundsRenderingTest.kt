package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

class BackdropExportBoundsRenderingTest {
    @Test
    fun fractionalInsetsExportEveryTouchedPixel() = runRenderTest {
        lateinit var exported: LayerBackdrop
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.75f)) {
                val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
                exported = rememberLayerBackdrop()
                Box(Modifier.size(100.dp)) {
                    Box(
                        Modifier.size(100.dp).drawBackdrop(
                            source,
                            { RectangleShape },
                            exportedBackdrop = exported,
                            insets = { BackdropInsets.all(0.3.dp) },
                        ) {
                            fill(Color.Red)
                        },
                    )
                    Box(Modifier.size(50.dp).drawBackdrop(exported, { RectangleShape }))
                }
            }
        }
        waitForIdle()
        runOnIdle {
            assertEquals(Rect(0f, 0f, 277f, 277f), exported.sourceBounds)
        }
    }
}
