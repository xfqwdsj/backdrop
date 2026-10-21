# Effects and sampling

Effects transform the source in declaration order. A blur followed by a color filter therefore
filters the blurred result. Use `effects { ... }` to process the current result, or capture a reusable
branch with `image { source(); effects { ... } }`. An explicit render declaration replaces
the inherited declaration; `BackdropRenderStyle.NoEffects` draws the unprocessed source.

## Choose a built-in effect

Choose effects by the transformation the component needs. For example, `blur` softens source detail,
`lens` refracts it near a shaped boundary, and color filters adjust the processed result. Compose
these operations in an effects producer, using its density to convert layout distances to pixels.

The [effect declarations](../backdrop/src/commonMain/kotlin/effects) contain argument validation.
Blur and lens distances are finite and non-negative. Lens requires a supported corner-based shape;
arbitrary path outlines are not supported by its signed-distance model. Highlight support for a
custom outline does not imply lens support for that outline.

Check [platform capabilities](platforms-and-hdr.md) when an effect is unavailable. In particular,
progressive blur falls back to uniform blur when runtime shaders are unavailable.

## Keep coverage, sampling, and layout separate

`BackdropInsets` changes the visible effect and callback region per physical side: positive values
extend it, negative values contract it. It leaves layout and foreground shape alone. An effect can
also request visible coverage with `cover(insets)`. Those requests must be non-negative and merge
by the maximum on each side, then add to the caller's insets.

Sampling room is calculated outside that visible region. It supplies neighbors a blur or refraction
reads without widening what is drawn. `BackdropExtension` reports the final recording room in
pixels. For example, two serial effects that each read eight pixels past their input need sixteen
pixels of input support; taking the larger individual radius would under-record the source.

## Align progressive blur and a painted mask

`BackdropRamp` combines anchors, endpoint intensities, and a `BackdropCurve`. An axis starts at the
content side of a surface and grows toward its far edge. `BackdropEdge.Start` and `End` resolve
through layout direction; `BackdropInsets` always uses physical sides.

`BackdropCurve.Linear` and `CubicBezier` share their CPU and shader evaluation. Bezier controls must
be finite, ordered on both axes, and within `0..1`; `asEasing()` exposes the same response to Compose
animations.

`BackdropRampAnchors.Auto` spans the surface. `offsets(plateau, fade)` and `ratio(plateau, fade)` let
the span reach into or past it: the fade extends beyond the content side, and the plateau reaches
toward the far side. Ratio anchors measure against the axis's content thickness. A custom axis can
exclude window insets from that content range.

Use the same ramp for blur and a mask when both should transition over the same span:

```kotlin
val ramp = BackdropRamp(anchors = BackdropRampAnchors.offsets(16.dp, 32.dp))
// In the effects producer:
progressiveBlur(radius = 24.dp.toPx(), edge = BackdropEdge.Top, ramp = ramp)
```

Progressive blur evaluates the curve per pixel in two Gaussian passes. End intensities must be
finite and in `0..1`, and anchors must resolve to a finite increasing span. Its anchors can add
visible coverage. Changing caller insets clips that span without moving its anchors.

`BackdropFill` is a fill description for consumers: brush, optional alpha, and optional ramp.
Its color constructor wraps a specified color in `SolidColor`; unspecified fill or alpha delegates
that choice to the consuming component. Backdrop does not supply a theme-based component that
resolves those choices for you.

Use the color or brush overload of `drawBackdropFill` to paint a resolved fill. Strength multiplies
the fill's alpha and ramp intensity. A painted ramp samples the curve into gradient stops, whereas
progressive blur evaluates it directly. Retain `BackdropFillRampCache` for repeated fill drawing,
or remember the brush from `ramp.brush(...)` instead of allocating its color list each frame.
`clipToGrownShape` matches the extended surface clip: rounded corners keep their radius and custom
shapes resolve against the extended size.

## Custom effects

`effect(RenderEffect, sampling)`, `effect(sampling) { }`, and `runtimeShaderEffect` require a
`BackdropSampling` contract.
It maps an output rectangle to the complete required input rectangle in surface-local pixels,
independent of the expanded layer origin. Declare all reads, including interpolation neighbors.

| Contract | Intended lookup |
| --- | --- |
| `Identity` | Read the same coordinate. |
| `outsets(left, top, right, bottom)` | Read a fixed neighborhood around the output. |
| `translated(dx, dy)` | Read the output shifted by a fixed offset. |
| Custom `requiredInput` | Bound a remapping whose footprint depends on the output region. |

For example, registering a platform effect that reads up to eight pixels on every side:

```kotlin
effects {
    effect(customEffect, sampling = BackdropSampling.outsets(8f, 8f, 8f, 8f))
}
```

The resolver walks from the last stage to the first, propagating each input request into its
predecessor. Recording bounds union the visible output and every intermediate request, including
translated regions that do not overlap the output. Contracts must be deterministic, finite, and
non-inverted. Validation checks rectangle shape, while the effect author must prove it covers
the actual reads.

### Construct an effect from resolved geometry

`effect(sampling) { }` registers a deferred factory. Its `BackdropEffectContext` supplies `size`,
`shape`, `layoutDirection`, an immutable `density` conversion value, the final graph `extension`,
and the active stage's `inputBounds`. Bounds use surface-local pixels; adding the extension's
left/top margins maps them to storage coordinates. The final extension includes other branches'
requirements, while `inputBounds` belongs to this stage's backward sampling request.

Read the context during the factory invocation. It is a read-only view of active build geometry,
not a snapshot to retain. Factories return a platform `RenderEffect`; graph registration belongs
to the surrounding `BackdropEffectScope`. Unsupported render-effect platforms skip the factory.
The factory's shader cache uses stage-local keys: equal keys in different stages identify separate
mutable shaders, while repeated resolutions reuse a stage's cached instance. A source change under
one key replaces that shader; the receiver releases caches when the process node is omitted
or detached. Display refresh invalidates recorded layers and reuses compiled programs. Custom
factories own their complete sampling declaration just like built-ins.

User-defined `BackdropEffectScope` extensions can register platform effects through this factory or
use `runtimeShaderEffect` for a single-input shader. The latter manages shader-stage keys, input
bounds and platform adapters. See [BackdropEffectContext](../backdrop/src/commonMain/kotlin/BackdropEffectContext.kt)
for the factory context and [Render graphs](render-graphs.md#extend-the-declaration-language) for
composition extensions.

The effects producer runs once per resolution. Stage construction and runtime-shader uniform blocks
run after the extension is resolved. Read `extension` inside the uniform block when translating
between layer and surface coordinates. The Skiko backend supplies the complete declared stage input
to neighboring-pixel shaders. See [BackdropSampling](../backdrop/src/commonMain/kotlin/BackdropSampling.kt),
[BackdropEffectScope](../backdrop/src/commonMain/kotlin/BackdropEffectScope.kt), and the
[custom-effect entry points](../backdrop/src/commonMain/kotlin/effects/RenderEffect.kt).

A same-coordinate gain shader can use `Identity` because it reads no neighbors. This fragment goes
inside an effects producer and imports `top.ltfan.backdrop.effects.runtimeShaderEffect`:

```kotlin
runtimeShaderEffect(
    key = "surface-gain",
    shaderString = """
        uniform shader content;
        uniform float gain;
        half4 main(float2 position) {
            half4 sampled = content.eval(position);
            return half4(sampled.rgb * gain, sampled.a);
        }
    """.trimIndent(),
    uniformShaderName = "content",
    sampling = BackdropSampling.Identity,
) {
    setFloatUniform("gain", 1.2f)
}
```

It scales premultiplied RGB while retaining alpha. If you change the shader to offset or spread its
lookups, change the sampling declaration with it; the library cannot infer reads from shader code.
