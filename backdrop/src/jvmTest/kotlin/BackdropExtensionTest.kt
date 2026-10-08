package top.ltfan.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.ltfan.backdrop.backdrops.LayerBackdrop
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.shadow.InnerShadow
import top.ltfan.backdrop.shadow.Shadow

/**
 * The effect region: how far a surface draws, how each side resolves, and where an exported
 * backdrop lands. Desktop tests run at density 1, so a dp and a px are the same number.
 */
@OptIn(ExperimentalTestApi::class)
class BackdropExtensionTest {
    @Test
    fun insetsExtendWhatTheSurfaceDraws() = runComposeUiTest {
        setContent { RegionScene(insets = { BackdropInsets.all(20.dp) }) }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        // The surface covers 50..150 of a 200 box, so its region covers 30..170.
        assertRed(pixels[100, 100], "surface center")
        assertRed(pixels[100, 40], "10dp above the surface, inside the region")
        assertRed(pixels[40, 100], "10dp left of the surface, inside the region")
        assertRed(pixels[100, 160], "10dp below the surface, inside the region")
        assertRed(pixels[160, 100], "10dp right of the surface, inside the region")
        assertBlack(pixels[100, 20], "30dp above the surface, outside the region")
        assertBlack(pixels[20, 100], "30dp left of the surface, outside the region")
        assertBlack(pixels[180, 100], "30dp right of the surface, outside the region")
    }

    @Test
    fun noInsetsKeepTheSurfaceRectangle() = runComposeUiTest {
        setContent { RegionScene(insets = { BackdropInsets.None }) }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        assertRed(pixels[100, 100], "surface center")
        assertBlack(pixels[100, 40], "above the surface")
        assertBlack(pixels[100, 160], "below the surface")
        assertBlack(pixels[40, 100], "left of the surface")
        assertBlack(pixels[160, 100], "right of the surface")
    }

    @Test
    fun eachSideResolvesSeparately() = runComposeUiTest {
        setContent {
            RegionScene(insets = { BackdropInsets.horizontal(left = 8.dp, right = 24.dp) })
        }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        assertBlack(pixels[100, 40], "a horizontal inset must not reach the vertical sides")
        assertRed(pixels[45, 100], "5dp left of the surface, inside the 8dp inset")
        assertBlack(pixels[30, 100], "20dp left of the surface, outside the 8dp inset")
        assertRed(pixels[165, 100], "15dp right of the surface, inside the 24dp inset")
        assertBlack(pixels[180, 100], "30dp right of the surface, outside the 24dp inset")
    }

    @Test
    fun samplingRoomAddsToTheRequestedExtension() = runComposeUiTest {
        var resolved = BackdropRoom(Size.Zero, BackdropExtension.None)
        setContent {
            Box(Modifier.size(200.dp).background(Color.Black).testTag("root"), Alignment.Center) {
                Box(
                    Modifier.size(100.dp)
                        .drawBackdrop(
                            backdrop = SolidBackdrop,
                            shape = { RectangleShape },
                            effects = {
                                // A blur reads its finite Gaussian kernel past each output edge.
                                blur(16f, TileMode.Decal)
                            },
                            insets = { BackdropInsets.all(4.dp) },
                            highlight = { Highlight.None },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                            onDrawSurface = { resolved = it },
                        )
                )
            }
        }
        waitForIdle()

        // The blur's 30-pixel support adds to the requested 4 on every side, and the
        // room reports the surface's own size even though the layer is larger.
        assertEquals(Size(100f, 100f), resolved.size)
        assertEquals(34f, resolved.extension.left)
        assertEquals(34f, resolved.extension.top)
        assertEquals(34f, resolved.extension.right)
        assertEquals(34f, resolved.extension.bottom)
        assertEquals(Offset(-34f, -34f), resolved.extension.originInNode)
    }

    @Test
    fun samplingHeadroomStaysInvisible() = runComposeUiTest {
        // The blur asks for 16 on each side while the caller asks to show 4: the extra room is for
        // sampling, so the surface still ends where the request does.
        setContent {
            Box(Modifier.size(200.dp).background(Color.Black).testTag("root"), Alignment.Center) {
                Box(
                    Modifier.size(100.dp)
                        .drawBackdrop(
                            backdrop = SolidBackdrop,
                            shape = { RectangleShape },
                            effects = { blur(16f, TileMode.Decal) },
                            insets = { BackdropInsets.all(4.dp) },
                            highlight = { Highlight.None },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        // The surface covers 50..150, so 3dp above it is shown and 6dp above it is not, even though
        // the blur's headroom reaches 16dp.
        assertRed(pixels[100, 47], "3dp above the surface, inside the request")
        assertBlack(pixels[100, 44], "6dp above the surface, inside the blur's headroom only")
    }

    @Test
    fun aRoundedSurfaceClipsItsContentToTheShape() = runComposeUiTest {
        setContent {
            Box(Modifier.size(200.dp).background(Color.Black), Alignment.Center) {
                Box(
                    Modifier.size(100.dp)
                        .drawBackdrop(
                            backdrop = SolidBackdrop,
                            shape = { RoundedCornerShape(40.dp) },
                            effects = BackdropStyle.NoEffects,
                            highlight = { Highlight.None },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                ) {
                    Box(Modifier.size(100.dp).background(Color.Green).testTag("content"))
                }
            }
        }
        val pixels = onNodeWithTag("content").captureToImage().toPixelMap()

        // The corner falls outside the shape: neither the surface nor its content draws there.
        assertBlack(pixels[1, 1], "clipped corner")
        // The shape's straight edge keeps both, so the content covers the surface there.
        assertEquals(Color.Green, pixels[50, 1], "straight top edge")
        assertEquals(Color.Green, pixels[50, 50], "center")
    }

    @Test
    fun anExportedBackdropKeepsItsGeometryAcrossInsets() = runComposeUiTest {
        lateinit var exported: LayerBackdrop
        setContent {
            exported = rememberLayerBackdrop()
            Box(Modifier.size(200.dp).background(Color.Black).testTag("root")) {
                // The backdrop marks the producer's own 10..20 square, so a sampled offset shows.
                Box(
                    Modifier.size(60.dp)
                        .drawPlainBackdrop(
                            backdrop = MarkerBackdrop,
                            shape = { RectangleShape },
                            insets = { BackdropInsets.all(10.dp) },
                            exportedBackdrop = exported,
                        )
                )
                // Sits 10dp right and down of the producer, so its first pixels sample the marker.
                Box(
                    Modifier.size(20.dp)
                        .offset(x = 10.dp, y = 10.dp)
                        .testTag("consumer")
                        .drawPlainBackdrop(
                            backdrop = exported,
                            shape = { RectangleShape },
                            effects = BackdropStyle.NoEffects,
                        )
                )
            }
        }
        val pixels = onNodeWithTag("consumer").captureToImage().toPixelMap()

        assertEquals(Color.White, pixels[2, 2], "the consumer must sample the marker square")
        assertTrue(pixels[16, 16].red > 0.9f, "and the producer's red past it: ${pixels[16, 16]}")
    }

    @Test
    fun changingInsetsAndShadowOffsetsUpdateDrawingBounds() = runComposeUiTest {
        val extended = mutableStateOf(false)
        val shadowed = mutableStateOf(false)
        setContent {
            val background = rememberLayerBackdrop()
            Box(Modifier.size(240.dp).testTag("root")) {
                Box(Modifier.fillMaxSize().layerBackdrop(background))
                Box(
                    Modifier.offset(80.dp, 80.dp)
                        .size(80.dp)
                        .drawBackdrop(
                            background,
                            { RectangleShape },
                            effects = {},
                            insets = {
                                if (extended.value) BackdropInsets.all(24.dp)
                                else BackdropInsets.None
                            },
                            highlight = { Highlight.None },
                            innerShadow = { InnerShadow.None },
                            shadow = {
                                if (shadowed.value)
                                    Shadow.Config(
                                        radius = 8.dp,
                                        offset = DpOffset((-16).dp, (-16).dp),
                                        color = Color.White,
                                    )
                                else Shadow.None
                            },
                            onDrawSurface = { room ->
                                drawRect(
                                    Color.Red,
                                    room.extension.originInNode,
                                    Size(
                                        size.width + room.extension.left + room.extension.right,
                                        size.height + room.extension.top + room.extension.bottom,
                                    ),
                                )
                            },
                        )
                )
            }
        }
        fun pixels() = onNodeWithTag("root").captureToImage().toPixelMap()
        assertEquals(0f, pixels()[60, 120].alpha)
        runOnIdle { extended.value = true }
        assertTrue(pixels()[60, 120].red > .99f)
        runOnIdle {
            extended.value = false
            shadowed.value = true
        }
        assertTrue(
            pixels()[70, 100].alpha > .05f,
            "Drawing bounds include the negative horizontal shadow offset",
        )
        assertTrue(
            pixels()[100, 70].alpha > .05f,
            "Drawing bounds include the negative vertical shadow offset",
        )
        runOnIdle { shadowed.value = false }
        assertEquals(0f, pixels()[70, 100].alpha)
    }

    @Composable
    private fun RegionScene(insets: () -> BackdropInsets) {
        Box(Modifier.size(200.dp).background(Color.Black).testTag("root"), Alignment.Center) {
            Box(
                Modifier.size(100.dp)
                    .drawBackdrop(
                        backdrop = SolidBackdrop,
                        shape = { RectangleShape },
                        effects = BackdropStyle.NoEffects,
                        insets = insets,
                        highlight = { Highlight.None },
                        shadow = { Shadow.None },
                        innerShadow = { InnerShadow.None },
                    )
            )
        }
    }

    private fun assertRed(color: Color, where: String) {
        assertTrue(color.red > 0.9f && color.green < 0.1f && color.blue < 0.1f, "$where: $color")
    }

    private fun assertBlack(color: Color, where: String) {
        assertTrue(color.red < 0.1f && color.green < 0.1f && color.blue < 0.1f, "$where: $color")
    }

    @Test
    fun aShapeThatIsAPathKeepsItsOwnOutlineWithoutAnExtension() = runComposeUiTest {
        setContent {
            Box(Modifier.size(200.dp).background(Color.Black).testTag("root"), Alignment.Center) {
                Box(
                    Modifier.size(100.dp)
                        .drawBackdrop(
                            backdrop = SolidBackdrop,
                            shape = { TriangleShape },
                            effects = BackdropStyle.NoEffects,
                            highlight = { Highlight.None },
                            shadow = { Shadow.None },
                            innerShadow = { InnerShadow.None },
                        )
                )
            }
        }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()

        // A continuous-corner shape is a path, and a surface that does not extend covers exactly
        // it:
        // its corners stay clear instead of being filled in as a rectangle.
        assertBlack(pixels[54, 54], "the top-left corner of the surface, outside its path")
        assertBlack(pixels[146, 54], "the top-right corner, outside its path")
        assertRed(pixels[100, 140], "inside the path")
    }
}

/** A shape whose outline is a path, the kind a continuous-corner shape produces. */
private val TriangleShape =
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

private val SolidBackdrop =
    object : Backdrop {
        override val isCoordinatesDependent: Boolean = false

        override fun BackdropDrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?,
        ) {
            // A source that samples a page covers every direction around the surface; a flat color
            // has to say so itself, or the region's leading sides stay empty.
            drawRect(
                Color.Red,
                topLeft = Offset(-size.width, -size.height),
                size = Size(size.width * 3f, size.height * 3f),
            )
        }
    }

private val MarkerBackdrop =
    object : Backdrop {
        override val isCoordinatesDependent: Boolean = false

        override fun BackdropDrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?,
        ) {
            drawRect(
                Color.Red,
                topLeft = Offset(-size.width, -size.height),
                size = Size(size.width * 3f, size.height * 3f),
            )
            // In the producer's own space, which is what a consumer must land back on.
            drawRect(Color.White, topLeft = Offset(10f, 10f), size = Size(10f, 10f))
        }
    }
