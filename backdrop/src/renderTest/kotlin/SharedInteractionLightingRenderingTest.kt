package top.ltfan.backdrop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.highlight.Highlight
import top.ltfan.backdrop.highlight.HighlightColorResponse
import top.ltfan.backdrop.highlight.HighlightDirection
import top.ltfan.backdrop.highlight.HighlightResponse
import top.ltfan.backdrop.highlight.HighlightStyle
import top.ltfan.backdrop.highlight.highlight
import top.ltfan.backdrop.interaction.InteractionLight
import top.ltfan.backdrop.interaction.InteractionLighting
import top.ltfan.backdrop.interaction.InteractionRegistry
import top.ltfan.backdrop.interaction.LocalInteractionRegistry
import top.ltfan.backdrop.interaction.ProvideInteractionLighting
import top.ltfan.backdrop.interaction.interactionLight
import top.ltfan.backdrop.interaction.interactionLightSource

class SharedInteractionLightingRenderingTest {
    @Test
    fun nestedSourcesShareOnePhysicalPointerContribution() = runRenderTest {
        lateinit var registry: InteractionRegistry
        setContent {
            ProvideInteractionLighting {
                registry = LocalInteractionRegistry.current!!
                Box(Modifier.size(120.dp).testTag("nested-pointer-root")) {
                    Box(
                        Modifier.fillMaxSize().interactionLightSource {
                            InteractionLight.Config(color = Color.Red, radius = 100.dp)
                        },
                    ) {
                        Box(
                            Modifier.offset(20.dp, 20.dp).size(80.dp).interactionLightSource {
                                InteractionLight.Config(color = Color.Blue, radius = 100.dp)
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithTag("nested-pointer-root").performTouchInput {
            down(position = Offset(60f, 60f))
        }
        waitForIdle()
        assertEquals(
            1,
            registry.lights.size,
            "nested sources contribute one light for the same physical pointer",
        )
    }

    @Test
    fun distinctPointersKeepDistinctNestedContributions() = runRenderTest {
        lateinit var registry: InteractionRegistry
        setContent {
            ProvideInteractionLighting {
                registry = LocalInteractionRegistry.current!!
                Box(Modifier.size(120.dp).testTag("multi-pointer-root")) {
                    Box(
                        Modifier.fillMaxSize().interactionLightSource {
                            InteractionLight.Config(color = Color.Red, radius = 100.dp)
                        },
                    ) {
                        Box(
                            Modifier.offset(20.dp, 20.dp).size(80.dp).interactionLightSource {
                                InteractionLight.Config(color = Color.Blue, radius = 100.dp)
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithTag("multi-pointer-root").performTouchInput {
            down(pointerId = 1, position = Offset(40f, 40f))
            down(pointerId = 2, position = Offset(80f, 80f))
        }
        waitForIdle()
        assertEquals(2, registry.lights.size, "different pointer ids retain separate contributions")
    }

    @Test
    fun movingPointerAfterNestedChildDetachesRestoresParentContribution() = runRenderTest {
        lateinit var registry: InteractionRegistry
        val showChild = mutableStateOf(true)
        setContent {
            ProvideInteractionLighting {
                registry = LocalInteractionRegistry.current!!
                Box(Modifier.size(120.dp).testTag("child-exit-root")) {
                    Box(
                        Modifier.fillMaxSize().interactionLightSource {
                            InteractionLight.Config(color = Color.Red, radius = 100.dp)
                        },
                    ) {
                        if (showChild.value) {
                            Box(
                                Modifier.offset(20.dp, 20.dp).size(80.dp).interactionLightSource {
                                    InteractionLight.Config(color = Color.Blue, radius = 100.dp)
                                },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("child-exit-root").performTouchInput {
            down(position = Offset(60f, 60f))
        }
        waitForIdle()
        assertEquals(1, registry.lights.size, "the child owns the shared contribution")

        runOnIdle { showChild.value = false }
        waitForIdle()
        assertEquals(0, registry.lights.size, "detaching the winning child removes its entry")
        onNodeWithTag("child-exit-root").performTouchInput { moveTo(Offset(70f, 65f)) }
        waitForIdle()
        assertEquals(
            1,
            registry.lights.size,
            "the parent restores its entry on the next pointer move",
        )
    }

    @Test
    fun movingPointerAfterNestedChildBecomesNoneRestoresParentContribution() = runRenderTest {
        lateinit var registry: InteractionRegistry
        val childLight =
            mutableStateOf<InteractionLight>(
                InteractionLight.Config(color = Color.Blue, radius = 100.dp),
            )
        setContent {
            ProvideInteractionLighting {
                registry = LocalInteractionRegistry.current!!
                Box(Modifier.size(120.dp).testTag("child-none-root")) {
                    Box(
                        Modifier.fillMaxSize().interactionLightSource {
                            InteractionLight.Config(color = Color.Red, radius = 100.dp)
                        },
                    ) {
                        Box(
                            Modifier.offset(20.dp, 20.dp).size(80.dp).interactionLightSource {
                                childLight.value
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("child-none-root").performTouchInput {
            down(position = Offset(60f, 60f))
        }
        waitForIdle()
        assertEquals(1, registry.lights.size, "nested sources share one active pointer light")

        runOnIdle { childLight.value = InteractionLight.None }
        waitForIdle()
        assertEquals(0, registry.lights.size, "None clears the child's winning entry")
        onNodeWithTag("child-none-root").performTouchInput { moveTo(Offset(70f, 65f)) }
        waitForIdle()
        assertEquals(1, registry.lights.size, "the parent restores its entry on the next move")
        assertEquals(Color.Red, registry.lights.single().color)
    }

    @Test
    fun clearingParentSourceDoesNotRemoveNestedChildWinner() = runRenderTest {
        lateinit var registry: InteractionRegistry
        val parentLight =
            mutableStateOf<InteractionLight>(
                InteractionLight.Config(color = Color.Red, radius = 100.dp),
            )
        setContent {
            ProvideInteractionLighting {
                registry = LocalInteractionRegistry.current!!
                Box(Modifier.size(120.dp).testTag("parent-exit-root")) {
                    Box(Modifier.fillMaxSize().interactionLightSource { parentLight.value }) {
                        Box(
                            Modifier.offset(20.dp, 20.dp).size(80.dp).interactionLightSource {
                                InteractionLight.Config(color = Color.Blue, radius = 100.dp)
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("parent-exit-root").performTouchInput {
            down(position = Offset(60f, 60f))
        }
        waitForIdle()
        val childEntry = registry.lights.single()

        runOnIdle { parentLight.value = InteractionLight.None }
        waitForIdle()
        assertEquals(1, registry.lights.size, "parent cleanup leaves the child's contribution")
        assertSame(childEntry, registry.lights.single(), "the child winner remains registered")
    }

    @Test
    fun repeatedPointerGestureSurvivesPreviousFade() = runRenderTest {
        mainClock.autoAdvance = false
        lateinit var registry: InteractionRegistry
        setContent {
            ProvideInteractionLighting {
                registry = LocalInteractionRegistry.current!!
                Box(Modifier.size(120.dp).testTag("pointer-reuse-root")) {
                    Box(
                        Modifier.fillMaxSize().interactionLightSource {
                            InteractionLight.Config(
                                color = Color.Red,
                                radius = 100.dp,
                                releaseDuration = 120.milliseconds,
                            )
                        },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("pointer-reuse-root").performTouchInput {
            down(pointerId = 1, position = Offset(30f, 30f))
        }
        waitForIdle()
        onNodeWithTag("pointer-reuse-root").performTouchInput { up(pointerId = 1) }
        waitForIdle()
        onNodeWithTag("pointer-reuse-root").performTouchInput {
            down(pointerId = 1, position = Offset(80f, 80f))
        }
        waitForIdle()
        assertEquals(if (touchGesturesReuseComposePointerIds) 1 else 2, registry.lights.size)
        val replacement = registry.lights.last()
        mainClock.advanceTimeBy(200)
        waitForIdle()
        assertEquals(
            1,
            registry.lights.size,
            "the replacement remains registered after the old fade",
        )
        assertSame(
            replacement,
            registry.lights.single(),
            "the previous fade cannot remove the new gesture entry",
        )
    }

    @Test
    fun pressLightsASeparateReceiverAndReleaseFadesToBaseline() = runRenderTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Red, radius = 160.dp)
                        },
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val capture = { onNodeWithTag("root").captureToImage().toPixelMap() }
        val baseline = capture()[140, 120]

        runOnIdle { source.tryEmit(press) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val lit = capture()[140, 120]
        assertTrue(
            lit.red > baseline.red + .08f,
            "a press lights the separate receiver in red: $lit vs $baseline",
        )

        runOnIdle { source.tryEmit(PressInteraction.Release(press)) }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        val released = capture()[140, 120]
        assertTrue(
            released.red < lit.red - .05f && released.red < baseline.red + .08f,
            "the configured release animation returns to baseline: $released vs $lit",
        )
    }

    @Test
    fun receiverWithoutASourceReceivesTheGlobalLight() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Green, radius = 160.dp)
                        },
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val baseline = onNodeWithTag("root").captureToImage().toPixelMap()[140, 120]
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val lit = onNodeWithTag("root").captureToImage().toPixelMap()[140, 120]
        assertTrue(
            lit.green > baseline.green + .08f,
            "a pure receiver receives a scoped source light",
        )
    }

    @Test
    fun isolatedScopeBlocksParentLightAndNonIsolatedScopeReusesIt() = runRenderTest {
        val parentSource = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        val isolate = mutableStateOf(true)
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(260.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 100.dp).size(40.dp).interactionLightSource(
                            parentSource,
                        ) {
                            InteractionLight.Config(color = Color.Blue, radius = 180.dp)
                        },
                    )
                    ProvideInteractionLighting(isolate = isolate.value) {
                        Box(
                            Modifier.offset(100.dp, 100.dp)
                                .size(80.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RectangleShape },
                                    render = {
                                        val material = image {
                                            source()
                                            clipSurface()
                                        }
                                        group {
                                            draw(material)

                                            interactionLight(
                                                InteractionLighting.Config(
                                                    interiorOpacity = .8f,
                                                ),
                                                blendMode = BlendMode.Plus,
                                            )
                                        }
                                        foreground {}
                                    },
                                ),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val capture = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 140] }
        runOnIdle { parentSource.tryEmit(press) }
        waitForIdle()
        val isolated = capture()
        assertTrue(
            isolated.blue < .05f,
            "the isolated child registry does not see the parent source",
        )

        runOnIdle { isolate.value = false }
        waitForIdle()
        val shared = capture()
        assertTrue(shared.blue > isolated.blue + .08f, "isolate=false reuses the parent registry")
    }

    @Test
    fun interiorGlowDrawsAboveSurfaceTintAndBehindForegroundContent() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Red, radius = 180.dp)
                        },
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)
                                        paintSurface { drawRect(Color.Blue) }

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    ) {
                        Box(Modifier.offset(30.dp, 30.dp).size(20.dp).background(Color.Green))
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        val glowOverTint = pixels[110, 90]
        val foreground = pixels[140, 120]
        assertTrue(
            glowOverTint.red > .2f && glowOverTint.blue > .95f,
            "interior glow adds red radiance over the blue surface tint: $glowOverTint",
        )
        assertTrue(
            foreground.green > .9f && foreground.red < .1f,
            "foreground content stays above the glow",
        )
    }

    @Test
    fun disablingAndDetachingASourceRemoveItsActivePress() = runRenderTest {
        val source = MutableInteractionSource()
        val light =
            mutableStateOf<InteractionLight>(
                InteractionLight.Config(color = Color.Red, radius = 160.dp),
            )
        val attached = mutableStateOf(true)
        val press = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    if (attached.value) {
                        Box(
                            Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(
                                source,
                            ) {
                                light.value
                            },
                        )
                    }
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        val baseline = sample()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val lit = sample()
        assertTrue(
            lit.red > baseline.red + .08f,
            "the active press initially illuminates the receiver",
        )

        runOnIdle { light.value = InteractionLight.None }
        waitForIdle()
        assertTrue(sample().red < lit.red - .05f, "None removes the active source light")

        runOnIdle { light.value = InteractionLight.Default }
        waitForIdle()
        assertTrue(
            sample().red < lit.red - .05f,
            "reenabling does not revive a press already disabled",
        )
        val nextPress = PressInteraction.Press(Offset(20f, 20f))
        runOnIdle { source.tryEmit(nextPress) }
        waitForIdle()
        assertTrue(sample().red > baseline.red + .08f, "a new press is received after reenable")

        runOnIdle { attached.value = false }
        waitForIdle()
        assertTrue(sample().red < lit.red - .05f, "detaching removes the owner's active light")
    }

    @Test
    fun switchingInteractionSourcesClearsTheOldSource() = runRenderTest {
        val first = MutableInteractionSource()
        val second = MutableInteractionSource()
        val useSecond = mutableStateOf(false)
        val firstPress = PressInteraction.Press(Offset(20f, 20f))
        val secondPress = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(
                            if (useSecond.value) second else first,
                        ) {
                            InteractionLight.Config(color = Color.Red, radius = 160.dp)
                        },
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        val baseline = sample()
        runOnIdle { first.tryEmit(firstPress) }
        waitForIdle()
        val lit = sample()
        assertTrue(lit.red > baseline.red + .08f, "the first interaction source is active")

        runOnIdle { useSecond.value = true }
        waitForIdle()
        assertTrue(sample().red < lit.red - .05f, "switching sources cancels the old active press")
        runOnIdle { second.tryEmit(secondPress) }
        waitForIdle()
        assertTrue(
            sample().red > baseline.red + .08f,
            "the replacement source can illuminate the receiver",
        )
    }

    @Test
    fun unspecifiedPressPositionUsesTheSourceCenter() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset.Unspecified)
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Yellow, radius = 160.dp)
                        },
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val center = onNodeWithTag("root").captureToImage().toPixelMap()[140, 120]
        assertTrue(
            center.red > .05f && center.green > .05f,
            "an unspecified press uses the source center",
        )
    }

    @Test
    fun rawPointerSourceTracksPointerMovementAndMultiplePointerPositions() = runRenderTest {
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(260.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 100.dp)
                            .size(60.dp)
                            .testTag("raw-source")
                            .interactionLightSource(interactionSource = null) {
                                InteractionLight.Config(color = Color.Red, radius = 120.dp)
                            },
                    )
                    Box(
                        Modifier.offset(130.dp, 100.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[145, 140] }
        val baseline = sample()
        onNodeWithTag("raw-source").performTouchInput {
            down(pointerId = 0, position = Offset(10f, 30f))
            down(pointerId = 1, position = Offset(30f, 30f))
        }
        waitForIdle()
        val near = sample()
        assertTrue(
            near.red > baseline.red + .08f,
            "a raw pointer press creates an interaction light: $near vs $baseline",
        )

        onNodeWithTag("raw-source").performTouchInput {
            moveTo(pointerId = 1, position = Offset(58f, 30f))
        }
        waitForIdle()
        val moved = sample()
        assertTrue(
            moved.red > near.red + .01f,
            "the moved pointer receives its own current position: $moved vs $near",
        )
    }

    @Test
    fun interactionContourRemainsWhenBaseDirectionalStrengthIsZero() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(200.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Red, radius = 120.dp)
                        },
                    )
                    Box(
                        Modifier.offset(80.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = 0f,
                                                contourOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {
                                        highlight(
                                            Highlight(
                                                width = 10.dp,
                                                style = HighlightStyle.Solid(Color.White),
                                                direction =
                                                    HighlightDirection(
                                                        strength = HighlightResponse(0f, 0f),
                                                        widthScale = HighlightResponse(1f, 1f),
                                                    ),
                                                opacity = 1f,
                                            ),
                                            colorSource = material,
                                            interactionLighting =
                                                InteractionLighting.Config(
                                                    interiorOpacity = 0f,
                                                    contourOpacity = .8f,
                                                ),
                                        )
                                    }
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val baseline = onNodeWithTag("root").captureToImage().toPixelMap()[81, 120]
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val lit = onNodeWithTag("root").captureToImage().toPixelMap()[81, 120]
        assertTrue(
            lit.red > baseline.red + .08f,
            "interaction contour remains active with a zero-strength base style: $lit vs $baseline",
        )
    }

    @Test
    fun sharedLightColorsTwoToneBlackSideAndExclusionsStillMaskTheContour() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(10f, 10f))
        val exclusions = mutableStateOf(emptySet<ClosedFloatingPointRange<Float>>())
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(240.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(95.dp, 95.dp).size(20.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Red, radius = 180.dp)
                        },
                    )
                    Box(
                        Modifier.offset(80.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = 0f,
                                                contourOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {
                                        highlight(
                                            Highlight(
                                                width = 10.dp,
                                                style = HighlightStyle.TwoTone(Color.White),
                                                direction =
                                                    HighlightDirection(
                                                        angle = 270f,
                                                        strength = HighlightResponse(0f, 0f),
                                                        widthScale = HighlightResponse(1f, 1f),
                                                    ),
                                                opacity = 1f,
                                                disabledAngles = exclusions.value,
                                            ),
                                            colorSource = material,
                                            interactionLighting =
                                                InteractionLighting.Config(
                                                    interiorOpacity = 0f,
                                                    contourOpacity = .8f,
                                                ),
                                        )
                                    }
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap() }
        val baseline = sample()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val illuminated = sample()
        val blackSide = illuminated[120, 158]
        val topSide = illuminated[120, 81]
        assertTrue(
            blackSide.red > baseline[120, 158].red + .08f,
            "the shared red light reaches TwoTone's black side: $blackSide",
        )
        assertTrue(
            topSide.red > baseline[120, 81].red + .08f,
            "the top side is lit before exclusion",
        )

        runOnIdle { exclusions.value = setOf(0f..45f, 315f..360f) }
        waitForIdle()
        val masked = sample()
        assertTrue(
            masked[120, 81].red < topSide.red - .05f,
            "the top direction exclusion suppresses interaction contour coverage",
        )
        assertTrue(
            masked[120, 158].red > masked[120, 81].red + .05f,
            "the opposite TwoTone side remains illuminated",
        )
    }

    @Test
    fun rawKeyboardPressesAreClearedWhenFocusMovesBeforeKeyUpElsewhere() = runRenderTest {
        val sourceFocus = FocusRequester()
        val otherFocus = FocusRequester()
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp)
                            .size(40.dp)
                            .focusRequester(sourceFocus)
                            .interactionLightSource(interactionSource = null) {
                                InteractionLight.Config(color = Color.Magenta, radius = 160.dp)
                            }
                            .focusable()
                            .testTag("keyboard-source"),
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                    Box(
                        Modifier.offset(10.dp, 10.dp)
                            .size(20.dp)
                            .focusRequester(otherFocus)
                            .focusable()
                            .testTag("other-focus"),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        val baseline = sample()
        runOnIdle { sourceFocus.requestFocus() }
        waitForIdle()
        val focused = sample()
        assertTrue(
            focused.red < baseline.red + .05f && focused.blue < baseline.blue + .05f,
            "raw focus alone does not create a light: $focused",
        )

        onNodeWithTag("keyboard-source").performKeyInput {
            keyDown(Key.Enter)
            keyDown(Key.Spacebar)
        }
        waitForIdle()
        assertTrue(sample().red > .05f, "Enter and Space key-downs retain the focused source light")

        runOnIdle { otherFocus.requestFocus() }
        onNodeWithTag("other-focus").performKeyInput {
            keyUp(Key.Enter)
            keyUp(Key.Spacebar)
        }
        waitForIdle()
        val afterFocusLoss = sample()
        assertTrue(
            afterFocusLoss.red < baseline.red + .05f && afterFocusLoss.blue < baseline.blue + .05f,
            "focus loss clears key lights even when key-up arrives at another node: $afterFocusLoss",
        )
    }

    @Test
    fun switchingToRawModeDoesNotCreateLightFromExistingFocus() = runRenderTest {
        val focus = FocusRequester()
        val externalSource = MutableInteractionSource()
        val externalFocus = FocusInteraction.Focus()
        val interactionSource = mutableStateOf<MutableInteractionSource?>(externalSource)
        val light =
            mutableStateOf<InteractionLight>(
                InteractionLight.Config(color = Color.Green, radius = 160.dp),
            )
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp)
                            .size(40.dp)
                            .focusRequester(focus)
                            .interactionLightSource(interactionSource.value) { light.value }
                            .focusable()
                            .testTag("switching-focus-source"),
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        runOnIdle { focus.requestFocus() }
        runOnIdle { externalSource.tryEmit(externalFocus) }
        waitForIdle()
        val externalMode = sample()
        assertTrue(externalMode.green > .05f, "external focus interaction emits a light")
        runOnIdle { interactionSource.value = null }
        mainClock.advanceTimeBy(250)
        waitForIdle()
        val rawMode = sample()
        assertTrue(
            rawMode.green < externalMode.green - .05f,
            "switching to raw mode clears external focus light: $rawMode vs $externalMode",
        )

        runOnIdle { light.value = InteractionLight.None }
        waitForIdle()
        val disabled = sample()
        runOnIdle { light.value = InteractionLight.Default }
        waitForIdle()
        val reenabled = sample()
        assertTrue(disabled.green < rawMode.green + .05f, "None leaves raw focus unlit")
        assertTrue(
            reenabled.green < disabled.green + .05f,
            "Default does not recreate a light from existing raw focus",
        )
    }

    @Test
    fun ancestorPreviewKeyActivatesRawLightWhenChildConsumesKey() = runRenderTest {
        val focus = FocusRequester()
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp)
                            .size(40.dp)
                            .interactionLightSource(interactionSource = null) {
                                InteractionLight.Config(color = Color.Magenta, radius = 160.dp)
                            }
                            .testTag("ancestor-key-source"),
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .focusRequester(focus)
                                .focusable()
                                .clickable {}
                                .testTag("consuming-child"),
                        )
                    }
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        runOnIdle { focus.requestFocus() }
        waitForIdle()
        val focused = sample()
        assertTrue(focused.red < .05f && focused.blue < .05f, "raw focus alone stays unlit")

        onNodeWithTag("consuming-child").performKeyInput { keyDown(Key.Enter) }
        waitForIdle()
        val activated = sample()
        assertTrue(
            activated.red > .05f && activated.blue > .05f,
            "ancestor preview sees activation before the child consumes it: $activated",
        )
    }

    @Test
    fun drawPlainBackdropDoesNotReceiveStyleInteractionLighting() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(20f, 20f))
        setContent {
            ProvideInteractionLighting {
                ProvideBackdropRenderStyle(
                    render = {
                        interactionLight(
                            InteractionLighting.Config(interiorOpacity = .8f),
                            blendMode = BlendMode.Plus,
                        )
                    },
                ) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                        Box(
                            Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(
                                source,
                            ) {
                                InteractionLight.Config(color = Color.Red, radius = 160.dp)
                            },
                        )
                        Box(
                            Modifier.offset(100.dp, 80.dp)
                                .size(80.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RectangleShape },
                                    render = {
                                        val material = image {
                                            source()
                                            effects {}
                                            clipSurface()
                                        }
                                        group {
                                            draw(material)
                                            paintSurface { drawRect(Color.Blue) }
                                        }
                                    },
                                ),
                        )
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val pixel = onNodeWithTag("root").captureToImage().toPixelMap()[140, 120]
        assertTrue(
            pixel.blue > .95f && pixel.red < .05f,
            "plain backdrop draws its blue surface without shared-light tint",
        )
    }

    @Test
    fun interiorLightIsClippedToOriginalRoundedShapeWithPositiveInsets() = runRenderTest {
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(10f, 10f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(80.dp, 80.dp).size(20.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Red, radius = 80.dp)
                        },
                    )
                    Box(
                        Modifier.offset(80.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(30.dp) },
                                insets = { BackdropInsets.all(20.dp) },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        val roundedCorner = pixels[81, 81]
        val interior = pixels[110, 110]
        assertTrue(interior.red > .05f, "the rounded surface interior receives its light")
        assertTrue(
            roundedCorner.red < .02f,
            "positive insets do not extend interior glow beyond the original rounded shape: $roundedCorner",
        )
    }

    @Test
    fun delayedPressUsesTheCurrentMovedPointerPosition() = runRenderTest {
        val source = MutableInteractionSource()
        val initialPosition = Offset(10f, 20f)
        val movedPosition = Offset(80f, 20f)
        val press = PressInteraction.Press(initialPosition)
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp, 100.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    Box(
                        Modifier.offset(20.dp, 20.dp)
                            .size(120.dp, 50.dp)
                            .interactionLightSource(source) {
                                InteractionLight.Config(color = Color.Red, radius = 12.dp)
                            }
                            .testTag("delayed-source"),
                    )
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .9f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val before = onNodeWithTag("root").captureToImage().toPixelMap()
        onNodeWithTag("delayed-source").performTouchInput {
            down(position = initialPosition)
            moveTo(position = movedPosition)
        }
        waitForIdle()
        runOnIdle { source.tryEmit(press) }
        waitForIdle()

        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        val currentPointer = pixels[100, 40]
        val originalPointer = pixels[30, 40]
        assertTrue(
            currentPointer.red > before[100, 40].red + .3f && originalPointer.red < .05f,
            "delayed press should follow the moved pointer; current=$currentPointer, " +
                "original=$originalPointer, beforeCurrent=${before[100, 40]}",
        )
    }

    @Test
    fun eachExternalFocusInteractionIsPairedWithItsOwnUnfocus() = runRenderTest {
        val source = MutableInteractionSource()
        val focusA = FocusInteraction.Focus()
        val focusB = FocusInteraction.Focus()
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp).size(40.dp).interactionLightSource(source) {
                            InteractionLight.Config(color = Color.Red, radius = 160.dp)
                        },
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        runOnIdle {
            source.tryEmit(focusA)
            source.tryEmit(focusB)
        }
        waitForIdle()
        val bothFocused = sample()
        assertTrue(bothFocused.red > .05f, "the source emits lights for both focus identities")

        runOnIdle { source.tryEmit(FocusInteraction.Unfocus(focusA)) }
        waitForIdle()
        val oneFocused = sample()
        assertTrue(
            oneFocused.red > .05f,
            "unfocusing A leaves B's focus light active: $oneFocused",
        )

        runOnIdle { source.tryEmit(FocusInteraction.Unfocus(focusB)) }
        waitForIdle()
        val noneFocused = sample()
        assertTrue(
            noneFocused.red < .05f,
            "unfocusing B clears both focus lights: $noneFocused",
        )
    }

    @Test
    fun pointerInputEndsExternalFocusUntilKeyboardInputResetsItsOwner() = runRenderTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        val focus = FocusInteraction.Focus()
        val focusRequester = FocusRequester()
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp)
                            .size(40.dp)
                            .focusRequester(focusRequester)
                            .interactionLightSource(source) {
                                InteractionLight.Config(
                                    color = Color.Red,
                                    radius = 160.dp,
                                    releaseDuration = 400.milliseconds,
                                )
                            }
                            .focusable()
                            .testTag("external-focus-source"),
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        runOnIdle { source.tryEmit(focus) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val externallyFocused = sample()
        assertTrue(externallyFocused.red > .05f, "external focus creates a light")

        onNodeWithTag("external-focus-source").performTouchInput {
            down(center)
            up()
        }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        val afterPointer = sample()
        assertTrue(
            afterPointer.red < externallyFocused.red - .05f,
            "pointer input ends prior external focus light: $afterPointer",
        )

        onNodeWithTag("external-focus-source").performKeyInput { keyDown(Key.A) }
        runOnIdle { source.tryEmit(FocusInteraction.Focus()) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        assertTrue(
            sample().red > afterPointer.red + .05f,
            "preview key input permits later focus light",
        )
    }

    @Test
    fun pointerDrivenFocusSuppressionSurvivesFocusLossUntilPreviewKeyDown() = runRenderTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        val sourceFocus = FocusRequester()
        val otherFocus = FocusRequester()
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(220.dp).background(Color.Black).testTag("root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    Box(
                        Modifier.offset(20.dp, 80.dp)
                            .size(40.dp)
                            .focusRequester(sourceFocus)
                            .interactionLightSource(source) {
                                InteractionLight.Config(color = Color.Red, radius = 160.dp)
                            }
                            .focusable()
                            .testTag("focus-history-source"),
                    )
                    Box(
                        Modifier.offset(10.dp, 10.dp)
                            .size(20.dp)
                            .focusRequester(otherFocus)
                            .focusable()
                            .testTag("other-focus"),
                    )
                    Box(
                        Modifier.offset(100.dp, 80.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        val sample = { onNodeWithTag("root").captureToImage().toPixelMap()[140, 120] }
        runOnIdle { sourceFocus.requestFocus() }
        runOnIdle { source.tryEmit(FocusInteraction.Focus()) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val initiallyFocused = sample()
        assertTrue(initiallyFocused.red > .05f, "external focus initially creates a light")

        onNodeWithTag("focus-history-source").performTouchInput {
            down(center)
            up()
        }
        mainClock.advanceTimeBy(500)
        runOnIdle { otherFocus.requestFocus() }
        runOnIdle { sourceFocus.requestFocus() }
        runOnIdle { source.tryEmit(FocusInteraction.Focus()) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val afterProgrammaticRefocus = sample()
        assertTrue(
            afterProgrammaticRefocus.red < .05f,
            "programmatic refocus stays suppressed after pointer input: $afterProgrammaticRefocus",
        )

        onNodeWithTag("focus-history-source").performKeyInput { keyDown(Key.A) }
        runOnIdle { source.tryEmit(FocusInteraction.Focus()) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        assertTrue(
            sample().red > afterProgrammaticRefocus.red + .05f,
            "preview key input allows a later external focus light",
        )
    }

    @Test
    fun immediateLightTracksTouchInsideScrollableClickableUntilRelease() = runRenderTest {
        mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                scrollState = rememberScrollState()
                Box(Modifier.size(240.dp).background(Color.Black).testTag("scroll-root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .9f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                    Column(Modifier.offset(20.dp, 20.dp).size(120.dp).verticalScroll(scrollState)) {
                        repeat(8) { index ->
                            val rowSource = remember { MutableInteractionSource() }
                            Box(
                                Modifier.size(120.dp, 80.dp)
                                    .interactionLightSource(rowSource) {
                                        InteractionLight.Config(color = Color.Red, radius = 34.dp)
                                    }
                                    .clickable(
                                        interactionSource = rowSource,
                                        indication = null,
                                    ) {}
                                    .testTag(if (index == 0) "scroll-row" else "scroll-row-$index"),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        val before = onNodeWithTag("scroll-root").captureToImage().toPixelMap()
        onNodeWithTag("scroll-root").performTouchInput { down(position = Offset(80f, 80f)) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val downPixel = onNodeWithTag("scroll-root").captureToImage().toPixelMap()[80, 80]
        assertTrue(
            downPixel.red > before[80, 80].red + .3f,
            "raw touch lights within one frame while clickable Press is deferred: $downPixel",
        )

        onNodeWithTag("scroll-root").performTouchInput { moveTo(position = Offset(80f, 30f)) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        assertTrue(scrollState.value > 0, "the move wins the vertical scroll gesture")
        val moved = onNodeWithTag("scroll-root").captureToImage().toPixelMap()
        assertTrue(
            moved[80, 30].red > before[80, 30].red + .3f && moved[80, 80].red < .05f,
            "the held light follows the finger through scroll: current=${moved[80, 30]}, old=${moved[80, 80]}",
        )

        onNodeWithTag("scroll-root").performTouchInput { up() }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        val released = onNodeWithTag("scroll-root").captureToImage().toPixelMap()
        assertTrue(
            released[80, 30].red < .05f,
            "releasing the finger fades the light after scrolling: ${released[80, 30]}",
        )
    }

    @Test
    fun rawTouchAndMatchingPressCancelKeepOneLightUntilPointerUp() = runRenderTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(Offset(40f, 30f))
        setContent {
            ProvideInteractionLighting {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(200.dp, 140.dp).background(Color.Black).testTag("touch-root")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = .9f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {}
                                },
                            ),
                    )
                    Box(
                        Modifier.offset(20.dp, 30.dp)
                            .size(100.dp, 70.dp)
                            .interactionLightSource(source) {
                                InteractionLight.Config(color = Color.Red, radius = 24.dp)
                            }
                            .testTag("touch-source"),
                    )
                }
            }
        }
        waitForIdle()
        val before = onNodeWithTag("touch-root").captureToImage().toPixelMap()
        onNodeWithTag("touch-root").performTouchInput { down(position = Offset(60f, 60f)) }
        mainClock.advanceTimeBy(16)
        waitForIdle()
        val rawDown = onNodeWithTag("touch-root").captureToImage().toPixelMap()[60, 60]
        assertTrue(
            rawDown.red > before[60, 60].red + .3f,
            "raw down lights immediately at the finger: $rawDown",
        )

        runOnIdle { source.tryEmit(press) }
        waitForIdle()
        val matchedPress = onNodeWithTag("touch-root").captureToImage().toPixelMap()[60, 60]
        assertTrue(
            abs(matchedPress.red - rawDown.red) < .05f,
            "the matching Press does not double the raw touch light: $rawDown vs $matchedPress",
        )

        runOnIdle { source.tryEmit(PressInteraction.Cancel(press)) }
        waitForIdle()
        val canceled = onNodeWithTag("touch-root").captureToImage().toPixelMap()[60, 60]
        assertTrue(
            abs(canceled.red - rawDown.red) < .05f,
            "Cancel does not end a still-held raw pointer: $rawDown vs $canceled",
        )

        onNodeWithTag("touch-root").performTouchInput { up() }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        val released = onNodeWithTag("touch-root").captureToImage().toPixelMap()[60, 60]
        assertTrue(released.red < .05f, "pointer up ends the touch light: $released")
    }

    @Test
    fun additiveInteriorLightBrightensAlreadyBrightBackdropAndRestoresAfterColorChanges() =
        runRenderTest {
            val source = MutableInteractionSource()
            val press = PressInteraction.Press(Offset(20f, 20f))
            val linearWhite4 = Color(4f, 4f, 4f, colorSpace = ColorSpaces.LinearExtendedSrgb)
            val hdrWhite4 = Color(4f, 4f, 4f, colorSpace = ColorSpaces.ExtendedSrgb)
            val lightColor = mutableStateOf(linearWhite4)
            setContent {
                ProvideInteractionLighting {
                    val backdrop = rememberLayerBackdrop()
                    Box(
                        Modifier.size(300.dp, 240.dp)
                            .background(Color.Black)
                            .testTag("bright-root"),
                    ) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                            Box(Modifier.fillMaxSize().background(Color(.9f, .9f, .9f)))
                        }
                        Box(
                            Modifier.offset(50.dp, 100.dp).size(40.dp).interactionLightSource(
                                source,
                            ) {
                                InteractionLight.Config(color = lightColor.value, radius = 160.dp)
                            },
                        )
                        Box(
                            Modifier.offset(90.dp, 80.dp)
                                .size(140.dp, 120.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RectangleShape },
                                    render = {
                                        val material = image {
                                            source()
                                            clipSurface()
                                        }
                                        group {
                                            draw(material)

                                            interactionLight(
                                                InteractionLighting.Config(
                                                    interiorOpacity = .15f,
                                                ),
                                                blendMode = BlendMode.Plus,
                                            )
                                        }
                                        foreground {}
                                    },
                                ),
                        )
                    }
                }
            }
            waitForIdle()
            val before = onNodeWithTag("bright-root").captureToImage().toPixelMap()[110, 120]
            runOnIdle { source.tryEmit(press) }
            waitForIdle()

            runOnIdle { lightColor.value = Color.White }
            waitForIdle()
            val sdrWhite = onNodeWithTag("bright-root").captureToImage().toPixelMap()[110, 120]
            assertTrue(
                sdrWhite.red > before.red + .025f,
                "white interaction light adds visible energy over the .9 backdrop: $before -> $sdrWhite",
            )

            runOnIdle { lightColor.value = hdrWhite4 }
            waitForIdle()
            runOnIdle { lightColor.value = Color.White }
            waitForIdle()
            val restoredWhite = onNodeWithTag("bright-root").captureToImage().toPixelMap()[110, 120]
            assertTrue(
                restoredWhite.red > before.red + .025f &&
                    abs(restoredWhite.red - sdrWhite.red) < .02f,
                "the held source restores its SDR additive response after the HDR input state: " +
                    "$before -> $sdrWhite -> $restoredWhite",
            )
        }

    @Test
    fun interactionContourIgnoresBackdropTintAndSurvivesHdrSdrSourceChanges() = runRenderTest {
        val leftSource = MutableInteractionSource()
        val rightSource = MutableInteractionSource()
        val leftPress = PressInteraction.Press(Offset(10f, 10f))
        val rightPress = PressInteraction.Press(Offset(10f, 10f))
        val sourceAttached = mutableStateOf(false)
        val sourceLight = mutableStateOf<InteractionLight>(InteractionLight.None)
        val hdrWhite4 = Color(4f, 4f, 4f, colorSpace = ColorSpaces.ExtendedSrgb)
        setContent {
            ProvideInteractionLighting {
                val blackBackdrop = rememberLayerBackdrop()
                val brightBackdrop = rememberLayerBackdrop()
                Box(Modifier.size(340.dp, 200.dp).background(Color.Black).testTag("contour-root")) {
                    Box(Modifier.offset(60.dp, 60.dp).size(80.dp).layerBackdrop(blackBackdrop)) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    Box(Modifier.offset(220.dp, 60.dp).size(80.dp).layerBackdrop(brightBackdrop)) {
                        Box(Modifier.fillMaxSize().background(Color(.9f, .9f, .9f)))
                    }
                    Box(
                        Modifier.offset(60.dp, 60.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = blackBackdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = 0f,
                                                contourOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {
                                        highlight(
                                            Highlight(
                                                width = 10.dp,
                                                style = HighlightStyle.Solid(Color.Transparent),
                                                colorResponse = HighlightColorResponse(amount = 0f),
                                                direction = HighlightDirection.Uniform,
                                            ),
                                            colorSource = material,
                                            interactionLighting =
                                                InteractionLighting.Config(
                                                    interiorOpacity = 0f,
                                                    contourOpacity = .8f,
                                                ),
                                        )
                                    }
                                },
                            ),
                    )
                    Box(
                        Modifier.offset(220.dp, 60.dp)
                            .size(80.dp)
                            .drawBackdrop(
                                backdrop = brightBackdrop,
                                shape = { RectangleShape },
                                render = {
                                    val material = image {
                                        source()
                                        clipSurface()
                                    }
                                    group {
                                        draw(material)

                                        interactionLight(
                                            InteractionLighting.Config(
                                                interiorOpacity = 0f,
                                                contourOpacity = .8f,
                                            ),
                                            blendMode = BlendMode.Plus,
                                        )
                                    }
                                    foreground {
                                        highlight(
                                            Highlight(
                                                width = 10.dp,
                                                style = HighlightStyle.Solid(Color.Transparent),
                                                colorResponse = HighlightColorResponse(amount = 0f),
                                                direction = HighlightDirection.Uniform,
                                            ),
                                            colorSource = material,
                                            interactionLighting =
                                                InteractionLighting.Config(
                                                    interiorOpacity = 0f,
                                                    contourOpacity = .8f,
                                                ),
                                        )
                                    }
                                },
                            ),
                    )
                    if (sourceAttached.value) {
                        Box(
                            Modifier.offset(40.dp, 90.dp).size(20.dp).interactionLightSource(
                                leftSource,
                            ) {
                                sourceLight.value
                            },
                        )
                        Box(
                            Modifier.offset(200.dp, 90.dp).size(20.dp).interactionLightSource(
                                rightSource,
                            ) {
                                sourceLight.value
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
        val sample = {
            val pixels = onNodeWithTag("contour-root").captureToImage().toPixelMap()
            pixels[61, 100] to pixels[221, 100]
        }
        val noSource = sample()
        runOnIdle { sourceAttached.value = true }
        waitForIdle()
        val noneLight = sample()
        assertTrue(
            abs(noSource.first.red - noneLight.first.red) < .01f &&
                abs(noSource.second.red - noneLight.second.red) < .01f,
            "no source and InteractionLight.None render the same contour: $noSource vs $noneLight",
        )

        runOnIdle {
            sourceLight.value =
                InteractionLight.Config(color = Color.White.copy(alpha = 0f), radius = 160.dp)
        }
        waitForIdle()
        val zeroSource = sample()
        assertTrue(
            abs(noSource.first.red - zeroSource.first.red) < .01f &&
                abs(noSource.second.red - zeroSource.second.red) < .01f,
            "a zero-alpha Config emits no interaction contour: $noSource vs $zeroSource",
        )

        runOnIdle {
            sourceLight.value = InteractionLight.Config(color = hdrWhite4, radius = 160.dp)
            leftSource.tryEmit(leftPress)
            rightSource.tryEmit(rightPress)
        }
        waitForIdle()
        val hdrInitial = sample()
        runOnIdle {
            sourceLight.value = InteractionLight.Config(color = Color.White, radius = 160.dp)
        }
        waitForIdle()
        val sdr = sample()
        assertTrue(
            sdr.first.red > noSource.first.red + .1f && sdr.second.red > noSource.second.red + .02f,
            "interaction contour responds over black and bright backdrops with color response disabled: " +
                "$noSource -> $sdr",
        )

        runOnIdle {
            sourceLight.value = InteractionLight.Config(color = hdrWhite4, radius = 160.dp)
        }
        waitForIdle()
        val hdrAgain = sample()
        runOnIdle {
            sourceLight.value = InteractionLight.Config(color = Color.White, radius = 160.dp)
        }
        waitForIdle()
        val sdrRestored = sample()
        assertTrue(
            sdrRestored.first.red > noSource.first.red + .1f &&
                sdrRestored.second.red > noSource.second.red + .02f &&
                abs(sdrRestored.first.red - sdr.first.red) < .02f,
            "the held source restores its SDR contour after the HDR input transition: " +
                "HDR=$hdrInitial, SDR=$sdr, HDR-again=$hdrAgain, restored=$sdrRestored",
        )
    }
}
