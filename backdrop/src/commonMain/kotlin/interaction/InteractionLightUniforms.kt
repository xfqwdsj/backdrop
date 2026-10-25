package top.ltfan.backdrop.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.abs
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.RuntimeShader

/**
 * Reusable source arrays and the local-to-window homography shared by interior and contour shaders.
 */
internal class InteractionLightUniforms(val capacity: Int) {
    val rowX = FloatArray(4)
    val rowY = FloatArray(4)
    val rowW = FloatArray(4)
    val positions = FloatArray(capacity * 4)
    val colors = FloatArray(capacity * 4)
    val parameters = FloatArray(capacity * 4)
    val curves = FloatArray(capacity * 4)
    var count: Int = 0
        private set

    var mayCoverReceiver: Boolean = true
        private set

    fun clear() {
        count = 0
        mayCoverReceiver = false
    }

    fun prepare(
        entries: List<InteractionLightEntry>,
        coordinates: LayoutCoordinates,
        size: Size,
    ): Boolean {
        count = 0
        val width = size.width
        val height = size.height
        if (!(width > 0f && height > 0f)) return false
        val a = coordinates.localToWindow(Offset.Zero)
        val b = coordinates.localToWindow(Offset(width, 0f))
        val d = coordinates.localToWindow(Offset(0f, height))
        val e = coordinates.localToWindow(Offset(width, height))
        if (
            !a.x.isFinite() ||
                !a.y.isFinite() ||
                !b.x.isFinite() ||
                !b.y.isFinite() ||
                !d.x.isFinite() ||
                !d.y.isFinite() ||
                !e.x.isFinite() ||
                !e.y.isFinite()
        )
            return false
        val dx1 = b.x - e.x
        val dx2 = d.x - e.x
        val dx3 = a.x - b.x - d.x + e.x
        val dy1 = b.y - e.y
        val dy2 = d.y - e.y
        val dy3 = a.y - b.y - d.y + e.y
        val denominator = dx1 * dy2 - dx2 * dy1
        if (!denominator.isFinite() || abs(denominator) <= 1e-8f) return false
        val g = (dx3 * dy2 - dx2 * dy3) / denominator
        val k = (dx1 * dy3 - dx3 * dy1) / denominator
        rowX[0] = (b.x - a.x + g * b.x) / width
        rowX[1] = (d.x - a.x + k * d.x) / height
        rowX[2] = a.x
        rowY[0] = (b.y - a.y + g * b.y) / width
        rowY[1] = (d.y - a.y + k * d.y) / height
        rowY[2] = a.y
        rowW[0] = g / width
        rowW[1] = k / height
        rowW[2] = 1f
        rowX[3] = 0f
        rowY[3] = 0f
        rowW[3] = 0f
        if (
            !rowX[0].isFinite() ||
                !rowX[1].isFinite() ||
                !rowX[2].isFinite() ||
                !rowY[0].isFinite() ||
                !rowY[1].isFinite() ||
                !rowY[2].isFinite() ||
                !rowW[0].isFinite() ||
                !rowW[1].isFinite() ||
                !rowW[2].isFinite()
        )
            return false
        require(entries.count(InteractionLightEntry::isFiniteForRendering) <= capacity) {
            "Interaction shader capacity must cover every finite source"
        }
        rowX[3] = 0f
        rowY[3] = 0f
        rowW[3] = 0f
        // Affine maps attain their coordinate extrema at the receiver corners. Perspective
        // receivers retain the general field path because an interior pole can exceed them.
        val minX = minOf(a.x, b.x, d.x, e.x)
        val maxX = maxOf(a.x, b.x, d.x, e.x)
        val minY = minOf(a.y, b.y, d.y, e.y)
        val maxY = maxOf(a.y, b.y, d.y, e.y)
        val scale = maxOf(1f, abs(minX), abs(maxX), abs(minY), abs(maxY))
        mayCoverReceiver = g != 0f || k != 0f
        positions.fill(0f)
        colors.fill(0f)
        parameters.fill(0f)
        curves.fill(0f)
        count = 0
        for (entry in entries) {
            if (!entry.isFiniteForRendering()) continue
            val offset = count++ * 4
            positions[offset] = entry.x
            positions[offset + 1] = entry.y
            val linear = entry.linearColor
            colors[offset] = linear.red
            colors[offset + 1] = linear.green
            colors[offset + 2] = linear.blue
            colors[offset + 3] = linear.alpha
            parameters[offset] = entry.radiusPx
            parameters[offset + 1] = entry.intensity
            if (!mayCoverReceiver && entry.radiusPx > 0f) {
                // Include rounding in the uploaded homography and shader coordinate arithmetic.
                val guard = maxOf(scale, abs(entry.x), abs(entry.y), abs(entry.radiusPx)) * 0.00001f
                mayCoverReceiver =
                    entry.x + entry.radiusPx + guard >= minX &&
                        entry.x - entry.radiusPx - guard <= maxX &&
                        entry.y + entry.radiusPx + guard >= minY &&
                        entry.y - entry.radiusPx - guard <= maxY
            }
            when (val curve = entry.falloff) {
                BackdropCurve.Linear -> {
                    curves[offset] = 0f
                    curves[offset + 1] = 0f
                    curves[offset + 2] = 1f
                    curves[offset + 3] = 1f
                }
                is BackdropCurve.CubicBezier -> {
                    curves[offset] = curve.x1
                    curves[offset + 1] = curve.y1
                    curves[offset + 2] = curve.x2
                    curves[offset + 3] = curve.y2
                }
            }
        }
        return true
    }

    fun upload(shader: RuntimeShader) {
        shader.setFloatUniform("interactionRowX", rowX)
        shader.setFloatUniform("interactionRowY", rowY)
        shader.setFloatUniform("interactionRowW", rowW)
        shader.setFloatUniform("interactionPositions", positions)
        shader.setFloatUniform("interactionColors", colors)
        shader.setFloatUniform("interactionParameters", parameters)
        shader.setFloatUniform("interactionCurves", curves)
        shader.setIntUniform("interactionLightCount", count)
    }
}
