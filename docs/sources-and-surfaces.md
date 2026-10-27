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
Every tile policy preserves transparency inside the source.

```kotlin
val backdrop = rememberLayerBackdrop(tileMode = TileMode.Decal)
```

For procedural content, draw the entire requested region, which can extend beyond `size`:

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

`Modifier.drawBackdrop` maps the source into receiver coordinates and evaluates one declaration:

```kotlin
Modifier.drawBackdrop(backdrop, shape = { shape }) {
    surface {
        effects { blur(12.dp.toPx()) }
        fill(tint)
        highlight(Highlight.Ambient)
    }
    val overlay = layer {
        fill(Color.White.copy(alpha = .1f))
        highlight(Highlight.Ambient)
    }
    draw(overlay)
}
```

`surface { }` processes its selected input through ordered optical stages and surface drawing
phases. Unset appearance slots inherit [styles](styles-and-decorations.md). At the root, surface
paint and interior lighting draw before children; highlights and inner shadows draw after them.
Inside an image or layer, all phases complete that image before it is composed elsewhere.
Use `styledSurface(style)` at the root to apply a fixed-order appearance without declaring its
individual stages. Background opacity below one applies once to the complete background result.
A stateful decoration owns only the construction resources its algorithm needs.

The same DSL also exposes [image operations](render-graphs.md). Select a processed image with
`replace(image)`, composite images with `draw(image)`, and paint with `paint` or `paintSurface`.
Call `source()` followed by `drawWithContent { drawContent, room -> ... }` to customize source
drawing before processing; invoke `drawContent()` inside that callback to include the source. These
drawing operations receive surface-local coordinates and `BackdropRoom`, including the full sampling
extension.

The selected result draws behind children. Children retain the original shape clip.
`foreground { ... }` draws an explicit transparent-based result above them. Primitive placement is
explicitly ordered by this declaration.

The modifier isolates compositing offscreen. `backgroundAlpha` applies once to the complete
background result; children and foreground have independent opacity. Compositing bounds include visible
effect coverage and outer-shadow support. Layout size and hit testing use the measured surface bounds. Caller
transforms and larger layer outsets are retained; caller clipping can cut off extended drawing.

## Export a processed surface

Pass a remembered `LayerBackdrop` as `exportedBackdrop` to sample a result from subsequent receivers.
The default export is the background result after background alpha, excluding children and foreground.
`export(image)` selects another graph image explicitly. Decorations are included when present in
that selected image. The exported layer is a finite source: its domain is the selected output's
bounds translated into layer-storage coordinates, then rounded outward to whole pixels. Left and
top use floor; right and bottom use ceiling. This includes every pixel touched by fractional
coverage, such as insets converted at a non-integer density. An empty output exports an empty
domain. Unused sampling padding stays outside that domain even when the recording buffer is larger.

[RenderBackdropModifier](../backdrop/src/commonMain/kotlin/RenderBackdropModifier.kt) owns presentation
and export. For empty or stale output, inspect source attachment and recording, coordinate mapping,
and caller clipping before adjusting effect strength. See
[development](development.md#choose-regression-coverage) for choosing regression coverage.
