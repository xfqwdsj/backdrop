package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.impl.use
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.highlight

class HighlightTileFrameTest {
    @Test
    fun tiledHighlightReadsTheRecordedSourceFromTheSameFrame() {
        val shape = GenericShape { size, _ ->
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(8f)))
        }
        assertHighlightReadsCurrentFrame(shape)
        assertHighlightReadsCurrentFrame(RoundedCornerShape(8.dp))
    }

    private fun assertHighlightReadsCurrentFrame(shape: Shape) {
        val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
        val marker = mutableIntStateOf(0)
        val scene =
            ImageComposeScene(width = 96, height = 96) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(96.dp)) {
                    Box(
                        Modifier.size(96.dp).layerBackdrop(backdrop).drawBehind {
                            drawRect(colors[marker.intValue])
                        },
                    )
                    Box(
                        Modifier.offset(16.dp, 16.dp).size(64.dp).drawBackdrop(
                            backdrop,
                            { shape },
                        ) {
                            val environment = image { source() }
                            replace(image { paint { drawRect(Color.Black) } })
                            highlight(environment = environment, blendMode = BlendMode.SrcOver) {
                                width = 8.dp
                                blurRadius = 4.dp
                                baseBrightness = 0f
                                environmentGain = 1f
                                direction = HighlightDirection.Uniform
                            }
                        },
                    )
                }
            }
        try {
            var time = 0L
            fun sample(): Pair<Color, Color> {
                time += 8_333_333L
                return scene.render(time).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    pixels[8, 48] to pixels[17, 48]
                }
            }

            val reference =
                colors.indices.map { index ->
                    marker.intValue = index
                    repeat(3) { sample() }
                    sample()
                }
            assertEquals(colors, reference.map { it.first })
            assertEquals(colors.size, reference.map { it.second }.distinct().size)
            repeat(12) { frame ->
                val index = frame % colors.size
                marker.intValue = index
                assertEquals(reference[index], sample(), "frame=$frame")
            }
        } finally {
            scene.close()
        }
    }
}
