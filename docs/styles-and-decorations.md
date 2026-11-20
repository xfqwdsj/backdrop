# Styles and decorations

`drawBackdrop` accepts one declaration for surface drawing and image composition. Effects, fill,
highlights and shadows work at the root and inside `image` or `layer` blocks. Single-consumer,
associative drawing can share its destination recording; a sampled result or explicit `layer`
retains its own compositing boundary.

## Construct and apply a style

```kotlin
val glass = BackdropStyle {
    blur = { BackdropBlur.Uniform(8.dp) }
    fill = { BackdropFill(tint) }
    highlight = { Highlight.Default }
}
val panel = glass {
    blur = { BackdropBlur.LayeredProgressive(16.dp, BackdropEdge.Top) }
    fill = null
}
ProvideBackdropStyle(glass) {
    Box(Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(24.dp) }) {
        styledSurface(panel)
    })
}
```

`BackdropStyle { }` creates immutable overrides. `style { }` derives an independent value;
its raw constructor and mutable builder are private implementation details. Producers remain delayed and derivation
copies their references without retaining a parent chain. Later assignments replace earlier ones.
An unset field retains its previous configuration when deriving. Assigning `null` clears that
override and restores the receiving position's theme, including when the base had an explicit value.
Use `None` or a neutral value to disable an effect; a transparent value remains explicitly configured.
An empty block reuses the original value. Equality compares producer references without running them.

`ProvideBackdropStyle(style)` merges explicit producers over its parent. `Default` resets all fields;
`NoEffects` explicitly selects neutral optical processing, transparent fill, no insets and disabled
decorations. An empty style overrides nothing. Construction reads no CompositionLocal.
`styledSurface()` and `styledSurface(style = null)` apply the receiving theme.
Only inherited fields read theme defaults. Drawing and effect parameters stay observed in their
consuming phases, so fill-color and blur-radius animations do not rebuild the graph.

`styledSurface` belongs only to the root `BackdropRenderScope`. It owns one background and one
foreground with this fixed order:

1. Color controls, the selected blur algorithm, vibrancy, lens, then shape clipping.
2. Background: outer shadow, processed source, fill, additive interior interaction light, then the
   environment-lit highlight.
3. Compose child content.
4. Foreground: inner shadow.

The highlight samples a Gaussian-filtered input built from the processed source and interior
interaction light before fill and child content.
Vibrancy appends saturation to the existing color processing. `BackdropBlur` explicitly selects
uniform, shader-progressive or layered-progressive blur; unsupported-platform behavior follows the
selected algorithm. The library chooses no fallback. A fill's ramp and edge request their
own coverage independently of blur. A null fill brush paints nothing and null alpha means one in
this pipeline; consumers may resolve those values through their own theme before constructing it.
Modifier insets override style insets. `backgroundAlpha` fades the complete background independently
of foreground and child content; exports retain the graph's selected image and presentation opacity.

A root accepts one `styledSurface`, without another root `surface` or existing foreground.
`foreground { }` keeps replacement semantics for free composition; after `styledSurface` use
`appendForeground { }` to add drawing without replacing the fixed foreground. Additional
background drawing uses ordinary `image`, `group`, `draw`, `paint` and `primitive` operations.
`decoration(value, processedSource)` exposes stateful decoration drawing at any graph position,
with the same automatic compositing and renderer-resource reuse as surface decorations.
Style configuration contains no arbitrary optical or drawing callbacks.

## Compose surface phases

`surface { }` remains a separate graph-composition API, also available in images and explicit
layers. An unset appearance slot inherits its delayed producer at the modifier's use position.
A setter replaces that slot; repeated `effects { }` blocks append stages while replacing inherited
optical processing. Drawing callbacks and decoration declarations append within their phases.
This API lets the caller compose surface phases through its own declaration receiver.

Read optical parameters inside `effects { }`, decoration configuration inside `surface { }`, and
drawing values inside paint callbacks. Each read then invalidates the phase that consumes it:

```kotlin
Modifier.drawBackdrop(
    backdrop,
    shape = { shape },
) {
    surface {
        effects { blur(animatedRadius.value.dp.toPx()) }
        highlight(if (enabled.value) Highlight.Default else Highlight.None)
    }
    val overlay = layer {
        fill(Color.White.copy(alpha = .1f))
        highlight(Highlight.Default)
    }
    draw(overlay)
}
```

Inside `surface { }`, `paintSurface { room -> ... }` draws above its source and fill, clipped to visible coverage.
`paintForeground { room -> ... }` draws after that surface’s foreground decorations. Both callbacks draw into the
current destination. Use an explicit `layer { }` when drawing requires an independent compositing boundary.
Stateful extensions use `BackdropDecoration`, with `drawBehind`, `drawSurface` or `drawForeground`;
see [custom rendering](custom-primitives.md#surface-decorations).

`BackdropRenderStyle` separately supplies `render` and `insets` to graph receivers. Its `NoEffects`
is an empty render declaration. See [Render graphs](render-graphs.md) for composition and export.

## Shape the contour response

`Highlight.Contour`, also built by `Highlight(...)` or `Highlight { ... }`, defaults to a
`2.dp` band. An environment image passes through a standard Gaussian blur and is sampled in place
by visible inner-band pixels. Set `blurRadius = 0.dp` to sample the environment directly.

Use `highlight(Highlight.Basic())` for a uniform white inner stroke. Its default width is `1.dp`,
and its parameters are `width`, `baseBrightness`, and `opacity`. The environment input and Gaussian
blur belong to contour highlights. Platforms without runtime shaders use the same stroke drawing
path with the configured width, base brightness, and opacity.

`HighlightDirection(angle, mode, strength, widthScale)` uses normal incidence: the normal's dot
product with the chosen direction is truncated for Single or folded by absolute value for Double.
Its width and strength have independent curves. `Uniform` removes directional modulation.
The inner-band profile is fixed to `1 - smoothstep(0, localWidth, distance)`.

For explicit angular curves, surface and graph declarations accept the same configuration values.
Import `highlight` and `angles` from `top.ltfan.backdrop.highlight`:

```kotlin
highlight {
    width = 2.dp
    angles(angle = 90f, domain = HighlightAngleDomain.Directed) {
        widthScale(0f, 180f, .5f, 1f, curve = BackdropCurve.CubicBezier(.4f, 0f, .8f, 1f))
        brightness(0f, 180f, .2f, 1.5f, curve = BackdropCurve.CubicBezier(.2f, 0f, .8f, 1f))
        mirror()
        clear(setOf(0f..30f, 330f..360f))
    }
}
```

`Highlight { ... }` also builds that configuration independently. Graph declarations accept an
explicit `environment` image. The surface DSL uses its processed source and interior interaction
light. Import `incidence` from the highlight package to select a normal-incidence response inside
the builder. Both branches use `angle` for orientation: 0 points right, 90 down, 180 left, 270 up.
Angular segments are relative to that orientation.

| Domain | Relative coordinate |
| --- | --- |
| `Unsigned` | Shortest separation in 0..180 degrees; both sides share the response. |
| `Directed` | Clockwise turn in 0..360 degrees; the sides can differ. |

Each segment interpolates from `from` to `to` over its relative angular interval using `curve`.
Width and brightness accept independent `BackdropCurve.Linear` or `BackdropCurve.CubicBezier`
responses; omitting `curve` selects linear interpolation.
Width scale multiplies base width; brightness multiplies emission and may exceed one for HDR.
Undefined channels are zero. Later declarations replace earlier values only in their specified
interval and channel. `clear` hard-zeros both channels. Shared endpoints are closed with a 0.001°
tolerance; later declarations win there too. Split cross-seam intervals at 0/360. `mirror(axis)`
appends reflected declarations including reversed curves; Unsigned already folds the two sides.
Profiles snapshot and precompute uniform arrays once and can be shared across rotated instances.
Shaders are specialized by segment capacity, not individual values or rotation.

Parameterized `disabledAngles: Set<ClosedFloatingPointRange<Float>>` applies a final hard cut.
`disabledAngleDomain` supports the same Unsigned and Directed meanings. These ranges are also
relative to the direction's `angle`.
For a uniform response, the orientation is zero. Cuts affect the complete environment-lit response.

## Color and geometry

The renderer evaluates one nearest-contour response and clips through the original outline,
including holes and disconnected contours. `baseBrightness` contributes fixed white light and
`environmentGain` controls the colored contribution from the blurred environment. Environment alpha
scales that contribution. The fixed base and environment terms are evaluated in linear extended-sRGB
and emitted with premultiplied alpha. Display headroom signals resource refresh; color strength is
caller-configured.

The experimental `ContourShape` contract can provide the same ordered line, arc and cubic segments
used for clipping and Backdrop geometry. The bundled `CapsuleShape` uses a G2 continuous profile and
is available as a normal Compose `Shape`.

## Shadows

Outer and inner shadows are independent primitives. Their parameter objects retain radius, offset,
color and alpha; configuration factories and `copy` produce configured values, while `None` omits
rendering. The call site owns shape, ordering and source selection. Interaction lighting is opt-in
through its provider registry; see [Interaction lighting](interaction-lighting.md).
