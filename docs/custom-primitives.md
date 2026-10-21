# Custom primitives

A primitive produces an isolated graph image. It declares its output bounds and every image it
reads; the receiver resolves sampling, owns the rendering instance and combines its output with
other images. Built-in highlights, shadows and interaction light use this registration contract.

## Define a configuration and renderer

The configuration type implements `BackdropPrimitive<YourType>`. Its renderer receives the current
configuration on preparation and drawing, so parameter updates can reuse cached state.

```kotlin
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import top.ltfan.backdrop.BackdropPrimitive
import top.ltfan.backdrop.BackdropPrimitiveDrawContext
import top.ltfan.backdrop.BackdropPrimitiveGeometry
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropPrimitiveResources

@Immutable
data class SolidPanel(val color: Color, val outset: Dp) : BackdropPrimitive<SolidPanel> {
    init {
        require(outset.value.isFinite() && outset.value >= 0f)
    }

    override fun bounds(geometry: BackdropPrimitiveGeometry): Rect =
        geometry.surfaceBounds.inflate(with(geometry.density) { outset.toPx() })

    override fun createRenderer(
        resources: BackdropPrimitiveResources,
    ): BackdropPrimitiveRenderer<SolidPanel> = Renderer()

    private class Renderer : BackdropPrimitiveRenderer<SolidPanel> {
        override fun draw(
            scope: DrawScope,
            primitive: SolidPanel,
            context: BackdropPrimitiveDrawContext,
        ) {
            val bounds = context.outputBounds
            scope.drawRect(primitive.color, bounds.topLeft, bounds.size)
        }

        override fun refresh() {}
    }
}
```

Register it inside a render declaration:

```kotlin
primitive(SolidPanel(Color.Red, 6.dp), alpha = .5f)
```

Import `primitive` from `top.ltfan.backdrop`. It appends the primitive with external alpha and blend
applied once. Capture it inside `image { }` for reuse, or use `group { }` to start a transparent
composite. Masks, transforms, foreground selection and exports operate on its result normally.
Internal Clear drawing erases only that primitive's isolated output.

## Declare bounds and dependencies

`bounds` returns conservative finite surface-local pixel bounds. Pixels outside are transparent;
the engine clips drawing to the requested portion. An empty rectangle produces no renderer.
`surfaceBounds` includes caller insets and visible coverage declared by effects. `size` and drawing
coordinates describe the receiving surface; sampling padding does not enlarge brush sizing.

Each dependency is a `BackdropPrimitiveInput(image, sampling)`. Inputs must reference preceding
images in the same declaration. Registration snapshots the input list and rejects foreign images.
Sampling runs backwards from the requested output intersected with the primitive's output bounds.
Each input declares its own complete footprint, including interpolation neighbors. The engine
unions all intermediate requests to allocate storage; storage padding does not expand output bounds.
Bounds and sampling must be deterministic within a resolution, finite and non-inverted.

```kotlin
val material = image {
    source()
    effects { blur(8.dp.toPx()) }
    clipSurface()
}
primitive(myPrimitive, inputs = listOf(BackdropPrimitiveInput(material)))
```

`prepare` receives final graph `extension`, the declared `outputBounds`, the active `requestedBounds`
and an ordered list of per-input `inputBounds`. It runs under snapshot observation after sampling
resolution. Inputs are recorded before their consumer and shared dependencies record once per draw.
Unreachable branches do not construct renderers or evaluate their bounds and sampling.

Graph construction ends when resolution begins. Configuration, preparation and drawing callbacks
cannot append graph operations. Captured declaration scopes reject late mutations explicitly.

## Draw declared images

`context.input(index)` returns a borrowed view during drawing. `view.draw(scope)` draws it in
surface coordinates, clipped to that dependency's declared input request. Retaining a view and
using it in another invocation throws. The input graph layer cannot be modified or released through
this view.

`view.runtimeShaderEffect(shader, uniformName, origin)` creates a single-input platform effect with
the declared sampling bounds. `origin` locates the surface origin in the renderer-owned target
layer; use the same translation while recording the input. Pixel-align a target covering
`outputBounds` by flooring its left/top and ceiling its right/bottom. Place the layer at that integer
left/top and use their negation as `origin`; the aligned width/height determine the recording size.
Set shader uniforms, record `view.draw(this)` into the owned layer with that translation, then draw
the layer. Runtime-shader support is required; the caller selects a capability fallback. Several
inputs can be drawn and composed, but arbitrary independent shader-child bindings are not supplied.
No graph operation reads rendered pixels back to the CPU.

## Own resources through the receiver

`createRenderer` is called for each reachable declaration position in each receiving modifier.
The receiver reuses that instance while the configuration class and `rendererKey` match. The key
has immutable equality and hash behavior; change it for incompatible renderer implementations or
generic type arguments. Keys select compatibility at a position, not relocation across positions.
Use the current `primitive` argument for changing parameters rather than capturing the initial
configuration in the renderer factory.

`resources.createGraphicsLayer()` creates a layer owned by that instance. The renderer can record
and configure it, and can release it early through `releaseGraphicsLayer`. Releasing a foreign or
already released layer throws. The engine frees outstanding owned layers automatically. Shader
keys are local to the instance; repeated keys reuse programs, and changed shader source replaces
the program. Mutable renderers, resource owners and contexts have no stability annotation.

| Event | Renderer and resources |
| --- | --- |
| Parameters or geometry change | `prepare` receives updated configuration and resolved geometry. |
| Display refresh | `refresh` drops owned-layer references; the engine frees layers and preserves shader-cache entries, then calls `prepare` before drawing. |
| Omission, incompatible class/key, detach | `release` runs once, followed by outstanding layer and shader-cache cleanup. |
| Construction or preparation failure | The error propagates and resources owned by that instance are released; a failed resolution has no renderable result. |

`refresh` is required even for a stateless renderer. It separates transient layer references from
reusable shader and CPU caches. `release` handles additional resources the renderer owns outside
the supplied resource owner. The shared interaction provider retains input and window ownership;
its built-in primitive adapters consume that domain's frame data through the same graph scheduler.
