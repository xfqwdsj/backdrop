# Sources and surfaces

Choose the source according to who owns the pixels. A `Backdrop` describes how to draw those pixels
for a receiver; it is separate from the receiver's shape, effects, and foreground.

## Choose a source

Record Compose content with `rememberLayerBackdrop` and `Modifier.layerBackdrop` when the source
must follow another layout position. Use `rememberCanvasBackdrop` when the source generates pixels
for each request. Sources can be composed or wrapped to reuse their drawing without coupling it
to a particular receiver; the [source APIs](../backdrop/src/commonMain/kotlin/backdrops) define those
operations.

A layer source depends on layout coordinates. Its source-to-receiver transform includes ancestor
and modifier-layer geometry; procedural sources use the receiver's drawing space directly.
Source transforms must be finite, invertible two-dimensional affine mappings.

The default graphics layer returned by `rememberLayerBackdrop` belongs to Backdrop. Its handle can
change during a rendering refresh while the `LayerBackdrop` identity stays the same. If you supply
a `GraphicsLayer`, you own its lifecycle and refresh.

## Decide what happens beyond the source

The source domain answers which pixels exist. The [sampling contract](effects-and-sampling.md#custom-effects)
answers which pixels an effect needs. Increasing sampling room does not invent source content.

Layer sources use `TileMode.Clamp` by default. This repeats the boundary texel, including its alpha
and extended-range RGB. Select `Decal` for transparent exterior pixels, `Repeated` for repetition,
or `Mirror` for mirrored repetition. Each member of a combined source keeps its own policy.
Transparency inside a source remains transparency under every policy.

```kotlin
val backdrop = rememberLayerBackdrop(tileMode = TileMode.Decal)
```

For procedural content, draw the entire requested region rather than assuming it equals `size`:

```kotlin
val backdrop = rememberCanvasBackdrop {
    drawRect(color, topLeft = samplingBounds.topLeft, size = samplingBounds.size)
}
```

A finite procedural source calls `drawSource(bounds, tileMode) { ... }`. Bounds must be non-empty,
finite, and pixel-aligned. The callback retains source coordinates, receives source size, and is
clipped to those bounds; it can run more than once. Use `BackdropDrawScope.withTransform` or the
`drawSource` transform parameter so the sampling request follows source-space transforms.
Finite-source edge extension needs runtime shaders; the fallback draws transparent exterior pixels.
See [BackdropDrawScope](../backdrop/src/commonMain/kotlin/BackdropDrawScope.kt) for the contract.

## Draw a surface

Use `drawBackdrop` for a surface that combines source processing with decorative styling.
`drawPlainBackdrop` provides the processing and callback path; it inherits effects and insets
without receiving decorative styling or interaction light. Both leave component geometry and
foreground drawing under the caller's control.

Within the surface draw node, drawing proceeds in this order:

1. `onDrawBehind` paints behind the processed source.
2. `onDrawBackdrop` receives a lambda that draws the source into its effect layer. Invoke that
   lambda to retain source drawing when customizing this callback.
3. `onDrawSurface` paints over the processed result, for example a tint or mask.
4. Interior interaction lighting illuminates the surface below foreground content.
5. The receiver's own content draws inside the original shape.
6. `onDrawFront` paints above that content.

Callbacks draw in surface-local coordinates and receive `BackdropRoom`: use its `size` when
positioning relative to the component, and its `extension` when accounting for extra recording room.
The callback region includes visible effect coverage; the content retains the original shape.

Both modifiers isolate compositing offscreen so overlapping parts receive group alpha once.
Compositing bounds include effect extension and outer-shadow support. Layout size and hit testing
stay unchanged. Caller transforms and larger layer outsets are retained; caller clipping can still
cut off extended drawing.

## Export a processed surface

Pass another remembered `LayerBackdrop` as `exportedBackdrop` to sample a surface from subsequent
receivers. The export records the processed backdrop and the behind, surface, and front callbacks,
plus interior interaction lighting. It excludes the receiver's child content and the separate
highlight/shadow decorations. Its source domain is the drawn region, excluding unused sampling
padding; this keeps boundary policies tied to visible content.

[DrawBackdropModifier](../backdrop/src/commonMain/kotlin/DrawBackdropModifier.kt) owns this ordering.
For diagnosing empty or stale output, inspect source attachment and recording, coordinate mapping,
and caller clipping before adjusting effect strength. See
[development](development.md#choose-regression-coverage) for choosing regression coverage.
