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

Parameterized surfaces resolve `BackdropStyle` fields at their use position and construct the
same graph primitives as explicit declarations. Graph defaults use `BackdropRenderStyle`.
Reachable process slots own effect contexts whose shader caches support uniform updates across
resolutions. A changed source under an existing shader key replaces that shader.
Omitting a process slot or detaching releases its cache. Refresh invalidates recorded layers.
Composite paints are retained; ordinary opaque SrcOver layer draws bypass the extra save-layer
operation.

Angular highlight profiles store ordered interval channels and precomputed uniform arrays.
Both domains are relative to the configured direction: shortest unsigned separation or a directed
clockwise turn. CPU and shaders share closed-endpoint tolerance; the shader corrects native atan
approximation before testing intervals. Hard cuts use relative atan2 rather than acos(dot), which
loses precision near parallel normals. Each response uses the nearest-contour geometry.

## Keep ownership local

Recording belongs to the source producer; processing and presentation resources belong to their
consuming modifier nodes. Image-composition scopes share graph ownership; only the root receiver
scope selects foreground and exported outputs. DSL receiver boundaries keep nested declarations
from accidentally targeting an outer output. A node releases its layers and dependent caches when
its rendering role ends. Shared interaction state belongs to the provider and input owners that define its lifetime.
Caller-supplied resources retain caller ownership across library refreshes.

[BackdropSourceRenderer](../backdrop/src/commonMain/kotlin/internal/BackdropSourceRenderer.kt)
keeps boundary resources per receiver. Sharing a backdrop therefore does not share the receiver's
boundary policy or temporary sampling buffers. Export domains describe visible output in layer
pixels, rounded outward after storage translation. They exclude unused sampling padding; buffer
allocation and source-domain bounds serve separate contracts.

[BackdropGraphRenderer](../backdrop/src/commonMain/kotlin/internal/BackdropGraphRenderer.kt) owns
intermediate images and primitive renderers per receiver. Resolution traverses only reachable
images, propagates all input requests backwards, and constructs deferred effects after the final
storage extension is known. Source domains and sampling requests have separate bounds. Each shared
image records once per receiver draw; presentation and export reuse those recordings.

[ProcessedBackdropFrame](../backdrop/src/commonMain/kotlin/ProcessedBackdropFrame.kt) passes an
explicit graph image to highlight color response within the same draw, together with interaction
entries. It is invalidated before replacing its underlying resources. The declaration chooses
whether that image includes earlier paint or composites. Every intermediate uses the native
rendering path; no CPU pixel readback is used.

Effect packages declare graph primitives through scope extensions. Public primitive registration
validates graph ownership and delegates execution to the receiver. Each primitive declares a
finite output domain and independent input sampling. Typed per-receiver renderers prepare after
all storage requests resolve; borrowed input views draw dependencies without exposing mutable
graph layers. Empty image domains contribute no visible extent. Display refresh drops transient
layers while preserving shader caches; omission, incompatible renderer keys and detach release
the renderer and its resource owner. [Custom primitives](custom-primitives.md) defines this contract.

Public deferred factories use `BackdropEffectContext`, a read-only view of final geometry and the active stage input. It holds
a Density value and has no stability annotation. Stage shader-cache keys are namespaced by stage
position; scope-level shader access supplies the underlying cache and observation path.

## Invalidate at the phase that consumes state

Visual producers are observed by drawing or effect resolution. Explicit producers avoid reading
replaced style defaults, and inherited producers resolve locals at the modifier's use position.
Position-only updates invalidate coordinate-dependent source sampling; changes to size, density,
font scale, layout direction, or observed geometry inputs resolve the graph before drawing.
Sampling extension is resolved before isolation placement so recording and isolation use consistent origins.

Surface isolation captures size, density, and layout direction during measurement, then configures
its retained layer during placement. Observed layer properties and render epochs invalidate
placement, allowing group-alpha changes without remeasuring a parent. Skiko isolation filters use
the expanded buffer coordinates: the surface origin lies at the left and top recording margins,
and the filter covers both margins on each axis.
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
response. Direction strength and direction-scaled width are independent. The falloff curve has its own
specialization. Two-tone black retains emission strength. Exclusions mask that nearest
normal. The output uses one retained contour layer, with processed-backdrop color response changing
RGB and interaction contour lighting joining the same output. Custom CPU and shader functions must
agree on straight-alpha shading before the shared profile and premultiplication.

Follow [HighlightRenderer](../backdrop/src/commonMain/kotlin/highlight/HighlightRenderer.kt),
[HighlightBoundaryGeometry](../backdrop/src/commonMain/kotlin/highlight/HighlightBoundaryGeometry.kt),
and [FlattenedBoundaryGeometry](../backdrop/src/commonMain/kotlin/internal/FlattenedBoundaryGeometry.kt)
when changing this path. User-facing controls belong in [Styles and decorations](styles-and-decorations.md).

## Platform adapters

Common code owns rendering contracts and orchestration. Platform adapters supply native rendering
operations and resource-refresh signals. The source-set hierarchy and target coverage belong to the
[module build](../backdrop/build.gradle.kts); platform behavior and fallback boundaries are described
in [Platforms and HDR](platforms-and-hdr.md). Validate display behavior at the platform boundary as
well as testing the shared calculations.
