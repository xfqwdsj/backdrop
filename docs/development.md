# Development

The published library is `:backdrop`; `benchmarks/` contains non-published shared scenes, JVM benchmarks and Android performance
harnesses. The [module build](../backdrop/build.gradle.kts) owns target configuration, dependencies, formatting,
Dokka, group, and publication metadata. [gradle.properties](../gradle.properties) owns the project
version; the [version catalog](../gradle/libs.versions.toml) owns tool versions.

## Build and verify

Use the repository wrapper and configure the Android SDK through the Android build environment.
The JDK and runner used by CI are specified in [the test workflow](../.github/workflows/test.yml).

For ordinary local verification:

```sh
./gradlew :verify
```

This checks formatting, JVM library and scenario tests, generated task documentation,
and release automation. The dependency-free script tests use Kotlin’s managed Node toolchain.

For rendering, platform, API and test-infrastructure validation, select a connected Android device
and run:

```sh
ANDROID_SERIAL="<device-serial>" ./gradlew :verifyConnected
```

This adds host-supported library assembly, Kotlin target tests and the shared Android device tests.
CI runs `verifyManaged` in one Ubuntu job with the AGP-managed device declared by the module.
Both device entry points include `verifyTargets`, which combines `verify` with the Kotlin target tests
supported by the host. AGP selects all connected devices unless `ANDROID_SERIAL` identifies one explicitly.

Run IDE inspections at warning severity on touched files and report unavailable checks explicitly.
Formatting uses ktfmt's Kotlin language style; review formatting changes for unrelated edits.
See [performance verification](performance.md) for workloads and interpretation.

Shared rendering tests live in `renderTest`, which both `jvmTest` and `androidDeviceTest` consume.
The Compose Multiplatform UI-test entry point and one-pixel-per-dp test density are shared.
Platform initialization, alpha-preserving capture and F16 shader readback use expect/actual
helpers. Android captures the test host GraphicsLayer before opaque window blending; native
Skia behavior stays in `jvmTest`. Android-specific
renderer integration stays in `androidDeviceTest`. The device graphics adapter requires API 34;
use API 35 or newer for the complete shared shader suite.
Contour normal probes batch query uniforms alongside the complete geometry and retain every
sample across draws. Each draw checks opaque output before comparing shader numerics, so missing
shader output is distinguished from geometric mismatches.

## Choose regression coverage

Read the relevant tests before implementing a change, then choose a reproducer that exercises the
failure at its actual boundary. Shared test sources live in [renderTest](../backdrop/src/renderTest/kotlin), with backend-specific
cases in [jvmTest](../backdrop/src/jvmTest/kotlin) and
[androidDeviceTest](../backdrop/src/androidDeviceTest/kotlin).

Choose tests that exercise the affected contract. Pure math tests can
check input bounds and color calculations, while rendering tests need to exercise recorded output
and state updates across frames. When changing a shared model, compare its CPU and shader paths.
For input or lifecycle defects, reproduce the transition that triggers stale state or ownership loss.
Keep the regression at the boundary where the defect becomes observable.

For stability and consumer skipping, create a separate consumer module in a temporary copy. Add a
temporary `composeCompiler` block with `reportsDestination` and `metricsDestination`, then inspect
its classes/composables reports. In-module inference cannot prove a consumer sees a public stability
contract. Public annotations define that contract independently of implementation inference.
Drawing scopes, runtime shaders, and shader caches have unsignalled mutable state and must not carry
stability annotations.
Graph declaration scopes hold an immutable Density value and expose mutable declaration state.
Verify their unstable library contract and external consumer inference.

Regression tests assert pixels, calculations and lifecycle behavior directly. Keep executable
investigation probes separate from the shared tests. Preserve assertion strength and explain
any assertion changes in the review.

Deterministic work tests can bound retained gradient entries, graph branches, graphics-layer
creation and logical recording pixels. Repeated frames with unchanged geometry should reuse layers,
and branches sharing one source should record it once per graph recording. Frames that only change
presentation opacity retain graph recordings and their drawing-input observations. These bounds describe owned work;
they do not establish GPU time, native texture memory or that one algorithm is faster on a device.
Keep runtime-specific allocation and post-GC heap probes in temporary copies. Compare identical
inputs after warm-up and report the measured boundary. Heap deltas depend on runtime collection
behavior, so retain their environment and collection conditions with the results.

Render-list builders are generated by dsl-utilities through KSP common metadata. Compilation and
source archives depend on that generation task; public generated types belong to the published API.

## Build constraints

Keep AGP upgrades separate from feature work. JS and Wasm need `binaries.executable()` for the Compose
plugin's UI-test configuration check. Settings intentionally leaves `repositoriesMode` unset because
the Kotlin web toolchain adds its Node distribution as a project repository;
`FAIL_ON_PROJECT_REPOS` would reject that setup.

After changing web dependencies, run `./gradlew :kotlinUpgradeYarnLock` and review/commit
[kotlin-js-store/yarn.lock](../kotlin-js-store/yarn.lock). A stale lock fails `:kotlinStoreYarnLock`.
The configured Kotlin toolchain owns this lockfile task and its package-manager integration.

## Import aliases

Prefer simple imports. When declarations collide, use the established names below; introduce new
source-prefixed aliases consistently and update this table in the same change.

| Declaration | Alias when needed |
| --- | --- |
| `androidx.compose.ui.graphics.Paint` | `ComposePaint` |
| `androidx.compose.ui.graphics.layer.CompositingStrategy` | `LayerCompositingStrategy` when the graphics-scope type is also imported |
| `org.jetbrains.skia.Paint` | `SkiaPaint` |
| `org.jetbrains.skia.BlendMode` | `SkiaBlendMode` |
| `org.jetbrains.skia.ColorFilter` | `SkiaColorFilter` |
| `org.jetbrains.skia.Rect` | `SkiaRect` |
| `android.graphics.RenderEffect` | `AndroidRenderEffect` |
| `android.graphics.RuntimeShader` | `AndroidGraphicsRuntimeShader` |

Use both Paint aliases when Compose and Skia paint are present together. For other collisions,
retain the other declaration's unaliased name when clear.

Type-name factories copy the name of the type they construct. The IDE accepts an exact match
between the function name and the declared return type name without a naming suppression.
When those names differ, verify that the returned type is compatible with the named type before
locally suppressing `FunctionName`. For example, `Highlight(...)` returns `Highlight.Config`,
which implements `Highlight`. Functions without a corresponding constructed type use ordinary
lower-camel-case names, such as `createRuntimeShaderEffect(...)`.

## Prepare publication

[The release contract](releases.md) defines version checks, generated-notes drafts, publication,
and the managed performance section. Publication metadata belongs to the library build; the
version is read from `gradle.properties`.

Use `./gradlew :publishLocal` for local integration. Configure `mavenLocal()` before remote
repositories in an independent consumer, inspect generated metadata and verify public signature
dependencies. Remote publication uses `publishCentral` and `publishPackages` with
`-PreleaseTag=<tag>`. Both verify the release before uploading; signing and repository credentials
come from the build environment. The owner controls tags and release publication.

## CI maintenance

Workflows share the repository-owned build setup action, which uses GitHub-maintained `actions/*`
and Gradle-maintained `gradle/actions/*` actions.
Use first-party actions for their respective tools; keep emulator lifecycle in AGP-managed devices.
The test workflow uses `--continue` to collect independent failures and cancels superseded runs
for the same pull request or branch. Publication runs
remain independent so an in-progress upload can finish.

Workflow tokens default to `contents: read`. Artifact publication adds `packages: write`; draft
creation and release-note updates use `contents: write` in their respective jobs.
Checkout leaves credentials out of Git configuration because subsequent steps use Gradle to publish.
The wrapper is tracked executable, so CI invokes it directly. `setup-gradle` owns Gradle caching
and wrapper validation; avoid overlapping cache actions. Its default policy writes caches on the
default branch and reads them on other branches. The configured encryption secret enables
configuration-cache persistence when available; fork pull requests do not receive that secret.

Gradle and Kotlin compilation use separate daemon memory settings in `gradle.properties`.
`kotlin.daemon.jvmargs` configures the Kotlin daemon directly. Ordinary verification reuses build
outputs; use `--rerun-tasks` only when fresh execution is needed for a specific investigation.

These conventions follow the official [GitHub workflow
syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [Gradle
Actions configuration](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md), and
[Kotlin daemon
configuration](https://kotlinlang.org/docs/gradle-compilation-and-caches.html#the-kotlin-daemon-and-how-to-use-it-with-gradle).

## Build and release tasks

The table is generated from the root task declarations. Run `./gradlew :updateBuildDocs` after
changing those declarations; `verify` checks that the table matches. Gradle plugin tasks remain
available for focused subsystem work. Use the leading colon to select the root entry point
without also selecting a plugin task of the same name in a subproject.

<!-- build-contract:start -->
| Root task | Contract |
| --- | --- |
| `./gradlew :verify` | Checks formatting, JVM tests and release automation. |
| `./gradlew :verifyTargets` | Runs the verify task and Kotlin target tests supported by the host. |
| `./gradlew :verifyConnected` | Builds the library and runs Kotlin target tests plus selected Android device tests. |
| `./gradlew :verifyManaged` | Builds the library and runs Kotlin target tests plus AGP-managed Android device tests. |
| `./gradlew :verifyRelease` | Checks the release tag, clean checkout and library artifacts. |
| `./gradlew :publishLocal` | Runs JVM tests and publishes artifacts to Maven Local. |
| `./gradlew :publishCentral` | Verifies the release and publishes to Maven Central. |
| `./gradlew :publishPackages` | Verifies the release and publishes to GitHub Packages. |
| `./gradlew :benchmark` | Measures JVM time and managed allocation; benchmarkSmoke selects execution checks. |
| `./gradlew :benchmarkList` | Lists JMH-discovered workloads and parameter values. |
<!-- build-contract:end -->
