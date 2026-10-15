# AGENTS.md

Guidance for agents working in this repository. Everything here was verified against the code and the compiler; keep it
current when behavior changes.

Keep this file to persistent design and rules: facts that go stale quickly — version numbers, annotation or file lists,
test counts — belong in the code, and this file points to the source instead of copying it.

## What this is

- Compose Multiplatform library `backdrop`: it records the content behind a component and redraws it through blur, lens
  refraction with dispersion, color adjustments, highlights and shadows, and it keeps extended-range colors on the paths
  it owns.
- Exactly one Gradle module, `:backdrop`. This repository ships the library only.
- A maintained fork of `Kyant0/AndroidLiquidGlass`: an independent project with its own `main`, its own versioning and
  its own publishing. It is not a drop-in replacement for upstream — this library carries changes that are incompatible
  with, and that behave differently from, the usage upstream documents. Treat upstream's documentation and source as
  reference, not as a specification for this library.
- Published as `top.ltfan.backdrop:backdrop` to Maven Central and GitHub Packages. The group, publication and POM
  metadata live in `backdrop/build.gradle.kts`; the version lives in `gradle.properties`; tool versions live in
  `gradle/libs.versions.toml`.

## Commands

- Fast loop: `./gradlew :backdrop:jvmTest`
- Trustworthy verification — the build cache can serve a stale result, so bypass it:
  `./gradlew :backdrop:assemble :backdrop:jvmTest --no-build-cache --rerun-tasks`
- Formatting: `./gradlew :backdrop:spotlessCheck`, fix with `./gradlew :backdrop:spotlessApply`. It is ktfmt
  (kotlinlang style); the exact targets are in the `spotless` block of `backdrop/build.gradle.kts`.
- CI parity: `./gradlew kotlinNodeJsSetup kotlinWasmNodeJsSetup kotlinNpmInstall kotlinWasmNpmInstall` followed by
  `./gradlew allTests`. Every test lives in `jvmTest`, so the JS, Wasm, iOS and macOS test tasks report `SKIPPED`; that is
  the expected green result, not a broken target. JDK and runner details are in `.github/workflows/`.
- JS/Wasm dependencies: after changing them, run `./gradlew kotlinUpgradeYarnLock` and commit
  `kotlin-js-store/yarn.lock`; a stale lock fails the build in `:kotlinStoreYarnLock`.
- Publishing: `./gradlew :backdrop:publishToMavenCentral` and
  `:backdrop:publishAllPublicationsToGitHubPackagesRepository`. Inspect the metadata before releasing with
  `:backdrop:generatePomFileForKotlinMultiplatformPublication` and read the POM under
  `backdrop/build/publications/`.
- The Android target needs an Android SDK path in `local.properties` locally; the file is git-ignored.
- Stability questions: ask the Compose compiler, do not reason from memory. A temporary
  `composeCompiler { reportsDestination = …; metricsDestination = … }` block yields `<module>-classes.txt` and
  `<module>-composables.txt`. Do this in a temporary copy, never in this repository.

## Import aliases

### General rules

- No fully qualified references in source code or KDoc; import declarations instead. Resolve name collisions with explicit import aliases. Package and import declarations and string-based external identifiers are exempt.
- Leave imports unaliased when their simple names are unambiguous.
- For collisions between Compose (`androidx.compose.*`) and Skia (`org.jetbrains.skia.*`), prefix the alias with the source: `Compose<Type>` or `Skia<Type>`. Keep the unaliased name for the other declaration when it remains clear.

### Established names

| Declaration | Import name | Use |
| --- | --- | --- |
| `androidx.compose.ui.graphics.Paint` | `ComposePaint` | When Compose and Skia `Paint` are both used in one file |
| `org.jetbrains.skia.Paint` | `SkiaPaint` | When Compose and Skia `Paint` are both used in one file |
| `org.jetbrains.skia.BlendMode` | `SkiaBlendMode` | When Compose and Skia `BlendMode` are both used in one file |
| `org.jetbrains.skia.ColorFilter` | `SkiaColorFilter` | When Compose and Skia `ColorFilter` are both used in one file |
| `org.jetbrains.skia.Rect` | `SkiaRect` | When Compose geometry `Rect` and Skia `Rect` are both used in one file |
| `android.graphics.RenderEffect` | `AndroidRenderEffect` | When Android and Compose `RenderEffect` are both used in one file |
| `android.graphics.RuntimeShader` | `AndroidGraphicsRuntimeShader` | When Android graphics and the library `RuntimeShader` are both used in one file |

### Adding a new collision

When a new same-file import collision appears, apply the source-prefix convention when it fits. Add its exact declaration-to-alias mapping to **Established names** in the same change, then use that name consistently in later files. Do not invent abbreviations or file-specific variants.

## Hard rules

- Never remove a `@Stable`/`@Immutable` from a public type to "simplify". The annotation is the only channel that carries
  stability across a module boundary: inference covers a module's own sources, while a consumer module treats an
  unannotated type as unstable even when its shape is immutable. Verified with a consumer probe module: the annotation
  is what makes the type `stable` there.
- Keep the public surface honest when adding a type: prefer a shape the compiler can infer (immutable `val`s), and
  annotate anything public that it cannot. Choose `@Stable` when an implementation has mutable or state-backed properties
  and `@Immutable` only when no public property ever changes; a false contract breaks recomposition silently.
- Every effect stage declares its full input footprint with `BackdropSampling`. Coordinates are pixel coordinates in
  surface space, independent of the expanded layer origin, and declarations include interpolation neighbors such as
  bilinear texels. Resolve the chain backwards: serial sampling requirements accumulate, while all intermediate
  rectangles are unioned to produce recording bounds. Do not replace this with a maximum of per-effect padding values.
  Keep effect construction deferred until the final extension is known; runtime shader setup can then use that resolved
  extension. External effects must supply a deterministic, finite, non-inverted sampling contract that covers every
  coordinate they read.
- `explicitApi()` is enabled: public declarations need an explicit `public`.
- Source domains and effect footprints are separate contracts. `BackdropDrawScope` carries the
  current sampling request; finite sources declare pixel-aligned bounds and a `TileMode` before
  effects run. Layer sources default to Clamp, preserving boundary alpha and extended-range RGB.
  Exported source domains exclude unused sampling padding. Boundary resources belong to each
  consuming modifier, so combined and shared sources retain independent boundary policies.
- Select the omitted package prefix from each module's actual package conventions. This repository
  has one module, `:backdrop`, whose prefix is `top.ltfan.backdrop`; do not apply it to other modules.
  Its sources declare `package top.ltfan.backdrop[.sub]` under `backdrop/src/<sourceSet>/kotlin/` with the
  `top/ltfan/backdrop` directory prefix omitted. The remaining package segments map directly to
  directories (`effects/Lens.kt` declares `top.ltfan.backdrop.effects`). Files in the same relative
  directory share the same package. Tests live flat at the test source-set root and declare
  `top.ltfan.backdrop`; import the production subpackages they exercise. Ignore an IDE package-directory
  hint only after verifying this mapping; a different package in the same directory is a real violation.
- Probes, clones and temporary copies go under a temp directory such as `/tmp/<name>`. Never modify this repository from a
  probe, and never commit one.
- Do not push to the upstream repository; do not push or rewrite published history. The owner pushes.
- Comments and KDoc are English: describe current behavior positively, never the change history ("previously", "moved",
  "old"), and never negate something the surrounding text does not establish. State a concrete reason for surprising
  behavior.

## Verified platform facts (do not "fix")

- HDR refresh is Android 14 and later only, because it hangs off `Display.registerHdrSdrRatioChangedListener`; elsewhere
  `BackdropHdrScope` keeps `LocalBackdropHdrHeadroom` at 1, and the desktop `rememberBackdropHdrState` legitimately
  ignores its interval parameter. The library never enables HDR on a window; that belongs to the app.
- Why the HDR scope listens to the ratio at all: retained Compose/HWUI effect layers keep the color space from their
  creation, so a headroom change only reaches the drawn output after the surrounding layer is rebuilt, and a rebuild
  freezes the ratio it was created with. The KDoc states the boundaries of that signal.
- Color matrices stay on the platform filter on purpose: whether that filter clamps extended-range RGB is not uniform
  across platforms, or even within one Android API level (some builds pass Skia's unclamped matrix, others and skiko
  pass the clamped default), and `Build.VERSION.SDK_INT` does not separate them, so no version test can select a
  library-side path. Tint and lighting do use runtime shaders, because the platform APIs take 32-bit ARGB colors there
  (Skia's `Lighting` additionally pins its result), so extended-range colors never reach them. The KDoc on `colorFilter`
  states the same boundary.
- A layer's HDR luminance cannot be measured through public APIs, and the screenshot and readback paths are structurally
  8-bit.

## Change workflow

- Reproduce before fixing, with the compiler rather than by reading: build a minimal probe (a temp copy, or a temp module
  in one) and quote its output in the report.
- Cross-module behavior — stability, API shape, whether a consumer can skip — needs a consumer probe module; the in-module
  report cannot show it.
- Before considering a change done: `:backdrop:spotlessCheck`, the IDE lint at warning severity on the files you touched,
  and `:backdrop:assemble :backdrop:jvmTest --no-build-cache --rerun-tasks`.
- Some lint findings here are intentional and must not be "fixed": the `RuntimeShaderEffect`/`ColorFilterEffect` factory
  names mirror Compose's own `Paint()`/`RuntimeShader()` naming. Decoration factories follow the same type-name
  convention. Weak warnings about inlining a local alias are noise.

## Deliberate decisions (do not "fix")

- AGP is pinned on purpose; treat upgrading it as a change of its own. The value lives in `gradle/libs.versions.toml`.
- `binaries.executable()` on `js` and `wasmJs` is required: without it the Compose plugin's
  `checkComposeUiTestConfigurationForJs` fails the test path.
- `settings.gradle.kts` deliberately sets no `repositoriesMode`: the Kotlin JS/Wasm plugin registers the Node distribution
  as a project repository, and `FAIL_ON_PROJECT_REPOS` makes `kotlinNodeJsSetup` fail.
- `Backdrop` is `@Stable`, not `@Immutable`: `LayerBackdrop` has a state-backed `var graphicsLayer`, so an immutability
  contract would be false.
- `BackdropEffectScope`, `RuntimeShaderCache` and `RuntimeShader` stay unannotated: their properties are mutable without
  notifying the composition, which is the same treatment Compose gives `DrawScope`, `Shader` and `Paint`.
- Keep demo, catalog and app modules out of this repository.
- The package prefix is this project's own, not upstream's, so the two libraries cannot collide on a classpath with
  different ABIs under the same names.
- The README carries no images.

## Visual style contracts

- `BackdropStyle` stores delayed producers. Provider parameters inherit omitted producers; explicit
  modifier producers replace individual defaults. Modifier `null` means inherit, decoration `None`
  means disabled, and `BackdropStyle.NoEffects` selects an empty effect chain.
- Decoration types have a `Config` type and a separate `None` branch. `Highlight.Config` snapshots
  exclusions and provides value equality and `copy()`. Zero-alpha configurations
  remain configurations. Drawing nodes retain observation while disabled and allocate layers only
  for active drawing; disable and detach release layers and reset layer-dependent caches.
- Read visual producers in drawing or effect observation, so snapshot-driven animations invalidate
  rendering without requiring recomposition. Resolve locals at the modifier's use position, and read
  style defaults only for inherited producers. Position-only updates notify coordinate-dependent
  sampling without resolving the effect chain; size, density, font scale and layout direction changes
  resolve its geometry-dependent inputs.
- `drawPlainBackdrop` consumes only the style's effects. Shape, source, drawing callbacks and layer
  control belong to the call site.
- Surface isolation configures its retained layer during placement. Observed layer properties and
  render epochs invalidate placement; measurement supplies size, density and layout direction.
- Highlight emission strength includes color alpha and directional response;
  `Highlight.Config.opacity` controls the shared drawing layer. Highlights use one fixed-width
  profile on the nearest contour. `Highlight.width` is the base width; `HighlightDirection.widthScale`
  maps side-facing incidence to aligned incidence (default 60% to 100%), independently of the
  direction strength response. Each pixel uses its center sample and the configured falloff curve.
  TwoTone emits black
  RGB on the negative side while retaining emission strength. Exclusions mask selected nearest
  outward-normal directions. Custom `HighlightStyle` implementations provide matching straight-alpha
  AGSL and CPU shading; mutable public properties are snapshot-backed under its `@Stable` contract.
  A component retains one output layer for the contour response. `Highlight.colorResponse` samples
  the already processed backdrop at covered samples and changes RGB while preserving emission
  strength. Its default `PreservePeak` transform operates in linear extended-sRGB; sample alpha
  scales the configured tint amount. The backdrop callback is included, while surface and front
  callbacks are not. Display headroom is a refresh signal and never supplies an automatic radiance
  multiplier or RGB limit. Snapshot-driven producers update drawing without recomposition, and
  unsupported runtime shaders leave static emission visible.
  Generic outlines use a uniform-backed full scan over a power-of-two segment capacity; padded
  degenerate segments are skipped. Their geometry caches a per-segment orientation only when the
  normal probe remains separated from every boundary segment; uncertain segments use the original
  containment probe at draw time. Rectangles and circular-corner rounded rectangles use analytic
  bounds and radius uniforms instead of flattened segment geometry. Falloff curve variants are
  specialized independently from direction response curves so their control points keep their own
  semantics.
- Shared interaction lighting is opt-in within the nearest `ProvideInteractionLighting` registry
  and Compose window identity. Sources register input state; `drawBackdrop` receives the shared
  field behind content and clipped to the original surface shape. `drawPlainBackdrop` remains
  effects-only. Source positions and radii use window pixels, with configured dp radius converted
  using source density. Runtime shader fields use linear extended-sRGB. Interior lighting uses native
  Plus blending without an extra layer; contour lighting joins the highlight output and uses its blend
  mode. The unsupported interior fallback uses a cached interpolated SDR vertex mesh. Keep every finite
  source and retain compiled shader-capacity variants while their renderer remains active; disabling
  or detaching the owning effect releases its renderer resources.
