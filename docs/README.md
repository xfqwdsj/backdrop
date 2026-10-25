# Backdrop documentation

Start with [Getting started](getting-started.md) to connect a source to a receiving surface.
[Sources and surfaces](sources-and-surfaces.md) explains pixel ownership and presentation;
[Render graphs](render-graphs.md) covers composition, masks, ordering, and group alpha;
[Effects and sampling](effects-and-sampling.md) explains how processing determines its input needs.
[Custom rendering](custom-primitives.md) defines rendering extensions, dependencies and resource ownership.

For component appearance, read [Styles and decorations](styles-and-decorations.md).
[Interaction lighting](interaction-lighting.md) covers shared visual responses to input, and
[Platforms and HDR](platforms-and-hdr.md) explains the boundary between library and platform behavior.

For maintenance, use [Rendering architecture](architecture.md) to understand lifecycle and
invalidation, then [Development](development.md) for the verification workflow and
[Performance verification](performance.md) for benchmark workloads and measurement boundaries.
[Release contract](releases.md) defines generated drafts, publication gates and performance reporting.

Examples use this fork's `top.ltfan.backdrop` packages. The guides explain how the parts work
together; linked source declarations contain exact parameters and validation rules. Build files
are the source for dependency versions, targets, and publication configuration.
