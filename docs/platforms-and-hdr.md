# Platforms and HDR

Platform adapters connect the shared rendering model to native capabilities. Target coverage and
SDK configuration live in [backdrop/build.gradle.kts](../backdrop/build.gradle.kts).
Common code selects effects through `isRenderEffectSupported()` and `isRuntimeShaderSupported()`.

## Capability boundaries

| Rendering path | Render effects | Runtime shaders | Display-driven HDR refresh |
| --- | --- | --- | --- |
| Android below API 31 | Unavailable | Unavailable | Unavailable |
| Android API 31–32 | Available | Unavailable | Unavailable |
| Android API 33 | Available | Available | Unavailable |
| Android API 34 and later | Available | Available | When the display ratio callback is available |
| Skiko targets | Reported available | Reported available | No display callback; headroom stays at 1 |

These are the gates implemented by [Android Platform](../backdrop/src/androidMain/kotlin/Platform.kt)
and [Skiko Platform](../backdrop/src/skikoMain/kotlin/Platform.kt), rather than guarantees that an
output surface can display HDR. Unsupported render effects are omitted. Progressive blur falls
back to uniform blur without runtime shaders; lens is omitted. Finite source boundaries use
transparent exterior pixels without their shader extension. Static highlight emission remains
visible, and interior interaction lighting has an SDR mesh fallback.

## Preserve extended-range color

Backdrop preserves extended-range values in its shader color uniforms, paint colors, supported tint
blend modes, and lighting filters. Highlight color response and the shared interaction field use
linear extended-sRGB arithmetic with premultiplied storage. The input buffers, platform
intermediates, output surface, and window must also support the desired range.

Color matrices deliberately use platform filters. Clamping behavior varies across platforms and
Android builds, including within a single API level, so an SDK-version branch cannot choose a
reliable alternative. Convenience matrix operations such as `colorControls`, `vibrancy`, and
`opacity` inherit that boundary. Tint and lighting use shader paths where available because their
platform filter APIs pack colors into ARGB8; Skia lighting also pins the result. Other blend modes
keep platform semantics. The [color-filter implementation](../backdrop/src/commonMain/kotlin/effects/ColorFilter.kt)
records the supported shader modes.

## Refresh resources when headroom changes

Enable HDR on the Android window in application code, for example with
`window.colorMode = ActivityInfo.COLOR_MODE_HDR`. Then wrap the relevant subtree:

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.time.Duration.Companion.milliseconds
import top.ltfan.backdrop.BackdropHdrScope
import top.ltfan.backdrop.LocalBackdropHdrRefreshInterval

CompositionLocalProvider(LocalBackdropHdrRefreshInterval provides 200.milliseconds) {
    BackdropHdrScope {
        // Recorded sources and receiving surfaces
    }
}
```

The scope listens to the Android display HDR/SDR ratio where available. Retained Compose/HWUI
layers can keep the color space captured at creation; refreshing Backdrop-owned resources lets
subsequent drawing reflect changed headroom without replacing the composition or interaction state.
It does not take ownership of supplied graphics layers, ancestor layers, or third-party effects.

`LocalBackdropHdrHeadroom` publishes the sampled display ratio, with 1 representing no reported
headroom. It is a refresh signal, not a per-layer luminance measurement, a per-window ratio, or an
automatic radiance multiplier or RGB limit. The refresh interval must be finite and non-negative;
it defaults to 200 milliseconds. Continuous changes are throttled and the latest pending value is
delivered. Set the interval at a stable provider boundary: changing this static local recomposes
its subtree.

Other platforms retain their usual behavior and report headroom 1. The unused Skiko interval is
intentional. See [BackdropHdrScope](../backdrop/src/commonMain/kotlin/BackdropHdrScope.kt) and its
[Android lifecycle](../backdrop/src/androidMain/kotlin/BackdropHdrScope.android.kt).

## Validate the right layer

Public APIs do not provide a layer's HDR luminance, and screenshot/readback paths do not prove HDR
display output. The rendering tests use floating-point Skia surfaces to check owned color math;
Compose screenshots check geometry, alpha, and updates through their 8-bit path. Neither replaces
verification on the application's configured HDR window and display.
