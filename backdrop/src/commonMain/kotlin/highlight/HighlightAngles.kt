package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import top.ltfan.backdrop.BackdropCurve
import top.ltfan.backdrop.BackdropRenderDsl
import top.ltfan.backdrop.RuntimeShader
import top.ltfan.backdrop.shaderControls

/** Relative angles are measured clockwise from HighlightDirection.angle. */
public enum class HighlightAngleDomain {
    /** The shortest separation, 0..180 degrees, folding the two sides together. */
    Unsigned,
    /** The oriented clockwise turn, 0..360 degrees. */
    Directed,
}

/** Shared endpoint tolerance in degrees, including the circular seam. */
internal const val HighlightAngleTolerance: Float = .001f

internal data class HighlightAngleSegment(
    val start: Float,
    val sweep: Float,
    val channels: Int,
    val from: Float,
    val to: Float,
    val curve: BackdropCurve,
)

/** Ordered, immutable angular responses prepared once for CPU evaluation and shader upload. */
@Immutable
public class HighlightAngleProfile
internal constructor(
    public val domain: HighlightAngleDomain,
    entries: List<HighlightAngleSegment>,
) {
    private val entries = entries.toTypedArray()
    internal val capacity: Int = boundaryUniformCapacity(entries.size)
    private val spans = FloatArray(capacity * 4)
    private val ranges = FloatArray(capacity * 2)
    private val curves = FloatArray(capacity * 4)
    internal val maximumWidthScale: Float =
        this.entries.fold(0f) { maximum, entry ->
            if (entry.channels and 1 != 0) maxOf(maximum, entry.from, entry.to) else maximum
        }

    init {
        for (i in this.entries.indices) {
            val entry = this.entries[i]
            spans[i * 4] = entry.start
            spans[i * 4 + 1] = entry.sweep
            spans[i * 4 + 2] = entry.channels.toFloat()
            spans[i * 4 + 3] = 1f
            ranges[i * 2] = entry.from
            ranges[i * 2 + 1] = entry.to
            val controls = entry.curve.shaderControls()
            for (c in 0..3) curves[i * 4 + c] = controls[c]
        }
    }

    /** Width multiplier at a relative normal angle; undefined regions return zero. */
    public fun widthScaleAt(angle: Float): Float = Float.fromBits((evaluate(angle) shr 32).toInt())

    /** Emission multiplier at a relative normal angle; undefined regions return zero. */
    public fun brightnessAt(angle: Float): Float = Float.fromBits(evaluate(angle).toInt())

    internal fun evaluate(angle: Float): Long {
        require(angle.isFinite()) { "Angle must be finite" }
        val turn = normalizedAngle(angle)
        val relative =
            if (domain == HighlightAngleDomain.Unsigned) minOf(turn, 360f - turn) else turn
        var width = 0f
        var brightness = 0f
        for (i in entries.indices) {
            val entry = entries[i]
            var distance = normalizedAngle(relative - entry.start)
            if (distance >= 360f - HighlightAngleTolerance) distance = 0f
            if (distance > entry.sweep + HighlightAngleTolerance) continue
            val progress = if (entry.sweep == 0f) 0f else (distance / entry.sweep).coerceIn(0f, 1f)
            val value = entry.from + (entry.to - entry.from) * entry.curve.transform(progress)
            if (entry.channels and 1 != 0) width = value
            if (entry.channels and 2 != 0) brightness = value
        }
        return (width.toBits().toLong() shl 32) or (brightness.toBits().toLong() and 0xffffffffL)
    }

    internal fun upload(shader: RuntimeShader) {
        shader.setFloatUniform(
            "angleDomain",
            if (domain == HighlightAngleDomain.Unsigned) 0f else 1f,
        )
        shader.setFloatUniform("angleSpans", spans)
        shader.setFloatUniform("angleRanges", ranges)
        shader.setFloatUniform("angleCurves", curves)
    }
}

/** Builds relative angular intervals. Later declarations replace earlier values in their region. */
@BackdropRenderDsl
public class HighlightAngleScope internal constructor(private val domain: HighlightAngleDomain) {
    private val entries = mutableListOf<HighlightAngleSegment>()

    /**
     * Interpolates a [Highlight] width multiplier from [from] to [to] using [curve] over the
     * interval.
     */
    public fun widthScale(
        start: Float,
        end: Float,
        from: Float,
        to: Float = from,
        curve: BackdropCurve = BackdropCurve.Linear,
    ) {
        add(start, end, 1, from, to, curve)
    }

    /** Interpolates emission from [from] to [to] using [curve]; values above one support HDR. */
    public fun brightness(
        start: Float,
        end: Float,
        from: Float,
        to: Float = from,
        curve: BackdropCurve = BackdropCurve.Linear,
    ) {
        add(start, end, 2, from, to, curve)
    }

    /** Hard-clears both channels. Cross-seam intervals are split explicitly at 0/360 degrees. */
    public fun clear(start: Float, end: Float) {
        add(start, end, 3, 0f, 0f, BackdropCurve.Linear, allowPoint = true)
    }

    public fun clear(ranges: Set<ClosedFloatingPointRange<Float>>) {
        for (range in ranges) clear(range.start, range.endInclusive)
    }

    /** Appends a reflected copy of the preceding declarations around a relative angular axis. */
    public fun mirror(axis: Float = 0f) {
        require(domain == HighlightAngleDomain.Directed) {
            "Unsigned angles are already symmetric; mirror a directed profile explicitly"
        }
        require(axis.isFinite()) { "Mirror axis must be finite" }
        val count = entries.size
        for (i in 0 until count) {
            val entry = entries[i]
            val curve =
                when (val original = entry.curve) {
                    BackdropCurve.Linear -> original
                    is BackdropCurve.CubicBezier ->
                        BackdropCurve.CubicBezier(
                            1f - original.x2,
                            1f - original.y2,
                            1f - original.x1,
                            1f - original.y1,
                        )
                }
            entries +=
                entry.copy(
                    start = normalizedAngle(2f * normalizedAngle(axis) - entry.start - entry.sweep),
                    from = entry.to,
                    to = entry.from,
                    curve = curve,
                )
        }
    }

    private fun add(
        start: Float,
        end: Float,
        channels: Int,
        from: Float,
        to: Float,
        curve: BackdropCurve,
        allowPoint: Boolean = false,
    ) {
        val limit = if (domain == HighlightAngleDomain.Unsigned) 180f else 360f
        require(start.isFinite() && end.isFinite() && start in 0f..limit && end in 0f..limit) {
            "Angular intervals must be finite and within 0..$limit degrees"
        }
        require(if (allowPoint) end >= start else end > start) {
            "Intervals must be ordered; split cross-seam intervals at 0/360 degrees"
        }
        require(from.isFinite() && to.isFinite() && from >= 0f && to >= 0f) {
            "Angular response values must be finite and non-negative"
        }
        entries +=
            HighlightAngleSegment(normalizedAngle(start), end - start, channels, from, to, curve)
    }

    internal fun build(): HighlightAngleProfile = HighlightAngleProfile(domain, entries)
}

/** Prepares an immutable profile that can be shared by rotated instances. */
public fun highlightAngles(
    domain: HighlightAngleDomain = HighlightAngleDomain.Directed,
    block: HighlightAngleScope.() -> Unit,
): HighlightAngleProfile = HighlightAngleScope(domain).apply(block).build()

internal fun angularDirectionShader(capacity: Int): String =
    """
uniform float angleDomain;
uniform float4 angleSpans[$capacity];
uniform float2 angleRanges[$capacity];
uniform float4 angleCurves[$capacity];
float2 angularResponse(float2 normal) {
  float2 relative = float2(dot(directionVector, normal),
      directionVector.x * normal.y - directionVector.y * normal.x);
  float radians = atan(relative.y, relative.x);
  float2 estimate = float2(cos(radians), sin(radians));
  // Correct native atan approximation before testing narrow angular intervals.
  radians += (estimate.x * relative.y - estimate.y * relative.x) / dot(estimate, relative);
  float angle = degrees(radians);
  angle = mod(mod(angle, 360.0) + 360.0, 360.0);
  if (angleDomain < 0.5) angle = min(angle, 360.0 - angle);
  float2 result = float2(0.0);
  for (int i = 0; i < $capacity; ++i) {
    float4 span = angleSpans[i];
    if (span.w < 0.5) continue;
    float distance = mod(mod(angle - span.x, 360.0) + 360.0, 360.0);
    if (distance >= 360.0 - 0.001) distance = 0.0;
    if (distance > span.y + 0.001) continue;
    float progress = span.y == 0.0 ? 0.0 : clamp(distance / span.y, 0.0, 1.0);
    float value = mix(angleRanges[i].x, angleRanges[i].y, backdropCurve(progress, angleCurves[i]));
    if (span.z == 1.0 || span.z == 3.0) result.x = value;
    if (span.z == 2.0 || span.z == 3.0) result.y = value;
  }
  return result;
}
"""
        .trimIndent()

/** Defines relative angular responses, with [angle] as their orientation. */
public fun HighlightScope.angles(
    angle: Float = 90f,
    domain: HighlightAngleDomain = HighlightAngleDomain.Directed,
    block: HighlightAngleScope.() -> Unit,
) {
    direction = HighlightDirection.Angular(highlightAngles(domain, block), angle)
}
