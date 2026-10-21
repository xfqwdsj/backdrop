package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import top.ltfan.backdrop.BackdropCurve

/** Directional emission measured against each boundary's outward normal. */
@Immutable
public sealed interface HighlightDirection {
    @Immutable public data object Uniform : HighlightDirection

    @Immutable
    public data class Incidence(
        public val angle: Float = 90f,
        public val mode: Mode = Mode.Double,
        public val strength: HighlightResponse = HighlightResponse(.35f, 1f, DefaultDirectionCurve),
        public val widthScale: HighlightResponse =
            HighlightResponse(.6f, 1f, DefaultDirectionCurve),
    ) : HighlightDirection {
        init {
            require(angle.isFinite()) { "angle must be finite" }
        }
    }

    /** Relative angle curves rotated by the direction angle. */
    @Immutable
    public data class Angular(
        public val profile: HighlightAngleProfile,
        public val angle: Float = 90f,
    ) : HighlightDirection {
        init {
            require(angle.isFinite()) { "angle must be finite" }
        }
    }

    @Immutable
    public enum class Mode {
        Single,
        Double,
    }
}

private val DefaultDirectionCurve = BackdropCurve.CubicBezier(.6f, 0f, 1f, .2f)

@Suppress("FunctionName")
public fun HighlightDirection(
    angle: Float = 90f,
    mode: HighlightDirection.Mode = HighlightDirection.Mode.Double,
    strength: HighlightResponse = HighlightResponse(.35f, 1f, DefaultDirectionCurve),
    widthScale: HighlightResponse = HighlightResponse(.6f, 1f, DefaultDirectionCurve),
): HighlightDirection.Incidence = HighlightDirection.Incidence(angle, mode, strength, widthScale)

/** Centered angular cuts prepared from public relative ranges. */
@Immutable
internal data class HighlightAngularCut(
    val angle: Float,
    val halfAngle: Float = 45f,
    val featherAngle: Float = 5f,
) {
    init {
        require(angle.isFinite()) { "angle must be finite" }
        require(halfAngle.isFinite() && halfAngle in 0f..180f) {
            "halfAngle must be finite and within 0..180"
        }
        require(featherAngle.isFinite() && featherAngle >= 0f && halfAngle + featherAngle <= 180f) {
            "featherAngle must fit within 180 degrees"
        }
    }
}

internal fun Set<HighlightAngularCut>.shaderValues(): FloatArray {
    val values = FloatArray(size * 3)
    forEachIndexed { index, item ->
        val radians = (PI / 180.0).toFloat()
        values[index * 3] = normalizedAngle(item.angle) * radians
        values[index * 3 + 1] = item.halfAngle * radians
        values[index * 3 + 2] = item.featherAngle * radians
    }
    return values
}

internal fun normalizedAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

internal val HighlightDirection.angle: Float
    get() =
        when (this) {
            HighlightDirection.Uniform -> 0f
            is HighlightDirection.Incidence -> angle
            is HighlightDirection.Angular -> angle
        }

internal val HighlightDirection.maximumWidthScale: Float
    get() =
        when (this) {
            HighlightDirection.Uniform -> 1f
            is HighlightDirection.Incidence -> maxOf(widthScale.from, widthScale.to)
            is HighlightDirection.Angular -> profile.maximumWidthScale
        }

/** Uses the normal-incidence response, rotated by [angle]. */
public fun HighlightScope.incidence(
    angle: Float = 90f,
    mode: HighlightDirection.Mode = HighlightDirection.Mode.Double,
    strength: HighlightResponse =
        (Highlight.Default.direction as HighlightDirection.Incidence).strength,
    widthScale: HighlightResponse =
        (Highlight.Default.direction as HighlightDirection.Incidence).widthScale,
) {
    direction = HighlightDirection(angle, mode, strength, widthScale)
}
