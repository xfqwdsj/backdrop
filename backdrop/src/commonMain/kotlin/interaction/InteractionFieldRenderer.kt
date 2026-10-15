package top.ltfan.backdrop.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.RuntimeShaderCacheImpl
import top.ltfan.backdrop.asComposeShader
import top.ltfan.backdrop.highlight.encodeHighlightExtendedSrgb
import top.ltfan.backdrop.isRuntimeShaderSupported

/** Reuses shader programs and uniform storage while shared source positions move. */
internal class InteractionFieldRenderer {
    private val programs = RuntimeShaderCacheImpl()
    private val paint = Paint().apply { blendMode = BlendMode.Plus }
    private var capacity = 0
    private var shaderKey = "interactionField:0"
    private val uniformPackets = mutableMapOf<Int, InteractionLightUniforms>()
    private var softwareMesh: SoftwareMesh? = null
    private var softwareSources = FloatArray(0)
    private var softwareCurves = arrayOfNulls<BackdropCurve>(0)
    private var softwareSourceCount = 0
    private val softwarePaint =
        Paint().apply {
            color = Color.White
            blendMode = BlendMode.Plus
        }

    fun release() {
        programs.clear()
        paint.shader = null
        softwarePaint.shader = null
        softwareMesh = null
        softwareSources = FloatArray(0)
        softwareCurves = arrayOfNulls(0)
        softwareSourceCount = 0
        uniformPackets.clear()
        capacity = 0
    }

    fun draw(
        scope: DrawScope,
        coordinates: LayoutCoordinates,
        entries: List<InteractionLightEntry>,
        opacity: Float,
    ) {
        if (opacity <= 0f || scope.size.minDimension <= 0f) return
        ensureCapacity(maxOf(4, entries.size))
        val uniforms = uniformPackets.getOrPut(capacity) { InteractionLightUniforms(capacity) }
        if (!uniforms.prepare(entries, coordinates, scope.size)) return
        if (!isRuntimeShaderSupported()) {
            if (entries.isEmpty()) return
            drawSoftwareMesh(scope, entries, opacity, uniforms)
            return
        }
        val shader =
            programs.obtainRuntimeShader(shaderKey) {
                interactionInteriorShader(capacity)
            }
        uniforms.upload(shader)
        shader.setFloatUniform("opacity", opacity)
        paint.shader = shader.asComposeShader()
        if (entries.isEmpty()) return
        scope.drawContext.canvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
    }

    private fun drawSoftwareMesh(
        scope: DrawScope,
        entries: List<InteractionLightEntry>,
        opacity: Float,
        uniforms: InteractionLightUniforms,
    ) {
        val width = ceil(scope.size.width).toInt()
        val height = ceil(scope.size.height).toInt()
        val mesh =
            softwareMesh?.takeIf { it.width == width && it.height == height }
                ?: createSoftwareMesh(width, height).also { softwareMesh = it }
        ensureSoftwareSources(entries.size)
        softwareSourceCount = 0
        entries.forEach { entry ->
            if (!entry.isFiniteForRendering()) return@forEach
            val offset = softwareSourceCount++ * 8
            val color = entry.linearColor
            softwareSources[offset] = entry.x
            softwareSources[offset + 1] = entry.y
            softwareSources[offset + 2] = entry.radiusPx
            softwareSources[offset + 3] = entry.intensity
            softwareSources[offset + 4] = color.red
            softwareSources[offset + 5] = color.green
            softwareSources[offset + 6] = color.blue
            softwareSources[offset + 7] = color.alpha
            softwareCurves[softwareSourceCount - 1] = entry.falloff
        }
        for (index in mesh.positions.indices) {
            val local = mesh.positions[index]
            val denominator =
                uniforms.rowW[0] * local.x + uniforms.rowW[1] * local.y + uniforms.rowW[2]
            var totalWeight = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            if (denominator.isFinite() && abs(denominator) >= 1e-8f) {
                val worldX =
                    (uniforms.rowX[0] * local.x + uniforms.rowX[1] * local.y + uniforms.rowX[2]) /
                        denominator
                val worldY =
                    (uniforms.rowY[0] * local.x + uniforms.rowY[1] * local.y + uniforms.rowY[2]) /
                        denominator
                for (sourceIndex in 0 until softwareSourceCount) {
                    val offset = sourceIndex * 8
                    val radius = softwareSources[offset + 2]
                    if (radius <= 0f) continue
                    val dx = worldX - softwareSources[offset]
                    val dy = worldY - softwareSources[offset + 1]
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance >= radius) continue
                    val curve = softwareCurves[sourceIndex] ?: continue
                    val weight =
                        interactionLightWeight(
                            distance,
                            radius,
                            softwareSources[offset + 7],
                            softwareSources[offset + 3],
                            curve,
                        )
                    if (weight <= 0f) continue
                    totalWeight += weight
                    red += softwareSources[offset + 4] * weight
                    green += softwareSources[offset + 5] * weight
                    blue += softwareSources[offset + 6] * weight
                }
            }
            val alpha = opacity * interactionFieldResponse(totalWeight)
            val color =
                if (alpha > 0f) {
                    Color(
                        encodeHighlightExtendedSrgb(red / totalWeight),
                        encodeHighlightExtendedSrgb(green / totalWeight),
                        encodeHighlightExtendedSrgb(blue / totalWeight),
                        alpha,
                        ColorSpaces.ExtendedSrgb,
                    )
                } else Color.Transparent
            mesh.vertices.colors[index] = color.toArgb()
        }
        scope.drawContext.canvas.drawVertices(mesh.vertices, BlendMode.Modulate, softwarePaint)
    }

    private fun ensureSoftwareSources(count: Int) {
        if (count <= softwareCurves.size) return
        var capacity = maxOf(4, softwareCurves.size)
        while (capacity < count) capacity *= 2
        softwareSources = FloatArray(capacity * 8)
        softwareCurves = arrayOfNulls(capacity)
    }

    private fun createSoftwareMesh(width: Int, height: Int): SoftwareMesh {
        var step = 4
        var columns: Int
        var rows: Int
        do {
            columns = ceil(width / step.toFloat()).toInt() + 1
            rows = ceil(height / step.toFloat()).toInt() + 1
            if (columns.toLong() * rows <= 65535L) break
            step *= 2
        } while (true)
        val positions =
            List(columns * rows) { index ->
                Offset(
                    minOf(width.toFloat(), (index % columns * step).toFloat()),
                    minOf(height.toFloat(), (index / columns * step).toFloat()),
                )
            }
        val indices = ArrayList<Int>((columns - 1) * (rows - 1) * 6)
        for (y in 0 until rows - 1) for (x in 0 until columns - 1) {
            val topLeft = y * columns + x
            val topRight = topLeft + 1
            val bottomLeft = topLeft + columns
            val bottomRight = bottomLeft + 1
            indices.add(topLeft)
            indices.add(topRight)
            indices.add(bottomLeft)
            indices.add(topRight)
            indices.add(bottomRight)
            indices.add(bottomLeft)
        }
        val vertices =
            Vertices(
                VertexMode.Triangles,
                positions,
                positions,
                List(positions.size) { Color.Transparent },
                indices,
            )
        return SoftwareMesh(width, height, positions, vertices)
    }

    private data class SoftwareMesh(
        val width: Int,
        val height: Int,
        val positions: List<Offset>,
        val vertices: Vertices,
    )

    private fun ensureCapacity(count: Int) {
        if (count <= capacity) return
        var next = maxOf(4, capacity)
        while (next < count) next *= 2
        capacity = next
        shaderKey = "interactionField:$next"
    }
}

internal fun interactionInteriorShader(capacity: Int): String =
    """
${interactionFieldShader(capacity)}
uniform float opacity;
half4 main(float2 coord) {
  float4 field = interactionField(coord);
  float alpha = opacity * field.a;
  // The field is linear; convert it to the target working color space before output.
  return half4(fromLinearSrgb(field.rgb) * alpha, alpha);
}
"""
        .trimIndent()
