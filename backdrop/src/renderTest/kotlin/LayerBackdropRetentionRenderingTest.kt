package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

class LayerBackdropRetentionRenderingTest {
    @Test
    fun movingASourceRefreshesConsumersWithoutStalePixels() = runRenderTest {
        val x = mutableFloatStateOf(0f)
        var recordings = 0
        val sourceDraw: ContentDrawScope.() -> Unit = {
            recordings++
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Red, Color.Blue),
                    startX = 0f,
                    endX = 180f,
                ),
            )
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val source = rememberLayerBackdrop(tileMode = TileMode.Decal, onDraw = sourceDraw)
                Box(Modifier.size(width = 220.dp, height = 100.dp).testTag("moving-source")) {
                    Box(
                        Modifier.offset { IntOffset(x.floatValue.toInt(), 0) }
                            .size(width = 180.dp, height = 100.dp)
                            .layerBackdrop(source),
                    )
                    SourceConsumer(
                        source,
                        Modifier.offset(80.dp, 20.dp).size(width = 40.dp, height = 60.dp),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("moving-source").captureToImage()
        waitForIdle()
        val before = onNodeWithTag("moving-source").captureToImage().toPixelMap()[100, 50]
        val initialRecordings = recordings
        assertTrue(initialRecordings > 0)

        runOnIdle { x.floatValue = -40f }
        waitForIdle()
        val after = onNodeWithTag("moving-source").captureToImage().toPixelMap()[100, 50]

        assertTrue(
            after.blue > before.blue + .15f,
            "source coordinates must update without stale pixels: before=$before, after=$after",
        )
    }

    @Test
    fun stableLayerBackdropDoesNotRecordEveryDraw() = runRenderTest {
        var recordings = 0
        var compositions = 0
        val sourceDraw: ContentDrawScope.() -> Unit = {
            recordings++
            drawRect(Color.Red)
        }
        setContent {
            SideEffect { compositions++ }
            val source = rememberLayerBackdrop(tileMode = TileMode.Decal, onDraw = sourceDraw)
            Box(
                Modifier.size(80.dp).testTag("stable-source").layerBackdrop(source),
            )
        }
        waitForIdle()
        repeat(3) {
            onNodeWithTag("stable-source").captureToImage()
            waitForIdle()
        }

        assertTrue(recordings <= 1, "recordings=$recordings, compositions=$compositions")
    }

    @Test
    fun successfulSourceRecordingInvalidatesConsumersOfTheOldPixels() = runRenderTest {
        val color = mutableStateOf(Color.Red)
        var recordings = 0
        val sourceDraw: ContentDrawScope.() -> Unit = {
            recordings++
            drawRect(color.value)
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val source = rememberLayerBackdrop(tileMode = TileMode.Decal, onDraw = sourceDraw)
                Box(Modifier.size(100.dp).testTag("recording-source")) {
                    Box(Modifier.size(60.dp).layerBackdrop(source))
                    SourceConsumer(
                        source,
                        Modifier.offset(20.dp, 20.dp).size(40.dp),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("recording-source").captureToImage()
        waitForIdle()
        assertTrue(
            onNodeWithTag("recording-source").captureToImage().toPixelMap()[40, 40].red > .98f,
        )
        val initialRecordings = recordings

        runOnIdle { color.value = Color.Blue }
        waitForIdle()
        val changed = onNodeWithTag("recording-source").captureToImage().toPixelMap()[40, 40]

        assertTrue(
            recordings > initialRecordings,
            "source revision must trigger a new recording: before=$initialRecordings, after=$recordings",
        )
        assertTrue(
            changed.blue > .98f,
            "consumer must observe the successful source revision: $changed",
        )
    }

    @Composable
    private fun SourceConsumer(source: LayerBackdrop, modifier: Modifier) {
        Box(
            modifier.drawBackdrop(source, { RectangleShape }) {
                val material = image {
                    source()
                    clipSurface()
                }
                group { draw(material) }
            },
        )
    }
}
