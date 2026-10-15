package top.ltfan.backdrop.interaction

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import top.ltfan.backdrop.BackdropCurve

/** A light emitted by an interaction source into its nearest [ProvideInteractionLighting] scope. */
@Stable
public sealed interface InteractionLight {
    public companion object {
        @Stable public val Default: InteractionLight = Config()
    }

    @Immutable public data object None : InteractionLight

    @Immutable
    public data class Config(
        public val color: Color = Color.White,
        public val radius: Dp = 160.dp,
        public val falloff: BackdropCurve = BackdropCurve.Linear,
        public val releaseDuration: Duration = 180.milliseconds,
    ) : InteractionLight {
        init {
            require(radius.value.isFinite() && radius.value >= 0f) {
                "Interaction light radius must be finite and nonnegative"
            }
            require(releaseDuration.isFinite() && releaseDuration >= Duration.ZERO) {
                "Interaction light release duration must be finite and nonnegative"
            }
        }
    }
}

/** Creates a configured interaction light. */
public fun InteractionLight(
    color: Color = Color.White,
    radius: Dp = 160.dp,
    falloff: BackdropCurve = BackdropCurve.Linear,
    releaseDuration: Duration = 180.milliseconds,
): InteractionLight = InteractionLight.Config(color, radius, falloff, releaseDuration)
