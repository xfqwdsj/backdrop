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
    highlight = { Highlight(style = HighlightStyle.Ambient()) },
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

`Highlight(...)`, `Shadow(...)`, and `InnerShadow(...)` create their respective `Config` data classes.
The `Config` constructors and preset values support `copy`; `InnerShadow.lerp` interpolates two
`InnerShadow.Config` values.

Highlight styles share `color` and `blendMode`; `Default` and `Ambient` also expose `angle` in degrees
and non-negative `falloff`. Color alpha controls highlight strength, and `Highlight.Config.alpha`
controls the drawing layer's opacity. Extended-range RGB preserves HDR on supported drawing paths.
`Ambient` draws a single-sided sheen and defaults to white with alpha 0.38, SrcOver, 45 degrees, and
falloff 1. `Default` draws a two-sided highlight, and `Plain` draws a uniform outline.

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
