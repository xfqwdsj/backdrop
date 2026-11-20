# Backdrop

![Maven Central Version](https://img.shields.io/maven-central/v/top.ltfan.backdrop/backdrop)

A Compose Multiplatform library for drawing backdrop effects behind UI content, with HDR support.
It records what is behind a component and redraws it through a chain of effects.

## Migrate to M3X

The standalone `top.ltfan.backdrop:backdrop` artifact is deprecated. Its successor is Backdrop
in [M3X](https://github.com/xfqwdsj/m3-extended). Use an M3X release that includes the integrated
Backdrop implementation.

Replace the dependency in the Gradle configuration or source set that declares it:

```kotlin
dependencies {
    implementation("top.ltfan.m3x:backdrop:<m3x-version>")
}
```

If you use a version catalog, update the module and version there. Keep `mavenCentral()` in
`settings.gradle.kts` and update renderer imports to `top.ltfan.m3x.backdrop` and its subpackages.
See the [M3X documentation](https://github.com/xfqwdsj/m3-extended/blob/main/docs/README.md) for
integration and API usage.

## Project

Backdrop originated as a fork of [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass).
Thanks to [Kyant0](https://github.com/Kyant0) for the original work. Licensed under
[Apache License 2.0](LICENSE).
