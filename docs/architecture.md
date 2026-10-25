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

A unified declaration describes ordered image operations and optional fixed surface phases.
Fixed surfaces resolve `BackdropStyle` fields at their use position and combine a serial optical
chain. Lowering shares associative SrcOver drawing with a single consumer; sampled dependencies,
destination-dependent operations and explicit isolation retain image boundaries. Sampling geometry is resolved before
placement configures surface isolation. Actual measurement resolves size-dependent bounds before
enclosing isolation layers are placed. Global position changes update source mapping without resolving
optics. Measured size changes retain image and fixed-surface declarations that did not read
`size` while declaring their structure. Nested scopes share the current measured size; effect and
drawing callbacks receive that updated geometry. Declaration-time `size` reads rebuild the graph
on resizing, preserving size-dependent branches and captured constants. Density, layout direction,
shape and producer changes invalidate the declaration.
Background alpha below one uses one output covering source, shadow and background drawings.
Custom `BackdropDecoration` renderers share receiver resource ownership and invocation-scoped input
views with graph rendering. Their declared bounds contribute to placement outsets.

`BackdropRenderStyle` supplies the root declaration and insets. `surface { }` uses the same image
operations at any nesting level, so it shares effect, primitive and resource lifecycle machinery.
Reachable process slots own effect contexts whose shader caches support uniform updates across
resolutions. A changed source under an existing shader key replaces that shader.
Omitting a process slot or detaching releases its cache. Refresh invalidates recorded layers.
Layered progressive blur owns its branches through the public primitive resource pool and records
them with the same surface-coordinate helper available to extensions. It retains gradient weights
across radius updates and reuses layers for levels that remain active when the ramp changes.
Composite paints are retained; SrcOver layer draws with alpha 1 bypass the extra save-layer
operation. Shadow renderers retain native paint configuration and immutable inner-blur effects
across geometry-only recordings. Color and radius changes update the affected resources;
refresh and release clear their retained configuration.

Contour shader variants are retained by structural signature. Geometry and visual uniforms update
with each recording and each selection of a retained variant.
Contour primitives retain snapshot-observed outlines by shape, size, layout direction and density.
Optical parameter changes retain the flattened contour; shape snapshot reads still invalidate it.
Curve subdivision accumulates coordinates in primitive float storage, preserving segment order
and the pixel flatness tolerance while avoiding per-coordinate boxing during resizing.
Generic contour search retains contiguous segment groups and their enclosing bounds. A group
whose distance lower bound cannot improve the selected boundary skips its segment tests.
Remaining segments retain declaration order and the nearest-boundary tolerance;
the full contour remains available for outward-normal fallback. Group bounds share geometry
lifetime and refresh when padded uniform capacity changes.

Angular highlight profiles store ordered interval channels and precomputed uniform arrays.
Both domains are relative to the configured direction: shortest unsigned separation or a directed
clockwise turn. CPU and shaders share closed-endpoint tolerance; the shader corrects native atan
approximation before testing intervals. Hard cuts use relative signed-angle evaluation
to retain precision near parallel normals. Each response uses the nearest-contour geometry.

## Keep ownership local

Recording belongs to the source producer; processing and presentation resources belong to their
consuming modifier nodes. Image-composition scopes share graph ownership; only the root receiver
scope selects foreground and exported outputs. DSL receiver boundaries keep nested declarations
from accidentally targeting an outer output. A node releases its layers and dependent caches when
its rendering role ends. Shared interaction state belongs to the provider and input owners that define its lifetime.
Receivers observe window-local light presence separately from drawing inputs. An empty window
selects the image preceding interior lighting, leaving its lighting nodes unreachable. Retaining
declaration positions preserves subsequent primitive renderers and their owned caches; only
reachable lighting nodes receive rendering resources. Interior lighting checks the finite support
of each light against affine receiver bounds before creating or binding its runtime shader. Corner
bounds include coordinate-rounding room; perspective receivers retain the general field path.
Movement updates this coverage check while retaining shader and primitive identity. Zero-intensity
lights whose support overlaps the receiver remain configured. Presence changes resolve the declaration;
source movement, intensity and membership changes within a nonempty window update recording
without resolving the effect chain.
Caller-supplied resources retain caller ownership across library refreshes.

[BackdropSourceRenderer](../backdrop/src/commonMain/kotlin/internal/BackdropSourceRenderer.kt)
keeps boundary resources per receiver. Sharing a backdrop therefore does not share the receiver's
boundary policy or temporary sampling buffers. Export domains describe visible output in layer
pixels, rounded outward after storage translation. They exclude unused sampling padding; buffer
allocation and source-domain bounds serve separate contracts.

[BackdropRenderer](../backdrop/src/commonMain/kotlin/BackdropRenderer.kt) owns
intermediate images and primitive renderers per receiver. Resolution traverses only reachable
images, propagates all input requests backwards, and constructs deferred effects after the final
storage extension is known. Source domains and sampling requests have separate bounds. Surface
clipping bounds its upstream request by the extended outline, retaining one pixel of antialiasing room.
Graph recording evaluates the current outline before reusing owned rounded-path geometry. Generic outlines
retain their fill rule when rebuilt for extended coverage.
A wider shadow branch still contributes its complete output bounds to graph storage without
forcing the clipped material's filters to sample that entire branch. Identity processing stages
and SrcOver draws with alpha 1 over a transparent base reuse their input storage;
resolution still observes their declarations and retains their complete sampling and coverage.
Adjacent native filters share one recording when each intermediate has a single consumer.
An exclusively consumed source records directly into that filtered layer. Independent foreground,
export and primitive readers retain their own shared images; fusion preserves every declared
sampling request and effect observation.
Within a graph recording, each shared image records once; presentation and export share it.
Recordings remain valid while their observed drawing inputs and resolved geometry stay unchanged.
Background, foreground and export retain independent snapshot observations in their drawing order,
so reused recordings keep their dependencies when opacity or child content changes. Position,
geometry, interaction membership and resource refreshes also invalidate recording. A visibility
transition prepares the active outputs and releases unused source-boundary resources.
Background alpha one presents the selected graph layer directly. Fractional background alpha
applies once through a receiver-owned presentation layer; returning to one releases that layer.
The selected export image is prepared before export recording, preserving the same graph-image
sharing across background and export.
Ordinary intermediate layers store the outward-rounded pixels requested from that node, with
surface-clip storage limited to its outline bounds and antialiasing room. Storage origins and sizes
resolve with the graph; integer placement preserves surface-local drawing coordinates. Native effect
layers retain the shared filter storage origin so their sampling coordinates remain consistent. Draw-scope and brush
sizes remain the surface size independently of intermediate storage dimensions.
Geometry wrappers and SrcOver composites use automatic compositing while retaining recorded
clipping. Paint defaults to offscreen isolation and exposes a compositing strategy for callers
whose drawing can compose directly into its consumer. Custom drawing, masks and primitives retain
offscreen isolation for destination blending; native effects also require offscreen rendering.

[ProcessedBackdropFrame](../backdrop/src/commonMain/kotlin/ProcessedBackdropFrame.kt) passes an
explicit graph image to highlight color response within the same draw, together with interaction
entries. It is invalidated before replacing its underlying resources. The declaration chooses
whether that image includes earlier paint or composites. Every intermediate uses the native
rendering path, keeping intermediate pixels in graphics resources.

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

Generic contour geometry also certifies a central rectangle containing no boundary segments.
The highlight shader shrinks this rectangle by its maximum band width and antialiasing room,
then rejects transparent interior pixels before scanning segments. Holes, concavities and
independent contours contribute to the same certificate; shapes without a usable empty region
retain the complete nearest-contour calculation.

Generic shaders scan a power-of-two segment capacity. Once a nearest candidate exists, a segment's
axis-aligned distance lower bound can reject candidates beyond that distance plus a float guard.
Remaining candidates retain declaration order and the nearest-boundary tie tolerance. Padding is
degenerate and skipped, preserving nearest-boundary and containment results. Geometry caches segment orientation only
when its normal probe is separated from every boundary segment; uncertain segments retain the
containment probe at draw time. These conditions matter for thin features and nearby contours.

Larger contours build a temporary group-bound index while generating orientation certificates.
It rejects disjoint segment groups and containment groups outside the query's vertical range.
Small contours use direct scans; the resulting certificate persists with geometry while its
construction index is released.

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
