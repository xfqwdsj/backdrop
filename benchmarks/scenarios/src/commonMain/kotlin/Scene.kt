package top.ltfan.backdrop.benchmark.scenarios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import top.ltfan.backdrop.Backdrop
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.interaction.ProvideInteractionLighting

@Composable
public fun ScenarioScene(
    controller: ScenarioController,
) {
    val transition = updateTransition(controller.expanded, label = "scenario")
    val phase =
        transition.animateFloat(
            label = "motion",
            transitionSpec = {
                if (controller.scenario == ScenarioId.Stretch)
                    spring(dampingRatio = .55f, stiffness = 260f)
                else tween(ScenarioTiming.DURATION_MILLIS)
            },
        ) {
            if (it) 1f else 0f
        }
    SideEffect { controller.bind(phase) { transition.isRunning } }
    val p = { phase.value }
    val source = rememberLayerBackdrop()
    ProvideInteractionLighting {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF192C68)),
        ) {
            if (controller.scenario != ScenarioId.Adaptive) {
                if (controller.scenario == ScenarioId.Scroll) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(controller.expanded) {
                        scroll.animateScrollTo(
                            if (controller.expanded) 500 else 0,
                            tween(ScenarioTiming.DURATION_MILLIS),
                        )
                    }
                    Column(Modifier.fillMaxSize().layerBackdrop(source).verticalScroll(scroll)) {
                        repeat(32) { row ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(56.dp)
                                    .background(
                                        if (row % 2 == 0) Color(0xFF3755A1) else Color(0xFFE5AA45),
                                    ),
                            ) {
                                BasicText("Feed item $row", Modifier.padding(16.dp))
                            }
                        }
                    }
                } else Background(Modifier.fillMaxSize().layerBackdrop(source))
            }
            when (controller.scenario) {
                ScenarioId.SharedBounds ->
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                        AnimatedContent(
                            controller.expanded,
                            modifier = Modifier.fillMaxSize(),
                            label = "shared",
                        ) { expanded ->
                            Box(Modifier.fillMaxSize()) {
                                val placement =
                                    if (expanded) Modifier.align(Alignment.Center)
                                    else Modifier.align(Alignment.TopStart).offset(24.dp, 96.dp)
                                Panel(
                                    "shared",
                                    source,
                                    p,
                                    placement
                                        .sharedBounds(
                                            rememberSharedContentState("surface"),
                                            this@AnimatedContent,
                                            boundsTransform = { _, _ ->
                                                tween(ScenarioTiming.DURATION_MILLIS)
                                            },
                                            resizeMode =
                                                SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                        )
                                        .size(
                                            if (expanded) 320.dp else 176.dp,
                                            if (expanded) 320.dp else 80.dp,
                                        ),
                                    foregroundBlur = true,
                                    animateOptics = true,
                                    generic = true,
                                )
                            }
                        }
                    }
                ScenarioId.Sheet ->
                    Panel(
                        "sheet",
                        source,
                        p,
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 40.dp)
                            .layout { measurable, _ ->
                                val width = 320.dp.roundToPx()
                                val height = (100.dp.toPx() + 240.dp.toPx() * p()).roundToInt()
                                val child = measurable.measure(Constraints.fixed(width, height))
                                layout(width, height) { child.place(0, 0) }
                            }
                            .graphicsLayer {
                                translationY = (1f - p()) * 48.dp.toPx()
                                alpha = .25f + .75f * p()
                                // Covers the default shadow support and vertical offset.
                                outsets = LayerOutsets(80.dp, 80.dp, 80.dp, 80.dp)
                            },
                        foregroundBlur = true,
                        animateOptics = true,
                    )
                ScenarioId.Carousel ->
                    repeat(3) { index ->
                        Panel(
                            "card$index",
                            source,
                            p,
                            Modifier.align(Alignment.Center).size(200.dp, 260.dp).graphicsLayer {
                                val delta = index - 1 - p()
                                translationX = delta * 170.dp.toPx()
                                scaleX = 1f - .12f * abs(delta)
                                scaleY = scaleX
                                rotationZ = delta * 10f
                            },
                        )
                    }
                ScenarioId.Perspective -> {
                    val cardSource = rememberLayerBackdrop()
                    // Source and receiver share a plane; Compose projects the completed card.
                    Box(
                        Modifier.align(Alignment.Center).size(320.dp, 300.dp).graphicsLayer {
                            rotationY = -55f + 110f * p()
                            rotationX = 18f * sin(PI.toFloat() * p())
                            cameraDistance = (1000f - 300f * p()).dp.toPx()
                        },
                    ) {
                        Background(Modifier.fillMaxSize().layerBackdrop(cardSource))
                        Panel(
                            "flip",
                            cardSource,
                            p,
                            Modifier.align(Alignment.Center).size(280.dp, 260.dp),
                            generic = true,
                        )
                    }
                }
                ScenarioId.Stretch ->
                    Panel(
                        "elastic",
                        source,
                        p,
                        Modifier.align(Alignment.Center).size(280.dp, 220.dp).graphicsLayer {
                            val pulse = sin(PI.toFloat() * p())
                            scaleX = 1f + .18f * pulse
                            scaleY = 1f - .12f * pulse
                            translationY = -24.dp.toPx() * pulse
                            transformOrigin = TransformOrigin(.5f, 0f)
                        },
                    )
                ScenarioId.Compositing -> {
                    // Padding keeps the animated offscreen group's shadow inside its storage.
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = 80.dp).size(380.dp, 280.dp),
                    ) {
                        Panel(
                            "backgroundAlpha",
                            source,
                            p,
                            Modifier.align(Alignment.Center).size(220.dp, 120.dp),
                            backgroundAlpha = true,
                        )
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .offset(y = (-80).dp)
                            .size(380.dp, 280.dp)
                            .graphicsLayer {
                                alpha = p().coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                val radius = (.35f + .65f * p()) * size.maxDimension
                                drawCircle(Color.White, radius, center, blendMode = BlendMode.DstIn)
                            },
                    ) {
                        Panel(
                            "groupAlpha",
                            source,
                            p,
                            Modifier.align(Alignment.Center).size(220.dp, 120.dp),
                        )
                    }
                }
                ScenarioId.Scroll ->
                    Panel(
                        "sticky",
                        source,
                        p,
                        Modifier.align(Alignment.TopCenter).offset(y = 48.dp).size(360.dp, 96.dp),
                    )
                ScenarioId.Adaptive ->
                    Box(
                        Modifier.align(Alignment.Center).layout { measurable, _ ->
                            val width = (384.dp.toPx() - 84.dp.toPx() * p()).roundToInt()
                            val height = (640.dp.toPx() - 180.dp.toPx() * p()).roundToInt()
                            val child = measurable.measure(Constraints.fixed(width, height))
                            layout(width, height) { child.place(0, 0) }
                        },
                    ) {
                        Background(Modifier.fillMaxSize().layerBackdrop(source))
                        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
                            val compact = maxWidth < 320.dp
                            if (compact)
                                Column {
                                    repeat(2) { i ->
                                        Panel(
                                            "pane$i",
                                            source,
                                            p,
                                            Modifier.padding(8.dp).size(240.dp, 160.dp),
                                        )
                                    }
                                }
                            else
                                Row {
                                    repeat(2) { i ->
                                        Panel(
                                            "pane$i",
                                            source,
                                            p,
                                            Modifier.padding(8.dp).size(144.dp, 240.dp),
                                        )
                                    }
                                }
                        }
                    }
            }
            Box(
                Modifier.align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(48.dp)
                    .background(Color.DarkGray)
                    .semantics {
                        contentDescription =
                            "Toggle surface: ${if (transition.isRunning) "animating" else "idle"}"
                        stateDescription = if (transition.isRunning) "Animating" else "Idle"
                    }
                    .toggleable(controller.expanded) { controller.expanded = it },
            )
        }
    }
}

@Composable
private fun Background(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color(0xFF3755A1))
        val pitch = 24.dp.toPx()
        repeat((size.height / pitch).toInt() + 1) { i ->
            drawRect(
                if (i % 2 == 0) Color(0xFFE5AA45) else Color(0xFF3755A1),
                Offset(0f, i * pitch),
                Size(size.width, pitch / 2f),
            )
        }
    }
}

@Composable
private fun Panel(
    id: String,
    source: Backdrop,
    p: () -> Float,
    modifier: Modifier,
    foregroundBlur: Boolean = false,
    animateOptics: Boolean = false,
    generic: Boolean = false,
    backgroundAlpha: Boolean = false,
) {
    val shape = if (generic) GenericContour else RoundedCornerShape(24.dp)
    val observed =
        modifier
            .testTag(id)
            .benchmarkSurface(
                source,
                { shape },
                { if (animateOptics) p() else 0f },
                backgroundAlpha = { if (backgroundAlpha) p().coerceIn(0f, 1f) else 1f },
            )
    Box(observed) {
        Canvas(
            Modifier.align(Alignment.Center).size(80.dp, 32.dp).graphicsLayer {
                if (foregroundBlur) {
                    val radius = (1f - p().coerceIn(0f, 1f)) * 16.dp.toPx()
                    renderEffect = if (radius > 0f) BlurEffect(radius, radius) else null
                }
            },
        ) {
            drawRect(Color.White)
        }
        BasicText(
            id,
            Modifier.padding(12.dp),
            style = TextStyle(color = Color.White, fontSize = 16.sp),
        )
    }
}

/** A generic curved outline exercises path-based contour geometry. */
private object GenericContour : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { 24.dp.toPx() }.coerceAtMost(minOf(w, h) / 2f)
        return Outline.Generic(
            Path().apply {
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                cubicTo(w, 0f, w, 0f, w, r)
                lineTo(w, h - r)
                cubicTo(w, h, w, h, w - r, h)
                lineTo(r, h)
                cubicTo(0f, h, 0f, h, 0f, h - r)
                lineTo(0f, r)
                cubicTo(0f, 0f, 0f, 0f, r, 0f)
                close()
            },
        )
    }
}
