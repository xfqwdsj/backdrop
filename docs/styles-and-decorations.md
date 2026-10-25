# Styles and decorations

`drawBackdrop` accepts one declaration for surface drawing and image composition. Effects, fill,
highlights and shadows work at the root and inside `image` or `layer` blocks. Single-consumer,
associative drawing can share its destination recording; a sampled result or explicit `layer`
retains its own compositing boundary.

## Configure a surface

```kotlin
ProvideBackdropStyle(
    blurRadius = { 8.dp },
    fill = { tint },
    highlight = { Highlight.Ambient },
) {
    Box(Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(24.dp) }) { surface {} })
}
```

`surface { }` selects fixed appearance phases. Its `BackdropStyle` contains delayed blur, fill,
highlight, shadow, inner-shadow, interaction-lighting and inset producers. Style fields provide
appearance producers; image and effect operations belong to the DSL scopes. The fixed order is outer
shadow, blurred and clipped source, fill, additive interior light, children, then highlight and
inner shadow. `backgroundAlpha` fades the background group; foreground decorations and children have
independent opacity. Use the graph DSL when decorations should fade with the background or need
another ordering.

Inside `surface { }`, an unset slot inherits its delayed producer at the modifier's use position. A DSL setter
replaces it; `Highlight.None`, `Shadow.None`, and `InnerShadow.None` omit those decorations.
An `effects { }` block replaces inherited blur with the declared serial chain. Repeated effect
blocks append stages; repeated appearance setters select the last value. A null `insets` argument
inherits the style's inset producer.
A zero-alpha value configures the primitive with transparent output. `BackdropStyle.NoEffects`
specifies every style field, with zero blur, transparent fill, no insets, and all decoration
primitives disabled. Omitted provider fields inherit parent producers;
providing a complete style replaces all fields. Defaults include highlight and outer shadow.

Read optical and decoration state inside the declaration, and drawing state inside a paint callback,
to update rendering without recomposition:

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
        highlight(Highlight.Ambient)
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

`Highlight(width, direction, style, ...)` configures a contour highlight.
`HighlightDirection(angle, mode, strength, widthScale)` uses normal incidence: the normal's dot
product with the chosen direction is truncated for Single or folded by absolute value for Double.
Its width and strength have independent curves. `Uniform` removes directional modulation.
`falloff` shapes the profile across the band; `opacity` controls the primitive's output layer.

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
explicit `colorSource` image; the surface DSL uses its processed source. `incidence(...)` inside the builder
selects the normal-incidence response; import that extension from the highlight package. Both branches use `angle` for orientation: 0 points
right, 90 down, 180 left, 270 up. Angular segments are relative to that orientation.

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
For a uniform response, the orientation is zero. Cuts affect static and interaction contour light.

## Color and geometry

The renderer evaluates one nearest-contour response and clips through the original outline,
including holes and disconnected contours. `Default` uses solid shading, `Ambient` two-tone
shading, and `Plain` uniform direction. Two-tone shading can emit black RGB while retaining emission.

`HighlightColorResponse` samples the processed source in the surface DSL. In a graph, pass an
explicit `colorSource` image to choose that domain and exclude later tints and children. Zero amount
permits omitting that source. Source alpha scales the response, while highlight alpha and
directional response determine emission. `PreservePeak` rescales positive sample RGB in linear
extended-sRGB to the base peak when a finite positive scale exists. Color response changes RGB while
preserving emission. Display headroom signals resource refresh; color strength is caller-configured.

Outer and inner shadows are independent primitives. Their parameter objects retain radius, offset,
color and alpha; configuration factories and `copy` produce configured values, while `None` omits
rendering. The call site owns shape, ordering and source selection. Interaction lighting is opt-in
through its provider registry; see [Interaction lighting](interaction-lighting.md).
