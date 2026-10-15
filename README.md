# Backdrop

![Maven Central Version](https://img.shields.io/maven-central/v/top.ltfan.backdrop/backdrop)

A Compose Multiplatform library for drawing backdrop effects behind UI content, with HDR support.
It records what is behind a component and redraws it through blur, lens refraction with dispersion,
color adjustments, highlights and shadows.

## Relationship to upstream

This project is a maintained fork of
[`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass), and it is not a drop-in replacement for
it: this library carries changes that are incompatible with, and that behave differently from, the usage the upstream
project documents.

## Getting Started

To use `backdrop` in your Kotlin Multiplatform project, add the following dependency to your
`build.gradle.kts` file:

```kotlin
dependencies {
    implementation("top.ltfan.backdrop:backdrop:<version>")
}
```

Or if you are using Gradle Version Catalogs, add the following to your `gradle/libs.versions.toml`:

```toml
[versions]
backdrop = "<version>"

[libraries]
backdrop = { module = "top.ltfan.backdrop:backdrop", version.ref = "backdrop" }
```

Make sure your `settings.gradle.kts` includes the repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

## Visual defaults

`drawBackdrop` inherits its effects, highlight, shadow, and inner shadow from
`LocalBackdropStyle` at the modifier's position in the UI tree. The root style uses an empty effect
chain, `Highlight.Default`, `Shadow.Default`, and `InnerShadow.None`. Shape and drawing callbacks
are supplied at the call site. `drawPlainBackdrop` inherits only effects.

Both surface modifiers composite offscreen. Their compositing bounds include the resolved effect
extension and outer shadow, while their layout size and content shape stay unchanged. Explicit
`layerBlock` transforms and larger outsets are retained; an explicit clip still clips the layer.
Skiko requests the complete extended surface input for shaders that sample neighboring pixels.

`BackdropRamp` anchors place the curve's ends inside or outside the surface. Effect insets extend
or contract the drawing region; blur and mask keep those anchors aligned in that region. Sampled
surfaces and direct-background masks share the extended shape: rounded corners retain their radius,
and custom shapes resolve their outline against the extended size.

`BackdropMask` takes either a brush or a color constructor. A color becomes a `SolidColor` fill;
mask strength multiplies the fill's own opacity. `drawBackdropMask` has separate color and brush
overloads, and both retain the fill in flat and progressive modes.

```kotlin
ProvideBackdropStyle(
    effects = { blur(8.dp.toPx()) },
    highlight = { Highlight.Ambient },
) {
    Box(Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(24.dp) }))
}
```

Omitted provider fields inherit their parent's producers. To provide a complete style, use
`ProvideBackdropStyle(style = myStyle)` or `CompositionLocalProvider(LocalBackdropStyle provides myStyle)`.
Use `LocalBackdropStyle.current.copy(...)` to retain selected fields when constructing a style.

Modifier parameters set to `null` inherit their corresponding producer. Explicit producers replace
that default. Disable a decoration with `{ Highlight.None }`, `{ Shadow.None }`, or
`{ InnerShadow.None }`; select an empty effect chain with `BackdropStyle.NoEffects`.

```kotlin
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(24.dp) },
    effects = BackdropStyle.NoEffects,
    shadow = { Shadow.None },
    innerShadow = { if (pressed) InnerShadow.Default else InnerShadow.None },
)
```

Producers run during drawing or effect observation. Read animated snapshot state inside a producer
to update rendering without recomposing the component. A producer can switch between `None` and a
configuration; `None` releases the decoration's drawing layer. Configurations remain configurations
at zero alpha.

### Shared interaction lighting

`ProvideInteractionLighting` creates a registry for interaction lights. A source opts in with
`Modifier.interactionLightSource`; a `drawBackdrop` receiver opts in with its
`interactionLighting` producer or the matching `BackdropStyle` field. The default receiver setting
is `InteractionLighting.None`, and `drawPlainBackdrop` remains effects-only.

```kotlin
ProvideInteractionLighting {
    Button(
        onClick = onClick,
        modifier = Modifier.interactionLightSource(light = {
            InteractionLight.Config(color = Color(0xFFFFB45E), radius = 180.dp)
        }),
    ) { Text("Light source") }

    Box(
        Modifier.drawBackdrop(
            backdrop,
            shape = { RoundedCornerShape(24.dp) },
            interactionLighting = { InteractionLighting.Config() },
        ),
    )
}
```

The default provider isolates its registry; a nested `ProvideInteractionLighting(isolate = false)`
reuses the nearest provider registry. Sources and receivers also match Compose's `LocalWindowInfo`
identity. This shares lights within that provider scope and Compose window; independent roots or
native windows are not discovered process-wide. Physical pointer input immediately creates a light
and owns its movement and release fade, whether or not an `InteractionSource` is supplied. The
modifier observes pointer changes without consuming them. A supplied `InteractionSource` also
provides synthetic press and focus interactions; a press uniquely matching an active pointer shares
that pointer's light, while unrelated presses keep their own lifetime. With no source, the modifier
observes focus for keyboard input but does not light from programmatic focus alone; Enter, Space, and
Center key input create a center light. A supplied `InteractionSource` provides focus lights, which
pointer input ends and subsequent key input permits again. An unspecified synthetic press position
uses the source center. Radius is measured in window pixels using the source density, so
receiver transforms do not scale the light. Release intensity fades according to
`InteractionLight.Config.releaseDuration`.

Runtime shader rendering evaluates the shared field in linear extended-sRGB. Interior lighting uses
native Plus blending without an extra layer. Contour lighting joins the highlight output and uses
the highlight's blend mode. On platforms without runtime shaders, interior lighting uses a cached,
interpolated SDR vertex mesh; its color range and sampling are therefore approximate, especially
for very small features and extended-range colors.

### Source boundaries

Effect sampling bounds describe required pixels; the source defines how those pixels are supplied.
`rememberLayerBackdrop(tileMode = TileMode.Clamp)` extends its recorded boundary texels before
effects run, preserving their alpha and extended-range RGB. Use `TileMode.Decal` for transparent
exterior pixels, or `Repeated` and `Mirror` for tiled sources. Each source in a combined backdrop
applies its own boundary policy. Exported surfaces expose their drawn region, excluding unused
effect sampling padding.

Canvas and custom backdrops draw through `BackdropDrawScope`. A procedural source can fill the
complete request:

```kotlin
val backdrop = rememberCanvasBackdrop {
    drawRect(color, topLeft = samplingBounds.topLeft, size = samplingBounds.size)
}
```

For a finite source, call `drawSource(bounds, tileMode)` with a non-empty, finite, pixel-aligned
rectangle. Its callback uses the declared source coordinates and source size and is clipped to
that rectangle. Apply coordinate transforms through the scope's `withTransform(matrix) { ... }`
or the `drawSource` transform parameter; both map the request with an invertible two-dimensional
affine matrix. Custom sources must cover their requested coordinates or explicitly declare a
finite domain. Boundary extension uses runtime shaders; unsupported platforms draw finite sources
with transparent exterior pixels. Interior transparency remains part of the source on every path.

### Custom effects and sampling

Custom `RenderEffect`s and runtime shaders declare their sampling behavior with
`BackdropSampling`. The declaration returns the complete input rectangle needed for an output
rectangle, in pixels relative to the surface's top-left. Include every sampled texel, including
neighbors used by bilinear interpolation. `Identity` declares same-coordinate reads;
`outsets(left, top, right, bottom)` declares a fixed footprint; `translated(dx, dy)` describes a
translated lookup. The library checks that returned rectangles are finite and non-inverted, but the
effect author is responsible for declaring a rectangle that covers the shader or filter's actual
reads.

Every external effect must provide this contract:

```kotlin
effects = {
    effect(customEffect, sampling = BackdropSampling.outsets(8f, 8f, 8f, 8f))
    runtimeShaderEffect(
        key = "my-effect",
        shaderString = shaderSource,
        uniformShaderName = "content",
        sampling = BackdropSampling.translated(dx = 2f, dy = 0f),
    ) {
        setFloatUniform("amount", amount)
    }
}
```

The effect chain is analyzed from its last stage to its first. Sampling needs from serial stages
therefore accumulate; independent input regions are combined by their bounding union. The library
includes the visible output and all intermediate input rectangles when computing the recording
extension. The effects producer runs once per resolution. The library builds deferred effect stages
and invokes runtime shader uniform blocks after calculating the final extension, so setup can use the
resolved bounds.

`Highlight(...)`, `Shadow(...)`, and `InnerShadow(...)` create their respective `Config` values.
`Highlight.Config` is a stable configuration class with `copy` and component accessors; it snapshots
the supplied exclusions. `InnerShadow.lerp` interpolates two `InnerShadow.Config` values.

`Highlight` renders a configurable falloff profile around the nearest outline boundary. `Default`
uses a solid style with two-sided direction; `Ambient` uses a two-tone style, and `Plain` disables
directional modulation. Direction angle uses 0 right, 90 down, 180 left, and 270 up. The configured
`width` is the base width. `HighlightDirection.widthScale` maps side-facing incidence to aligned incidence (default 60%
to 100%); its curve and endpoints are independent from the color `strength` response. The `from`
endpoint is the side-facing ratio and `to` is the aligned ratio. Either may exceed 1 to scale beyond
the base width or strength. The profile is clipped to that direction-scaled width. `Highlight.Config.opacity` controls
the drawing layer. Each pixel evaluates the profile at its center; the shape clip supplies the
antialiased contour edge. Emission strength includes color alpha and direction response, so black
RGB can still carry emission. `colorResponse` tints covered samples from the already processed backdrop
while preserving the highlight peak and emission strength. Its default amount is 0.5; set it to zero
to disable backdrop sampling. The transform receives linear extended-sRGB colors and keeps
extended-range channels on supported drawing paths.

Applications can implement `HighlightStyle` for custom boundary shading. Provide matching
`shaderSource` (AGSL) and `shade(HighlightSample)` implementations. Both return straight-alpha color
and emission strength; each covered sample uses its nearest boundary position and normal, then
applies direction strength, direction-scaled width, the configured falloff, one exclusion weight,
and premultiplication. The shader provides `shadeHighlight` and `highlightStrength`. Use
`configureShader` to upload custom uniforms. The interface is `@Stable`, so mutable public
properties must be backed by Compose snapshot state to invalidate drawing.

`colorResponse` samples the processed backdrop at each covered highlight sample, after the effect
chain and backdrop callback have rendered. Surface callbacks, text, and front decorations are not
part of that source. `PreservePeak` maps the sample's positive RGB peak to the base highlight peak;
if either peak cannot produce a finite positive scale, the base color is retained. Sample alpha
controls the tint amount, while highlight alpha and direction alone control emission:

```kotlin
Highlight(
    style = HighlightStyle.Solid,
    colorResponse = HighlightColorResponse(amount = 0.5f),
)
```

The response only changes RGB. The pipeline composes in linear extended-sRGB arithmetic and stores
premultiplied RGB; transforms retain extended-range channels on supported paths. HDR window
configuration remains the app's responsibility. Rectangles and circular-corner rounded rectangles
use analytic bounds and radius uniforms for nearest-boundary queries. Other outlines use cached
flattened path geometry, and the original fill-rule-aware outline clip preserves concavities and
holes. Snapshot state read by the highlight producer updates drawing without recomposition.

## HDR

Backdrop keeps extended-range colors on the paths it owns: shader color uniforms, paint colors, and
the tint and lighting filters. Color matrices stay on the platform filter, so how they treat
extended-range values follows the platform and OS release. On Android 14 and later Backdrop can
also refresh its own rendering resources when the display HDR/SDR ratio changes, so the effects
follow the display's current headroom.

HDR output is opt-in and belongs to the app: Backdrop never enables HDR on a window by itself.

```kotlin
CompositionLocalProvider(LocalBackdropHdrRefreshInterval provides 200.milliseconds) {
  BackdropHdrScope {
    // rememberLayerBackdrop / drawBackdrop / drawPlainBackdrop
  }
}
```

Enable HDR on the Android window yourself, for example with
`window.colorMode = ActivityInfo.COLOR_MODE_HDR`. `BackdropHdrScope` then:

- publishes the sampled display ratio through `LocalBackdropHdrHeadroom`, which is 1 while the
  display reports no headroom,
- throttles refreshes to `LocalBackdropHdrRefreshInterval`, 200 milliseconds by default; the value
  must be finite and non-negative,
- refreshes only the rendering resources Backdrop owns, so the composition, animations and
  selection state stay intact, and
- leaves `GraphicsLayer`s you supply yourself, plus ancestor and third-party effect layers, under
  your control.

Platforms without the display callback keep their usual rendering behavior, with
`LocalBackdropHdrHeadroom` staying at 1. Effects that run through platform intermediates, such as
the system blur, follow the platform's own color handling.

## Components

The library does not include any high-level components; you will need to create your own.

## Credits

Super big thanks to [Kyant0](https://github.com/Kyant0).

## Contributing

We welcome contributions! Please submit issues or pull requests for any bugs, features, or
improvements.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
