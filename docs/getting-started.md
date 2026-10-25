# Getting started

A backdrop has two participants: a source that supplies pixels and a surface that processes them.
Recording the background separately lets several surfaces sample the same content without including
their own foreground in that source.

## Add the dependency

Follow the [README's Getting Started instructions](../README.md#getting-started) for the dependency,
version-catalog entry, and Maven Central repository configuration.

## Record and sample

This example uses only Compose Foundation and UI alongside Backdrop:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.ltfan.backdrop.backdrops.layerBackdrop
import top.ltfan.backdrop.backdrops.rememberLayerBackdrop
import top.ltfan.backdrop.drawBackdrop
import top.ltfan.backdrop.effects.blur
import top.ltfan.backdrop.surface

@Composable
fun BackdropExample() {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize()
                .layerBackdrop(backdrop)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF356DF3), Color(0xFFF19CCB)))
                )
        )
        Box(
            Modifier.align(Alignment.Center).size(160.dp).drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(24.dp) },
            ) {
                surface { effects { blur(12.dp.toPx()) } }
            }
        )
    }
}
```

`layerBackdrop` records the content after that modifier. `drawBackdrop` maps the recorded layer
into the receiver's coordinates, processes it, and draws the receiver's content above it. Put the
background and receiver in separate branches as shown. Give both a real layout size; recording an
empty source provides no pixels to sample.

The effect scope is a `Density`, so convert dp distances with `toPx()` inside `effects { }`.
A `surface { }` declaration inherits [appearance defaults](styles-and-decorations.md); explicit
setters select each slot. Its fixed phases order outer shadow, processed source, fill and interior
lighting before children, then contour highlight and inner shadow. See [Render graphs](render-graphs.md)
for independently sampled images, masks, arbitrary ordering and export.

For a uniform gradient, blur has little visible structure to soften; replace it with textured or
scrolling background content to inspect the sampling. Capability and fallback behavior are covered
in [Platforms and HDR](platforms-and-hdr.md).

## Continue building a component

Use [styles](styles-and-decorations.md) to share a visual configuration across receivers. Use
[sources and surfaces](sources-and-surfaces.md) for transparent boundaries and
exported sources. For fade regions such as scrolling bars, combine a ramp and progressive blur as
described in [effects and sampling](effects-and-sampling.md).
