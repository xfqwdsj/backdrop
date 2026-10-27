# Custom rendering

A primitive produces a graph image. It declares its output bounds and every image it
reads; the receiver resolves sampling, owns the rendering instance and combines its output with
other images. Highlights, shadows, interaction light and fixed-phase surface decorations use this
registration contract.

## Surface decorations

`BackdropDecoration<P>` supplies a stateful drawing inside `surface { }`. Register it with
`drawBehind`, `drawSurface` or `drawForeground`. Its renderer receives the current configuration
on prepare and draw. `BackdropRenderResources` owns any explicitly requested layers, shader keys and
resource pools. Compatible renderer keys retain state through parameter updates; omission or detach
closes the owner. A display refresh calls `refresh` before releasing layers and retains shader keys.

A decoration declares finite, ordered output bounds in surface pixels. Those bounds participate
in placement outsets and clip its drawing. Surface-phase drawings also follow the visible shape
clip. The drawing destination belongs to the containing phase. Algorithms needing an isolated
construction layer can create one from resources and record it with the decoration context's
`recordLayer` helper; layer and brush coordinates remain surface-local.

A decoration overrides `sampling(geometry)` with its complete `BackdropSampling` footprint when
it reads the processed source; the default null value declares a drawing without that input.
`BackdropDecorationContext.inputBounds` reports the resolved footprint.
`BackdropDecorationDrawContext.processedSource` borrows the selected graph image. Surface phases
supply the finite, shape-clipped source after serial optics and before fill. The view can draw or
provide a runtime-shader input during the active decoration invocation. Access after return is rejected. Sampling outside that processed domain uses
transparent pixels. The borrowed context and input remain read-only; callers choose their own effects
and foreground drawing.

Stateful decorations can also join ordinary graph composition through
`decoration(value, processedSource)`. The selected input belongs to the same declaration; callers
choose its processing and clipping. This preserves decoration renderer keys, automatic compositing,
sampling footprints and resource ownership.
With a fixed styled surface, append foreground drawing through `appendForeground { }`;
the additional block starts transparent and composites above the retained foreground.

## Define an isolated image configuration and renderer

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
import top.ltfan.backdrop.BackdropGeometry
import top.ltfan.backdrop.BackdropPrimitiveRenderer
import top.ltfan.backdrop.BackdropRenderResources

@Immutable
data class SolidPanel(val color: Color, val outset: Dp) : BackdropPrimitive<SolidPanel> {
    init {
        require(outset.value.isFinite() && outset.value >= 0f)
    }

    override fun bounds(geometry: BackdropGeometry): Rect =
        geometry.surfaceBounds.inflate(with(geometry.density) { outset.toPx() })

    override fun createRenderer(
        resources: BackdropRenderResources,
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

`BackdropPrimitive.compositingStrategy` defaults to `Offscreen`, which gives destination-dependent
drawing such as `Clear` its own destination. A renderer that only draws ordinary source-over
content can opt into `Auto`. The evaluator can then draw a private, single-consumer result into
its containing recording. Sampling, sharing or explicitly isolating the result retains the required
image boundary. Choose this contract from the renderer's blending semantics.

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
resolution. Inputs are recorded before their consumer and shared dependencies record once per frame.
Unreachable branches do not construct renderers or evaluate their bounds and sampling.

Graph construction ends when resolution begins. Configuration, preparation and drawing callbacks
cannot append graph operations. Captured declaration scopes reject late mutations explicitly.

## Draw declared images

`context.input(index)` returns a borrowed view during drawing. `view.draw(scope)` draws it in
surface coordinates, clipped to that dependency's declared input request. Retaining a view and
using it in another invocation throws. The input graph layer cannot be modified or released through
this view.

`view.runtimeShaderEffect(shader, uniformName, origin)` creates a single-input platform effect
for the declared input and active output domains. `origin` locates the surface origin in the
renderer-owned target layer; use the same translation while recording the input.

For a filtered layer, storage must cover both the output and its input sampling footprint.
The primitive context's `recordLayer` helper uses the complete resolved graph storage, preserving
surface coordinates and brush sizes. Pass `Offset(extension.left, extension.top)` as `origin`,
set shader uniforms in that coordinate space, assign the effect, record `view.draw(this)` through
the helper, then draw the layer.

When managing storage manually, union the required output and input rectangles, round left/top
down and right/bottom up, and place the layer at that integer origin. Use its negation for shader
coordinates. For a decoration, pass that union as the `bounds` argument to `recordLayer`; its
default bounds cover the decoration output only.

Runtime-shader support is required; the caller selects a capability fallback. Several inputs can
be composed into the single input image supported by the portable shader interface. A custom
renderer owns the sampling and intermediate images of any native filter chain it constructs.
Graph operations keep intermediate pixels in native graphics resources.

## Retain resources by branch key

Create `resources.resourcePool<Key, Value> { key -> ... }` once in the renderer. Each key has its
own resource owner and shader namespace. `getOrCreate(key)` reuses the value, while
`retainOnly(activeKeys)` releases entries that leave the active set. Retention creates no entries;
call `getOrCreate` for the values needed by the current configuration. Keys have immutable equality
and hash behavior. Update filters, brushes and other parameter-dependent state on the retained value.

```kotlin
private val branches = resources.resourcePool<Int, GraphicsLayer> { level ->
    createGraphicsLayer()
}

// In prepare:
branches.retainOnly(activeLevels)
branches.getOrCreate(level).renderEffect = effectForLevel

// In draw:
val layer = branches.getOrCreate(level)
context.recordLayer(layer) {
    context.input(0).draw(this)
}
with(scope) { drawLayer(layer) }
```

Import `GraphicsLayer` and `drawLayer` from `androidx.compose.ui.graphics.layer`, and
`recordLayer` from `top.ltfan.backdrop`.
`activeLevels` is a set of keys selected by the effect, and `effectForLevel` is its resolved native
filter. Sampling still declares the filter's full input footprint before recording.

A pool follows the receiving primitive's lifecycle automatically. Display refresh disposes values
and their layers, retaining shader caches for keys still in the pool. The next `getOrCreate`
rebuilds that value. Clearing, key removal and owner release also clear those shader caches.
Values remain borrowed until one of these events; obtain them again during subsequent callbacks.
Renderer `refresh` drops any separately cached value references and geometry-dependent metadata;
the pool object itself stays with the renderer.

Use `dispose = { value -> ... }` for resources owned by the value outside its entry resource
owner. Disposal runs before automatic cleanup. Factories clean up their own partially constructed
external resources when throwing; entry-owned layers, nested pools and shaders are cleaned up by
the engine. Cleanup continues across entries when a disposer throws. Callbacks work with entry
resources and do not reenter the same pool. Pools are unsynchronized rendering state and carry no
Compose stability annotation.

## Record an owned layer

`context.recordLayer(layer) { ... }` records over resolved graph storage in surface coordinates.
It sets layer placement and translates the origin using `context.extension`, while `size` and
`drawContext.size` inside the callback remain the surface size. Brushes therefore retain their
surface sizing even when other branches enlarge storage. Explicitly draw through the extension
when processing those pixels. The helper is inline and applies no effect, alpha or blend policy;
configure those on the layer separately.

Record dependency layers before the layers that draw them, keeping recording callbacks sequential.
Use the helper during primitive drawing. Borrowed input views keep their existing invocation
lifetime, and expired drawing contexts reject recording. For a target with custom storage bounds,
use the raw layer-recording procedure described above. The helper records commands; the effect
still declares its sampling and the resource owner still controls lifetime.

## Own resources through the receiver

`createRenderer` is called for each reachable declaration position in each receiving modifier.
The receiver reuses that instance while the configuration class and `rendererKey` match. The key
has immutable equality and hash behavior; change it for incompatible renderer implementations or
generic type arguments. Keys select compatibility at a position, not relocation across positions.
Read changing parameters from the current `primitive` argument on each callback so the retained
renderer follows configuration updates.

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
Layered progressive blur uses the public resource pool and recording helper for its retained branches.
