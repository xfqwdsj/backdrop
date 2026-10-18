package top.ltfan.backdrop.highlight

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import top.ltfan.backdrop.BackdropCurve

/** Directional emission measured against each boundary's outward normal. */
@Immutable
public sealed interface HighlightDirection {
    @Immutable public data object None : HighlightDirection

    @Immutable
    public data class Config(
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
): HighlightDirection.Config = HighlightDirection.Config(angle, mode, strength, widthScale)

/** A boundary region where a highlight is hidden, measured from its local outward normal. */
@Immutable
public sealed interface HighlightExclusion {
    @Immutable public data object Left : HighlightExclusion

    @Immutable public data object Top : HighlightExclusion

    @Immutable public data object Right : HighlightExclusion

    @Immutable public data object Bottom : HighlightExclusion

    @Immutable
    public data class Direction(
        public val angle: Float,
        public val halfAngle: Float = 45f,
        public val featherAngle: Float = 5f,
    ) : HighlightExclusion {
        init {
            require(angle.isFinite()) { "angle must be finite" }
            require(halfAngle.isFinite() && halfAngle in 0f..180f) {
                "halfAngle must be finite and between 0 and 180"
            }
            require(
                featherAngle.isFinite() && featherAngle >= 0f && halfAngle + featherAngle <= 180f,
            ) {
                "featherAngle must be finite, non-negative, and fit within 180 degrees"
            }
        }
    }
}

internal fun HighlightExclusion.asDirection(): HighlightExclusion.Direction =
    when (this) {
        HighlightExclusion.Left -> HighlightExclusion.Direction(180f)
        HighlightExclusion.Top -> HighlightExclusion.Direction(270f)
        HighlightExclusion.Right -> HighlightExclusion.Direction(0f)
        HighlightExclusion.Bottom -> HighlightExclusion.Direction(90f)
        is HighlightExclusion.Direction -> this
    }

internal fun Set<HighlightExclusion>.shaderValues(): FloatArray {
    val values = FloatArray(size * 3)
    forEachIndexed { index, item ->
        val direction = item.asDirection()
        val radians = (PI / 180.0).toFloat()
        values[index * 3] = normalizedAngle(direction.angle) * radians
        values[index * 3 + 1] = direction.halfAngle * radians
        values[index * 3 + 2] = direction.featherAngle * radians
    }
    return values
}

internal fun normalizedAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f
