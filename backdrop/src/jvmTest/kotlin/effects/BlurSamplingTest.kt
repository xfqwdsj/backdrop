package top.ltfan.backdrop.effects

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import top.ltfan.backdrop.BackdropRamp

class BlurSamplingTest {
    @Test
    fun gaussianSupportUsesFiniteThreeSigmaKernel() {
        assertEquals(2f, gaussianSupport(0.01f))
        assertEquals(19f, gaussianSupport(10f))
        assertEquals(923f, gaussianSupport(532f))
    }

    @Test
    fun progressiveSamplingUsesLocalRampStrengthAndOnlyExpandsItsPassAxis() {
        val ramp = BackdropRamp(from = 0f, to = 1f)
        val sampling =
            progressiveBlurSampling(
                vertical = true,
                verticalRamp = true,
                radius = 12f,
                ramp = ramp,
                anchorStart = 0f,
                anchorEnd = 100f,
            )

        val weakRegion = sampling.requiredInput(Rect(0f, 0f, 30f, 10f))
        val weakSupport = progressiveBlurKernelSupport(12f * ramp.intensityAt(0.1f))
        assertEquals(Rect(0f, -weakSupport, 30f, 10f + weakSupport), weakRegion)

        val strongRegion = sampling.requiredInput(Rect(0f, 90f, 30f, 100f))
        val strongSupport = progressiveBlurKernelSupport(12f * ramp.intensityAt(1f))
        assertEquals(Rect(0f, 90f - strongSupport, 30f, 100f + strongSupport), strongRegion)
    }

    @Test
    fun progressiveSamplingCapsTheDiscreteKernelAndKeepsSurfaceCoordinates() {
        val ramp = BackdropRamp(from = 1f, to = 1f)
        val sampling =
            progressiveBlurSampling(
                vertical = false,
                verticalRamp = false,
                radius = 500f,
                ramp = ramp,
                anchorStart = 40f,
                anchorEnd = 140f,
            )

        assertEquals(
            Rect(
                160f - progressiveBlurKernelSupport(500f),
                50f,
                200f + progressiveBlurKernelSupport(500f),
                60f,
            ),
            sampling.requiredInput(Rect(160f, 50f, 200f, 60f)),
        )
    }

    @Test
    fun zeroIntensityKeepsTheInputRegionUnchanged() {
        val ramp = BackdropRamp(from = 0f, to = 0f)
        val output = Rect(10f, 20f, 30f, 40f)
        val sampling =
            progressiveBlurSampling(
                vertical = true,
                verticalRamp = true,
                radius = 80f,
                ramp = ramp,
                anchorStart = 0f,
                anchorEnd = 100f,
            )

        assertEquals(output, sampling.requiredInput(output))
        assertEquals(0f, progressiveBlurKernelSupport(0f))
    }
}
