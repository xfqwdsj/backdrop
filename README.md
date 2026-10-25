# Backdrop

![Maven Central Version](https://img.shields.io/maven-central/v/top.ltfan.backdrop/backdrop)

A Compose Multiplatform library for drawing backdrop effects behind UI content, with HDR support.
It records what is behind a component and redraws it through a chain of effects.

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

## Documentation

The [documentation guide](docs/README.md) covers integration and rendering contracts, with separate
paths for component authors and library maintainers.

## Project

Backdrop is an independently maintained fork of
[`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass), with its own versioning
and publishing. Use this project's documentation for its API and rendering contracts.

Thanks to [Kyant0](https://github.com/Kyant0) for the original work. Issues and pull requests are
welcome. Licensed under [Apache License 2.0](LICENSE).
