# Interaction lighting

Shared interaction light lets input on one component illuminate nearby backdrop surfaces. Enable
three participants: a provider registry, an input source, and a receiving surface. Merely providing
a registry leaves receivers disabled.

## Connect sources and receivers

The following composable-body excerpt uses Foundation's click handling:

```kotlin
ProvideInteractionLighting {
    Box {
        Box(
            Modifier.size(120.dp).interactionLightSource(
                light = { InteractionLight.Config(color = Color(0xFFFFB45E), radius = 180.dp) }
            ).clickable(onClick = onClick)
        )
        Box(
            Modifier.size(240.dp).drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(24.dp) },
                render = {
                    val lighting = InteractionLighting.Config()
                    source()
                    clipSurface()
                    val material = current
                    interactionLight(lighting)
                    highlight(Highlight(), colorSource = material, interactionLighting = lighting)
                },
            )
        )
    }
}
```

Import the lighting APIs from `top.ltfan.backdrop.interaction`. Supply the backdrop and layout
appropriate to your component; the source can be independent of the receiver. Put
`interactionLightSource` before the focusable modifier to observe keyboard and focus events.
Receiver defaults can share the same declaration through `ProvideBackdropRenderStyle(render = { ... })`.

`InteractionLight.Config` controls color, radius, radial falloff, and release duration.
`InteractionLighting.Config` controls interior and contour opacity independently. Return
`InteractionLight.None` to disable a source, or `InteractionLighting.None` to disable a receiver.
Radius and release duration must be finite and non-negative; receiver opacities are in `0..1`.

## Set sharing boundaries

`ProvideInteractionLighting` owns an isolated registry by default. A nested provider with
`isolate = false` reuses the nearest registry; without a parent it creates one. Sources and
receivers must also share Compose's `LocalWindowInfo` identity. Separate roots or native windows
use their own registries and window identities.

Positions and radii live in window pixels. Source density converts the configured dp radius, and
receiver transforms map that field into local drawing coordinates without scaling its physical
radius. Source movement updates drawing coordinates while retaining the receiving composition.

## Understand input ownership

Physical pointer down creates light immediately, including inside scrolling click targets. The
source observes input without consuming it. Pointer movement and release own that light's lifetime;
release fades intensity according to the configured duration. Nested sources coordinate the same
physical pointer so it contributes once, while distinct pointers contribute independently.

An optional `InteractionSource` adds synthetic presses and focus events. Pass the same instance to
the component and `interactionLightSource` when those events should illuminate receivers. A press
that uniquely matches an active pointer shares its light; unrelated presses keep independent
lifetimes. An unspecified press position uses the source center.

Without an `InteractionSource`, raw focus enables Enter, Space, and Center key activation but does
not emit light from programmatic focus alone. With a supplied source, focus interactions emit
lights paired with their own unfocus events. Pointer input ends those focus lights and suppresses
pointer-driven focus emission until subsequent preview key input permits it again. Changing the
interaction source, disabling, or detaching clears the corresponding active entries.

## Choose the visual response

Interior lighting produces an image clipped to the original surface shape. Draw it with native
Plus blending at the desired point in the declaration. Contour lighting joins the highlight image
when passed as `interactionLighting` to `highlight`; its external blend belongs to the graph draw.
`interactionLight` omits its image and blend operation while the receiving window has no registered
sources. Source appearance and final removal update the declaration. Registered sources retain the
operation throughout release, including zero intensity. Moving a source updates drawing only.
Custom declarations can read `hasInteractionLights` to observe the same window-local membership;
nested image and foreground declarations share the receiver's provider and window.

The runtime field evaluates color in linear extended-sRGB. On platforms without runtime shaders,
the interior fallback interpolates a cached SDR vertex mesh. Small features and extended-range
colors are approximate on that path. Compiled shader-capacity variants are retained while the
renderer is active and released when its owner disables or detaches.

For implementation and regression coverage, follow
[InteractionLightSource](../backdrop/src/commonMain/kotlin/interaction/InteractionLightSource.kt),
[InteractionFieldRenderer](../backdrop/src/commonMain/kotlin/interaction/InteractionFieldRenderer.kt),
and [SharedInteractionLightingRenderingTest](../backdrop/src/renderTest/kotlin/SharedInteractionLightingRenderingTest.kt).
