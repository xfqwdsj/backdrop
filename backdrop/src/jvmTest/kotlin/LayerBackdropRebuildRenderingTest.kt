package top.ltfan.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberCombinedBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.shadow.Shadow

class LayerBackdropRebuildRenderingTest {
    private data class StripePixels(val first: Int, val second: Int)

    private fun renderStripePixels(scene: ImageComposeScene, timeNanos: Long): StripePixels {
        scene.render(timeNanos).use { image ->
            val info = ImageInfo(160, 160, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.sRGB)
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                check(image.readPixels(bitmap, 0, 0))
                return StripePixels(bitmap.getColor(35, 80), bitmap.getColor(45, 80))
            }
        }
    }

    private fun DrawScope.drawStripes(
        width: Float,
        height: Float,
        stripeWidth: Float,
        first: Color,
        second: Color,
    ) {
        var x = 0f
        var index = 0
        while (x < width) {
            drawRect(
                color = if (index++ % 2 == 0) first else second,
                topLeft = Offset(x, 0f),
                size = Size(stripeWidth, height),
            )
            x += stripeWidth
        }
    }

    @Test
    fun dirtySiblingConsumerKeepsSamplingRebuiltSourceUnderLayerAncestor() {
        val epoch = mutableIntStateOf(0)
        val redrawToken = mutableIntStateOf(0)
        val scene =
            ImageComposeScene(
                width = 160,
                height = 160,
                content = {
                    CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                        val source = rememberLayerBackdrop()
                        Box(Modifier.size(160.dp).graphicsLayer()) {
                            Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawStripes(
                                        size.width,
                                        size.height,
                                        10.dp.toPx(),
                                        Color.Green,
                                        Color.Blue,
                                    )
                                }
                            }
                            Box(Modifier.fillMaxSize().background(Color.Red))
                            CompositionLocalProvider(LocalBackdropRenderEpoch provides 0) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .drawBackdrop(
                                            backdrop = source,
                                            shape = { RectangleShape },
                                            effects = { blur(8.dp.toPx()) },
                                            highlight = { Highlight.None },
                                            shadow = { Shadow.None },
                                            onDrawSurface = {
                                                drawRect(Color.White.copy(alpha = 0.2f))
                                            },
                                        ),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().drawWithContent {
                                            redrawToken.intValue
                                            drawContent()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        try {
            var warmed = StripePixels(0, 0)
            repeat(3) { frame ->
                warmed = renderStripePixels(scene, frame * 16_000_000L)
            }
            assertNotEquals(
                warmed.first,
                warmed.second,
                "Warm source should retain stripe contrast",
            )

            redrawToken.intValue++
            Snapshot.sendApplyNotifications()
            scene.sendPointerEvent(PointerEventType.Move, Offset(8f, 8f), timeMillis = 50)
            epoch.intValue++
            Snapshot.sendApplyNotifications()

            repeat(5) { frame ->
                assertEquals(
                    warmed,
                    renderStripePixels(scene, (frame + 3) * 16_000_000L),
                    "Sibling consumer changed during source rebuild frame $frame",
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun alphaZeroTabsRemainInCombinedBackdropAfterSourceRebuild() {
        val epoch = mutableIntStateOf(0)
        val redrawToken = mutableIntStateOf(0)
        val scene =
            ImageComposeScene(
                width = 160,
                height = 160,
                content = {
                    CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                        val page = rememberLayerBackdrop()
                        val tabs = rememberLayerBackdrop()
                        val combined = rememberCombinedBackdrop(page, tabs)
                        Box(Modifier.size(160.dp).graphicsLayer()) {
                            Box(Modifier.fillMaxSize().layerBackdrop(page)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawStripes(
                                        size.width,
                                        size.height,
                                        10.dp.toPx(),
                                        Color.Green,
                                        Color.Blue,
                                    )
                                }
                            }
                            Box(
                                Modifier.fillMaxSize()
                                    .alpha(0f)
                                    .graphicsLayer()
                                    .layerBackdrop(tabs),
                            ) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .drawBackdrop(
                                            backdrop = page,
                                            shape = { RectangleShape },
                                            effects = { blur(4.dp.toPx()) },
                                            highlight = { Highlight.None },
                                            shadow = { Shadow.None },
                                        ),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().drawWithContent {
                                            redrawToken.intValue
                                            drawContent()
                                        },
                                    )
                                }
                            }
                            CompositionLocalProvider(LocalBackdropRenderEpoch provides 0) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .drawBackdrop(
                                            backdrop = combined,
                                            shape = { RectangleShape },
                                            effects = { blur(8.dp.toPx()) },
                                            highlight = { Highlight.None },
                                            shadow = { Shadow.None },
                                        ),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().drawWithContent {
                                            redrawToken.intValue
                                            drawContent()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        try {
            var warmed = StripePixels(0, 0)
            repeat(3) { frame ->
                warmed = renderStripePixels(scene, frame * 16_000_000L)
            }
            assertNotEquals(
                warmed.first,
                warmed.second,
                "Warm combined source should retain stripe contrast",
            )

            redrawToken.intValue++
            Snapshot.sendApplyNotifications()
            scene.sendPointerEvent(PointerEventType.Move, Offset(8f, 8f), timeMillis = 50)
            epoch.intValue++
            Snapshot.sendApplyNotifications()

            repeat(5) { frame ->
                assertEquals(
                    warmed,
                    renderStripePixels(scene, (frame + 3) * 16_000_000L),
                    "Combined consumer changed during source rebuild frame $frame",
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun exportedBackdropWithEmptyEffectsKeepsItsRecordedPixelsAfterRebuild() {
        val epoch = mutableIntStateOf(0)
        val scene =
            ImageComposeScene(
                width = 160,
                height = 160,
                content = {
                    CompositionLocalProvider(LocalBackdropRenderEpoch provides epoch.intValue) {
                        val page = rememberLayerBackdrop()
                        val exported = rememberLayerBackdrop()
                        Box(Modifier.size(160.dp).background(Color.Red)) {
                            Box(Modifier.fillMaxSize().layerBackdrop(page)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawStripes(
                                        size.width,
                                        size.height,
                                        10.dp.toPx(),
                                        Color.Green,
                                        Color.Blue,
                                    )
                                }
                            }
                            Box(
                                Modifier.fillMaxSize()
                                    .drawBackdrop(
                                        backdrop = page,
                                        shape = { RectangleShape },
                                        effects = {},
                                        highlight = { Highlight.None },
                                        shadow = { Shadow.None },
                                        exportedBackdrop = exported,
                                        onDrawSurface = {
                                            drawStripes(
                                                size.width,
                                                size.height,
                                                10.dp.toPx(),
                                                Color.Yellow,
                                                Color.Cyan,
                                            )
                                        },
                                    ),
                            ) {
                                Box(Modifier.fillMaxSize().background(Color.Blue))
                                Box(
                                    Modifier.fillMaxSize()
                                        .drawBackdrop(
                                            backdrop = exported,
                                            shape = { RectangleShape },
                                            effects = {},
                                            highlight = { Highlight.None },
                                            shadow = { Shadow.None },
                                        ),
                                )
                            }
                        }
                    }
                },
            )
        try {
            var warmed = StripePixels(0, 0)
            repeat(3) { frame ->
                warmed = renderStripePixels(scene, frame * 16_000_000L)
            }
            assertNotEquals(
                warmed.first,
                warmed.second,
                "Warm exported source should retain stripe contrast",
            )
            assertNotEquals(
                warmed.first,
                Color.Red.toArgb(),
                "Consumer should sample exported pixels over the page",
            )

            epoch.intValue++
            Snapshot.sendApplyNotifications()
            repeat(5) { frame ->
                assertEquals(
                    warmed,
                    renderStripePixels(scene, (frame + 3) * 16_000_000L),
                    "Exported consumer changed during source rebuild frame $frame",
                )
            }
        } finally {
            scene.close()
        }
    }
}
