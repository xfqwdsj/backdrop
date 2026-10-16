# Styles and decorations

A `BackdropStyle` supplies delayed visual defaults to receiving surfaces. Receivers resolve it at
their position in the UI tree, so the same modifier value can inherit different defaults when used
under different providers.

## Share defaults and override deliberately

```kotlin
ProvideBackdropStyle(
    effects = { blur(8.dp.toPx()) },
    highlight = { Highlight.Ambient },
) {
    Box(Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(24.dp) }))
}
```

Omitted provider fields retain the parent's producers. `ProvideBackdropStyle(style = myStyle)`
provides a complete style; use `LocalBackdropStyle.current.copy(...)` when constructing a style
that retains selected parent fields.

| Value at a modifier | Meaning |
| --- | --- |
| `null` producer | Inherit that field from the style. |
| Explicit producer | Replace that field, including the complete effects chain. |
| `{ Highlight.None }`, `{ Shadow.None }`, `{ InnerShadow.None }` | Disable that decoration. |
| `BackdropStyle.NoEffects` | Use an empty effects chain. |

[BackdropStyle.Default](../backdrop/src/commonMain/kotlin/BackdropStyle.kt) defines the root
appearance. A style governs visual defaults; the call site owns the source and component structure.
The plain modifier inherits only effects and insets.

## Animate through producers

Read snapshot state inside the producer to update drawing without recomposing the component:

```kotlin
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(24.dp) },
    highlight = { if (enabled) Highlight.Default else Highlight.None },
    innerShadow = { InnerShadow(alpha = animatedAlpha.value) },
)
```

Here `enabled` is snapshot-backed state and `animatedAlpha` is an animation state holder. Reading
its value before constructing the producer would instead subscribe the composition to that value.
Disabled decorations retain observation so they can become active again, while releasing drawing
layers. A zero-alpha `Config` remains a configuration; use `None` to express disabling.

## Shape a highlight

`Highlight(...)` creates a `Highlight.Config`. `Default` uses a solid style with two-sided direction;
`Ambient` selects two-tone shading; `Plain` removes directional modulation. The renderer shades the
nearest contour and clips through the original outline, including holes and disconnected contours.

Tune the controls according to their role:

- `width` sets the base band width. `direction.widthScale` changes coverage from side-facing to
  aligned incidence; its default range is 60% to 100% of the base width.
- `direction.strength` independently changes emission from side-facing to aligned incidence.
  Both response ranges have their own curves and may use endpoints above one.
- `falloff` controls the profile across the band. Each pixel uses its center sample and the shape
  clip supplies the antialiased contour edge.
- `opacity` controls the shared output layer. Color alpha and directional response contribute to
  emission strength before that layer is composited.
- `exclusions` masks outward-normal directions. Named sides and angular exclusions use boundary
  orientation rather than the pixel's position within the component.

Direction angles use 0 right, 90 down, 180 left, and 270 up. A two-tone style emits black RGB on
its negative side while retaining emission strength. `Highlight.Config` snapshots the supplied
exclusion set and provides value equality, `copy`, and component accessors.

## Let the backdrop tint the contour

`HighlightColorResponse` samples the already processed backdrop, including `onDrawBackdrop`
drawing. Surface and front callbacks, text, and other foreground content are excluded. Its amount
defaults to 0.5; zero disables backdrop sampling. Sample alpha scales that amount, while the
highlight's own alpha and direction still determine emission strength.

The default `PreservePeak` transform scales the sampled positive RGB peak to the base highlight
peak in linear extended-sRGB. If no finite positive scale is available, it retains the base color.
The response changes RGB only; it does not multiply the result by display headroom.

```kotlin
Highlight(
    style = HighlightStyle.Solid,
    colorResponse = HighlightColorResponse(amount = 0.5f),
)
```

## Extend shading

Implement `HighlightStyle` with matching CPU `shade(HighlightSample)` and AGSL `shaderSource`.
The shader declares `shadeHighlight(...)` and `highlightStrength(...)`; return straight-alpha color
and separate non-negative emission strength. Use `configureShader` for uniforms. The renderer then
applies direction, width, falloff, exclusions, and premultiplication.

A custom `HighlightColorTransform` likewise provides matching CPU and AGSL color transforms.
Both interfaces are `@Stable`: back mutable public properties with Compose snapshot state.
Prefix custom uniforms and helpers so they stay separate from renderer-owned shader declarations.
The extension signatures are specified in
[HighlightStyle](../backdrop/src/commonMain/kotlin/highlight/HighlightStyle.kt) and
[HighlightColorResponse](../backdrop/src/commonMain/kotlin/highlight/HighlightColorResponse.kt).
Static highlight emission remains available without runtime shaders; processed-backdrop tinting
requires the shader path.

## Add shadows

`Shadow(...)` and `InnerShadow(...)` create their respective `Config` values with radius, offset,
color, `alpha`, and blend mode. Their opacity parameter is named `alpha`; the highlight layer's
parameter is `opacity`. `InnerShadow.lerp` interpolates two configurations and switches blend mode
at the midpoint. Outer shadow support expands compositing bounds without changing layout size.
