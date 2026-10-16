# Development

The root build includes one module, `:backdrop`. The
[module build](../backdrop/build.gradle.kts) owns target configuration, dependencies, formatting,
Dokka, group, and publication metadata. [gradle.properties](../gradle.properties) owns the project
version; the [version catalog](../gradle/libs.versions.toml) owns tool versions.

## Build and verify

Use the repository wrapper. Configure the Android SDK in the ignored `local.properties` file.
The JDK and runner used by CI are specified in [the test workflow](../.github/workflows/test.yml).

For a focused JVM iteration:

```sh
./gradlew :backdrop:jvmTest
```

Before completing a change, run formatting verification and an uncached assembly/test pass:

```sh
./gradlew :backdrop:spotlessCheck
./gradlew :backdrop:assemble :backdrop:jvmTest --no-build-cache --rerun-tasks
```

Also run IDE inspections at warning severity on touched files and report checks that could not run.
The uncached pass forces execution rather than relying on cached build results. Formatting uses
ktfmt's Kotlin language style; `:backdrop:spotlessApply` applies it to the configured targets, so
review its diff for unrelated edits. Its scope is defined by the module's `spotless` block.

For CI's multiplatform test path, prepare both web toolchains before testing:

```sh
./gradlew kotlinNodeJsSetup kotlinWasmNodeJsSetup kotlinNpmInstall kotlinWasmNpmInstall
./gradlew allTests
```

Executable tests currently live in `jvmTest`. Other target test tasks can be skipped because they
have no tests; a skipped task does not demonstrate that target's rendering behavior. Likewise,
assembly validates compilation and packaging rather than every platform's visual output.

## Choose regression coverage

Read the relevant tests before implementing a change, then choose a reproducer that exercises the
failure at its actual boundary. Test sources live in [jvmTest](../backdrop/src/jvmTest/kotlin).

Choose tests by the contract being changed rather than by a fixed file list. Pure math tests can
check input bounds and color calculations, while rendering tests need to exercise recorded output
and state updates across frames. When changing a shared model, compare its CPU and shader paths.
For input or lifecycle defects, reproduce the transition that triggers stale state or ownership loss.
Keep the regression at the boundary where the defect becomes observable.

For stability and consumer skipping, create a separate consumer module in a temporary copy. Add a
temporary `composeCompiler` block with `reportsDestination` and `metricsDestination`, then inspect
its classes/composables reports. In-module inference cannot prove a consumer sees a public stability
contract. Public annotations remain part of that contract even when implementation inference agrees.
Drawing scopes, runtime shaders, and shader caches have unsignalled mutable state and remain unannotated.

## Build constraints

Keep AGP upgrades separate from feature work. JS and Wasm need `binaries.executable()` for the Compose
plugin's UI-test configuration check. Settings intentionally leaves `repositoriesMode` unset because
the Kotlin web toolchain adds its Node distribution as a project repository;
`FAIL_ON_PROJECT_REPOS` would reject that setup.

After changing web dependencies, run `./gradlew kotlinUpgradeYarnLock` and review/commit
[kotlin-js-store/yarn.lock](../kotlin-js-store/yarn.lock). A stale lock fails `:kotlinStoreYarnLock`.
This lockfile task belongs to the configured Kotlin toolchain; it is not a reason to install or
replace global package managers.

## Import aliases

Prefer simple imports. When declarations collide, use the established names below; introduce new
source-prefixed aliases consistently and update this table in the same change.

| Declaration | Alias when needed |
| --- | --- |
| `androidx.compose.ui.graphics.Paint` | `ComposePaint` |
| `org.jetbrains.skia.Paint` | `SkiaPaint` |
| `org.jetbrains.skia.BlendMode` | `SkiaBlendMode` |
| `org.jetbrains.skia.ColorFilter` | `SkiaColorFilter` |
| `org.jetbrains.skia.Rect` | `SkiaRect` |
| `android.graphics.RenderEffect` | `AndroidRenderEffect` |
| `android.graphics.RuntimeShader` | `AndroidGraphicsRuntimeShader` |

Use both Paint aliases when Compose and Skia paint are present together. For other collisions,
retain the other declaration's unaliased name when clear. Type-name factories deliberately match
Compose's `Paint()` and `RuntimeShader()` convention, including effect and decoration factories;
their naming inspections are intentional. Weak local-alias inlining warnings need no cleanup.

## Prepare publication

Inspect generated metadata before an owner-authorized release:

```sh
./gradlew :backdrop:generatePomFileForKotlinMultiplatformPublication
```

Read the POM under `backdrop/build/publications/`. Publication tasks are
`:backdrop:publishToMavenCentral` and
`:backdrop:publishAllPublicationsToGitHubPackagesRepository`. Both channels publish
`top.ltfan.backdrop:backdrop`; signing and credentials are configured by the build and
[publish workflow](../.github/workflows/publish.yml). Local signing uses the configured GPG command
unless in-memory signing is selected. Keep credentials outside tracked files.

The release workflow prepares web dependencies and runs tests before publication. Review changes
locally; repository policy reserves pushing and published-history changes to the owner.
