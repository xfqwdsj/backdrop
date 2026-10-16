# Rendering architecture

Backdrop separates source acquisition, effect resolution, and surface presentation. Keeping these
contracts distinct lets one recorded source serve several receivers with different sampling needs
and boundary policies.

## Follow a frame

A source supplies pixels for the receiver's request. The receiver resolves its visual producers
and propagates sampling requirements backwards through the effect chain before recording. Deferred
effect construction then uses those resolved bounds, and presentation combines the processed source
with the component's own drawing.

The concrete draw and export ordering is in [Sources and surfaces](sources-and-surfaces.md).
The sampling coordinate contract is in [Effects and sampling](effects-and-sampling.md).

## Keep ownership local

Recording belongs to the source producer; processing and presentation resources belong to their
consuming modifier nodes. A node releases its layers and dependent caches when its rendering role
ends. Shared interaction state belongs to the provider and input owners that define its lifetime.
Caller-supplied resources retain caller ownership across library refreshes.

[BackdropSourceRenderer](../backdrop/src/commonMain/kotlin/internal/BackdropSourceRenderer.kt)
keeps boundary resources per receiver. Sharing a backdrop therefore does not share the receiver's
boundary policy or temporary sampling buffers. Export bounds exclude unused padding.

[ProcessedBackdropFrame](../backdrop/src/commonMain/kotlin/ProcessedBackdropFrame.kt) hands the
already processed layer to highlight color response within the same draw. It also publishes
interaction entries for the contour. It is invalidated before replacing its underlying resources.
The processed color source includes the backdrop callback, while surface tint and foreground stay
outside it.

## Invalidate at the phase that consumes state

Visual producers are observed by drawing or effect resolution. Explicit producers avoid reading
replaced style defaults, and inherited producers resolve locals at the modifier's use position.
Position-only updates invalidate coordinate-dependent source sampling; changes to size, density,
font scale, layout direction, or observed geometry inputs resolve the effect chain.

Surface isolation captures size, density, and layout direction during measurement, then configures
its retained layer during placement. Observed layer properties and render epochs invalidate
placement, allowing group-alpha changes without remeasuring a parent.
[EpochResourceOwner](../backdrop/src/commonMain/kotlin/internal/EpochResourceOwner.kt) creates a
replacement before releasing the previous resource. A `LayerBackdrop` retains identity across
owned-layer refreshes, and consumers observe replacement/recording state before early returns so
an initially empty or detached source can become visible on a later recording.

## Resolve the nearest contour

Rectangles and rounded rectangles with circular corners use analytic bounds and radius uniforms.
Other outlines use cached flattened boundary geometry, while the original fill-rule-aware outline
clip preserves holes, concavities, and disconnected regions. Elliptical or unsuitable rounded
outlines keep the generic path.

Generic shaders scan a power-of-two segment capacity. Padding is degenerate and skipped rather
than changing nearest-boundary or containment results. Geometry caches segment orientation only
when its normal probe is separated from every boundary segment; uncertain segments retain the
containment probe at draw time. These conditions matter for thin features and nearby contours.

Each covered pixel uses its nearest boundary position and outward normal to evaluate one shading
response. Direction strength and direction-scaled width remain independent, as does the falloff
curve specialization. Two-tone black retains emission strength. Exclusions mask that nearest
normal. The output uses one retained contour layer, with processed-backdrop color response changing
RGB and interaction contour lighting joining the same output. Custom CPU and shader functions must
agree on straight-alpha shading before the shared profile and premultiplication.

Follow [HighlightModifier](../backdrop/src/commonMain/kotlin/highlight/HighlightModifier.kt),
[HighlightBoundaryGeometry](../backdrop/src/commonMain/kotlin/highlight/HighlightBoundaryGeometry.kt),
and [FlattenedBoundaryGeometry](../backdrop/src/commonMain/kotlin/internal/FlattenedBoundaryGeometry.kt)
when changing this path. User-facing controls belong in [Styles and decorations](styles-and-decorations.md).

## Platform adapters

Common code owns rendering contracts and orchestration. Platform adapters supply native rendering
operations and resource-refresh signals. The source-set hierarchy and target coverage belong to the
[module build](../backdrop/build.gradle.kts); platform behavior and fallback boundaries are described
in [Platforms and HDR](platforms-and-hdr.md). Validate display behavior at the platform boundary as
well as testing the shared calculations.
