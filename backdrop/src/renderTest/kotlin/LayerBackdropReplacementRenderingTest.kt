package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop

class LayerBackdropReplacementRenderingTest {
    @Test
    fun epochChangeKeepsSamplingTheLayerInEveryFrame() = runRenderTest {
        mainClock.autoAdvance = false
        val epoch = mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                val source = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(source).background(Color.Green))
                    Box(Modifier.fillMaxSize().background(Color.Yellow))
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = source,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        effects {}
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        mainClock.advanceTimeBy(500)
        fun center(): Color {
            val image = onNodeWithTag("root").captureToImage()
            return image.toPixelMap()[image.width / 2, image.height / 2]
        }
        assertTrue(center().green > 0.9f, "The layer is sampled before the epoch change")
        epoch.intValue++
        repeat(4) { frame ->
            mainClock.advanceTimeByFrame()
            val pixel = center()
            println("LIB_EPOCH frame$frame=$pixel")
        }
        val settled = center()
        assertTrue(settled.green > 0.9f, "The layer is sampled after the epoch change: $settled")
    }

    @Test
    fun replacedCallerLayerKeepsBeingSampled() = runRenderTest {
        mainClock.autoAdvance = false
        var version by mutableIntStateOf(0)
        setContent {
            Box(Modifier.size(160.dp).testTag("root")) {
                val replaced = key(version) { rememberGraphicsLayer() }
                val inner = rememberLayerBackdrop(graphicsLayer = replaced)
                Box(Modifier.fillMaxSize().layerBackdrop(inner).background(Color.Blue))
                // A sibling between the recording and the sampling surface, so the sampling surface
                // is drawn while the replaced layer is empty.
                Box(Modifier.fillMaxSize().background(Color.Yellow))
                Box(
                    Modifier.fillMaxSize()
                        .drawBackdrop(
                            backdrop = inner,
                            shape = { RectangleShape },
                            render = {
                                val material = image {
                                    source()
                                    effects {}
                                    clipSurface()
                                }
                                group {
                                    draw(material)
                                }
                                foreground {}
                            },
                        ),
                )
            }
        }
        mainClock.advanceTimeBy(500)
        fun center(): Color {
            val image = onNodeWithTag("root").captureToImage()
            return image.toPixelMap()[image.width / 2, image.height / 2]
        }
        assertTrue(center().blue > 0.9f, "The replaced layer is sampled before the replacement")
        version++
        mainClock.advanceTimeBy(500)
        val settled = center()
        assertTrue(
            settled.blue > 0.9f,
            "The replaced layer is sampled after the replacement: $settled",
        )
    }

    @Test
    fun contentOfAnExportedLayerDrawsItAfterTheEpochRecording() = runRenderTest {
        mainClock.autoAdvance = false
        val epoch = mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                val source = rememberLayerBackdrop()
                val exported = rememberLayerBackdrop()
                Box(Modifier.size(160.dp).background(Color.Blue).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(source).background(Color.Green))
                    // The surface records `exported` after its content has drawn.
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = source,
                                shape = { RectangleShape },
                                exportedBackdrop = exported,
                                render = {
                                    val material = image {
                                        source()
                                        effects {}
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)
                                        paintSurface { drawRect(Color.Yellow) }
                                    }
                                    foreground {}
                                },
                            ),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                        Box(
                            Modifier.fillMaxSize()
                                .drawBackdrop(
                                    backdrop = exported,
                                    shape = { RectangleShape },
                                    render = {
                                        val material = image {
                                            source()
                                            effects {}
                                            clipSurface()
                                        }
                                        group {
                                            draw(material)
                                        }
                                        foreground {}
                                    },
                                ),
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeBy(500)
        fun center(): Color {
            val image = onNodeWithTag("root").captureToImage()
            return image.toPixelMap()[image.width / 2, image.height / 2]
        }
        val before = center()
        assertTrue(
            before.red > 0.9f && before.green > 0.9f,
            "The nested surface draws the exported layer before the epoch change: $before",
        )
        epoch.intValue++
        repeat(4) { frame ->
            mainClock.advanceTimeByFrame()
            val after = center()
            assertTrue(
                after.red > 0.9f && after.green > 0.9f,
                "The nested surface draws the exported layer in frame $frame: $after",
            )
        }
    }
}
