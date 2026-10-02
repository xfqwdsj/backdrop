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
