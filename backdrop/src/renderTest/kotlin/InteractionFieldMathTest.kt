package top.ltfan.backdrop

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.interaction.interactionFieldResponse
import top.ltfan.backdrop.interaction.interactionLightWeight

class InteractionFieldMathTest {
    @Test
    fun sourceWeightsAndOverlappingResponseMatchTheSharedFieldDefinition() {
        val first =
            interactionLightWeight(
                25f,
                100f,
                sourceAlpha = .5f,
                intensity = 2f,
                BackdropCurve.Linear,
            )
        val second =
            interactionLightWeight(
                50f,
                100f,
                sourceAlpha = 1f,
                intensity = 1f,
                BackdropCurve.Linear,
            )

        assertEquals(.75f, first, .0001f)
        assertEquals(.5f, second, .0001f)
        assertEquals(1f - exp(-1.25f), interactionFieldResponse(first + second), .0001f)
    }

    @Test
    fun invalidAndOutOfRadiusSourcesCannotContribute() {
        assertEquals(
            0f,
            interactionLightWeight(100f, 100f, 1f, 1f, BackdropCurve.Linear),
        )
        assertEquals(
            0f,
            interactionLightWeight(0f, 0f, 1f, 1f, BackdropCurve.Linear),
        )
        assertEquals(
            0f,
            interactionLightWeight(
                0f,
                100f,
                Float.NaN,
                1f,
                BackdropCurve.Linear,
            ),
        )
        assertEquals(0f, interactionFieldResponse(0f))
        assertEquals(0f, interactionFieldResponse(Float.NaN))
    }
}
