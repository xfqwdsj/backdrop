# AGENTS.md

## Scope and navigation

This repository ships one library module, `:backdrop`. Keep demo, catalog, and app modules out of it.
Backdrop is an independent fork: upstream code is reference material, not this project's specification.

Read [the documentation guide](docs/README.md) for the relevant subsystem before changing behavior.
[Rendering architecture](docs/architecture.md) explains ownership and invalidation;
[development](docs/development.md) covers verification, build constraints, and import aliases.
Keep those documents current when their contracts change. README is the short integration entry
point; preserve its existing badges and setup instructions. Keep visual galleries in dedicated
documentation. Versions and publication metadata belong in their build files, not in
duplicated documentation inventories.

## Change discipline

- Preserve unrelated working-tree edits and keep changes scoped to the request.
- Reproduce a defect before fixing it with a minimal executable probe; quote its output in the report.
  Put probes, clones, and temporary copies under a temporary directory, never in this repository.
- For stability, API shape, or consumer skipping, use a separate consumer probe module. An in-module
  compiler report cannot establish cross-module behavior. Generate Compose metrics in a temporary copy.
- Before considering a change done, run `./gradlew :backdrop:spotlessCheck`, IDE lint at warning
  severity on touched files, and
  `./gradlew :backdrop:assemble :backdrop:jvmTest --no-build-cache --rerun-tasks`.
  Report skipped, incomplete, or unavailable checks explicitly.
- Do not push or rewrite published history. The owner pushes, including to upstream.

## Public API and source conventions

- `explicitApi()` requires explicit `public` declarations.
- Never remove a public `@Stable` or `@Immutable` to simplify a type. Consumer stability depends on
  that contract. Prefer immutable `val` properties; annotate public types the compiler cannot infer.
  Use `@Stable` for snapshot-backed mutable behavior and `@Immutable` only when public properties
  never change. Do not annotate unsignalled mutable drawing scopes, shaders, or caches as stable.
- `Backdrop` is `@Stable`: `LayerBackdrop.graphicsLayer` is snapshot-backed and replaceable.
- Sources use `top.ltfan.backdrop[.sub]` under `backdrop/src/<sourceSet>/kotlin/`, omitting the
  `top/ltfan/backdrop` directory prefix. Remaining package segments match directories. Tests live at
  the test source-set root in `top.ltfan.backdrop` and import production subpackages.
  Verify this mapping before dismissing an IDE package-directory warning; different packages in
  the same directory are a violation.
- Use imports rather than fully qualified references in source or KDoc. Package/import declarations
  and string-based external identifiers are exempt. Leave unambiguous names unaliased; resolve
  Compose/Skia collisions with `Compose<Type>` or `Skia<Type>`. Follow the
  [established alias table](docs/development.md#import-aliases), updating it for new collisions.
- Comments and KDoc are English and describe current behavior positively. Explain the concrete
  reason for surprising behavior; omit change history and ungrounded negative claims. Overviews
  describe responsibilities and contracts rather than inventories of current features or internals.
  Keep member-specific details with the member that defines them.
- Type-name factories copy the name of the type they construct. An exact match between the function
  name and declared return type name needs no naming suppression. When the names differ, verify
  that the returned type is compatible with the named type, then locally suppress `FunctionName`.
  Use ordinary lower-camel-case names for functions that do not construct a corresponding type.

## Rendering invariants

- Every effect declares its full `BackdropSampling` footprint in surface pixels, including
  interpolation neighbors. Resolve serial stages backwards and union all intermediate rectangles.
  Build deferred stages only after the final extension is known. Validate deterministic, finite,
  non-inverted contracts; a maximum of individual padding values is insufficient.
- Keep source domains separate from effect footprints. Finite sources declare pixel-aligned bounds
  and a tile policy before effects. Boundary resources belong to each consuming modifier; combined
  sources retain independent policies and exported domains exclude unused sampling padding.
- Preserve delayed visual producers and use-position style lookup. Only inherited fields read
  defaults; snapshot reads belong in drawing or effect observation. Position changes update
  coordinate-dependent sampling without resolving the effect chain; geometry changes resolve it.
- Preserve `null` style inheritance, explicit render replacement, `BackdropRenderStyle.NoEffects`, and the fixed-order
  `BackdropStyle.NoEffects` appearance.
  Treat zero-alpha values as configured primitives. Omitted primitives keep declaration observation,
  release renderer resources, and reset layer-dependent caches. The caller owns source, shape,
  graph ordering, foreground, export selection, and layer control. Never read rendered pixels back
  to implement graph operations.
- Configure surface isolation during placement from measured geometry. Layer-property and render
  epoch observations invalidate placement.
- Highlights evaluate one nearest-contour response with independent width and strength curves.
  Color response changes RGB, preserving emission; custom CPU and AGSL implementations must match.
  Preserve the analytic and generic geometry contracts in [architecture](docs/architecture.md).
- Interaction lighting is opt-in within a provider registry and Compose window identity. Preserve
  window-pixel geometry, input ownership, additive interior blending, and contour integration.
  Keep finite sources and active shader-capacity variants; release renderer resources on disable or detach.
- Display headroom is a refresh signal, never an automatic RGB multiplier or limit. HDR window
  setup belongs to the app. Preserve the platform boundaries in [Platforms and HDR](docs/platforms-and-hdr.md),
  including platform matrix filters and caller-owned layers.

## Build decisions

AGP is deliberately pinned; upgrade it only as a separate change. Preserve JS/Wasm executable
binaries and the absence of `repositoriesMode` in settings; the reasons and lockfile workflow are
in [development](docs/development.md#build-constraints).
