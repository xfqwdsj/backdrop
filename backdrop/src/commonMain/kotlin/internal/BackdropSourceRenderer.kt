package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import top.ltfan.backdrop.BackdropDrawScope
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.isRuntimeShaderSupported

/** Resources belong to one drawing consumer, rather than to a shared source. */
internal class BackdropSourceRenderer(private val context: () -> GraphicsContext) {
    private class Slot(val layer: GraphicsLayer, val shader: RuntimeShader)

    private val slots = mutableListOf<Slot>()
    private var used = 0

    fun beginFrame() {
        used = 0
    }

    fun endFrame() {
        while (slots.size > used) {
            context().releaseGraphicsLayer(slots.removeAt(slots.lastIndex).layer)
        }
    }

    fun release() {
        slots.forEach { context().releaseGraphicsLayer(it.layer) }
        slots.clear()
        used = 0
    }

    fun draw(
        scope: DrawScope,
        size: Size,
        samplingBounds: Rect,
        block: BackdropDrawScope.() -> Unit,
    ) {
        Scope(scope, size, samplingBounds).block()
    }

    private inner class Scope(
        private val target: DrawScope,
        override val size: Size,
        override val samplingBounds: Rect,
    ) : BackdropDrawScope, DrawScope by target {
        override fun withTransform(transform: Matrix, draw: BackdropDrawScope.() -> Unit) {
            val bounds = inverseSourceTransform(transform).map(samplingBounds)
            val surfaceSize = size
            target.withTransform({ transform(transform) }) {
                Scope(this, surfaceSize, bounds).draw()
            }
        }

        override fun drawSource(
            bounds: Rect,
            tileMode: TileMode,
            transform: Matrix,
            draw: DrawScope.() -> Unit,
        ) {
            validateBounds(bounds)
            val inverse = inverseSourceTransform(transform)
            val request = inverse.map(samplingBounds)
            require(
                listOf(
                        request.left,
                        request.top,
                        request.right,
                        request.bottom,
                        request.width,
                        request.height,
                    )
                    .all { it.isFinite() }
            ) {
                "Source sampling request must be finite"
            }
            require(
                tileMode == TileMode.Clamp ||
                    tileMode == TileMode.Decal ||
                    tileMode == TileMode.Repeated ||
                    tileMode == TileMode.Mirror
            ) {
                "Unsupported source tile mode: $tileMode"
            }
            val footprint =
                Rect(
                    request.left - 0.5f,
                    request.top - 0.5f,
                    request.right + 0.5f,
                    request.bottom + 0.5f,
                )
            if (
                tileMode == TileMode.Decal ||
                    bounds.contains(footprint) ||
                    !isRuntimeShaderSupported()
            ) {
                target.withTransform({ transform(transform) }) {
                    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                        sourceContent(bounds.size, draw)
                    }
                }
                return
            }

            // Include the texels used when the sampled layer is transformed back to surface space.
            val output =
                Rect(
                    floor(footprint.left),
                    floor(footprint.top),
                    ceil(footprint.right),
                    ceil(footprint.bottom),
                )
            val input = if (tileMode == TileMode.Clamp) clampedInput(output, bounds) else bounds
            // Keep sampled texels and output coordinates in one filter space. Skia's child
            // filter coordinates depend on its retained input domain.
            val recorded =
                Rect(
                    minOf(output.left, input.left),
                    minOf(output.top, input.top),
                    maxOf(output.right, input.right),
                    maxOf(output.bottom, input.bottom),
                )
            require(
                recorded.width.isFinite() &&
                    recorded.height.isFinite() &&
                    recorded.width < Int.MAX_VALUE.toFloat() &&
                    recorded.height < Int.MAX_VALUE.toFloat()
            ) {
                "Source sampling layer dimensions must fit in positive integers"
            }
            val width = recorded.width.toInt()
            val height = recorded.height.toInt()
            if (width <= 0 || height <= 0) return
            val slot = obtainSlot()
            val shader = slot.shader
            shader.setFloatUniform(
                "sourceBounds",
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
            )
            shader.setFloatUniform("origin", recorded.left, recorded.top)
            shader.setIntUniform(
                "tileMode",
                when (tileMode) {
                    TileMode.Clamp -> 0
                    TileMode.Repeated -> 1
                    TileMode.Mirror -> 2
                    else -> error("Unsupported source tile mode: $tileMode")
                },
            )
            slot.layer.renderEffect =
                RuntimeShaderEffect(
                    shader,
                    "content",
                    Rect(0f, 0f, recorded.width, recorded.height),
                )
            slot.layer.record(target, target.layoutDirection, IntSize(width, height)) {
                translate(-recorded.left, -recorded.top) {
                    clipRect(input.left, input.top, input.right, input.bottom) {
                        sourceContent(bounds.size, draw)
                    }
                }
            }
            slot.layer.topLeft = IntOffset(recorded.left.toInt(), recorded.top.toInt())
            target.withTransform({ transform(transform) }) {
                clipRect(output.left, output.top, output.right, output.bottom) {
                    drawLayer(slot.layer)
                }
            }
        }
    }

    private fun obtainSlot(): Slot {
        if (used == slots.size) {
            slots +=
                Slot(
                    context().createGraphicsLayer().apply {
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
                    RuntimeShader(SourceBoundaryShader),
                )
        }
        return slots[used++]
    }
}

private fun Rect.contains(other: Rect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

private fun validateBounds(bounds: Rect) {
    require(
        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all {
            it.isFinite() && floor(it) == it
        } &&
            bounds.width.isFinite() &&
            bounds.height.isFinite() &&
            bounds.width > 0f &&
            bounds.height > 0f
    ) {
        "Source bounds must be a finite, non-empty, pixel-aligned rectangle: $bounds"
    }
}

private fun clampedInput(output: Rect, source: Rect): Rect =
    Rect(
        (output.left + 0.5f).coerceIn(source.left + 0.5f, source.right - 0.5f) - 0.5f,
        (output.top + 0.5f).coerceIn(source.top + 0.5f, source.bottom - 0.5f) - 0.5f,
        (output.right - 0.5f).coerceIn(source.left + 0.5f, source.right - 0.5f) + 0.5f,
        (output.bottom - 0.5f).coerceIn(source.top + 0.5f, source.bottom - 0.5f) + 0.5f,
    )

internal const val SourceBoundaryShader: String =
    """
uniform shader content;
uniform float4 sourceBounds;
uniform float2 origin;
uniform int tileMode;

float2 mapTexel(float2 p) {
    float2 first = sourceBounds.xy + 0.5;
    float2 last = sourceBounds.zw - 0.5;
    if (tileMode == 0) return clamp(p, first, last);
    float2 extent = sourceBounds.zw - sourceBounds.xy;
    float2 index = p - first;
    if (tileMode == 1) return first + mod(mod(index, extent) + extent, extent);
    float2 period = 2.0 * extent;
    float2 phase = mod(mod(index, period) + period, period);
    return first + min(phase, period - 1.0 - phase);
}

float4 texel(float2 p) {
    return content.eval(mapTexel(p) - origin);
}

float4 main(float2 coord) {
    float2 p = coord + origin;
    if (tileMode == 0) {
        return content.eval(clamp(p, sourceBounds.xy + 0.5, sourceBounds.zw - 0.5) - origin);
    }
    float2 base = floor(p - 0.5) + 0.5;
    float2 fraction = p - base;
    return mix(mix(texel(base), texel(base + float2(1.0, 0.0)), fraction.x),
               mix(texel(base + float2(0.0, 1.0)), texel(base + float2(1.0, 1.0)), fraction.x),
               fraction.y);
}
"""

private fun inverseSourceTransform(transform: Matrix): Matrix {
    require(transform.values.size == 16 && transform.values.all { it.isFinite() }) {
        "Source transform must be finite"
    }
    require(
        transform[0, 2] == 0f &&
            transform[1, 2] == 0f &&
            transform[2, 0] == 0f &&
            transform[2, 1] == 0f &&
            transform[2, 2] == 1f &&
            transform[2, 3] == 0f &&
            transform[3, 2] == 0f &&
            transform[0, 3] == 0f &&
            transform[1, 3] == 0f &&
            transform[3, 3] == 1f &&
            transform[0, 0] * transform[1, 1] - transform[0, 1] * transform[1, 0] != 0f
    ) {
        "Source transform must be an invertible two-dimensional affine transform"
    }
    return Matrix(transform.values.copyOf()).apply {
        invert()
        require(values.all { it.isFinite() }) { "Inverse source transform must be finite" }
    }
}

private fun DrawScope.sourceContent(sourceSize: Size, draw: DrawScope.() -> Unit) {
    val scope =
        object : DrawScope by this {
            override val size: Size = sourceSize
        }
    scope.draw()
}
