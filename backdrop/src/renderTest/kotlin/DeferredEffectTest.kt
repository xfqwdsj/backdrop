package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.effects.effect

class DeferredEffectTest : PlatformGraphicsTest() {
    @Test
    fun factoriesSeeAccumulatedStageBoundsAndTheFinalGraphExtension() {
        val scope = TestScope().apply { size = Size(10f, 10f) }
        val seenBounds = mutableListOf<Rect>()
        val seenExtensions = mutableListOf<BackdropExtension>()
        var factories = 0
        scope.prepareEffects {
            effect(BackdropSampling.translated(20f, -5f)) {
                factories++
                seenBounds += inputBounds
                seenExtensions += extension
                BlurEffect(1f, 1f)
            }
            effect(BackdropSampling.outsets(1f, 2f, 3f, 4f)) {
                factories++
                seenBounds += inputBounds
                seenExtensions += extension
                BlurEffect(1f, 1f)
            }
        }
        assertEquals(0, factories, "registration must defer construction")
        scope.measureStages(Rect(0f, 0f, 10f, 10f), 10f, 10f)
        assertEquals(0, factories, "sampling resolution must defer construction")
        val finalGraphExtension = BackdropExtension(9f, 11f, 30f, 12f)
        scope.buildEffects(finalGraphExtension)
        assertEquals(2, factories)
        assertEquals(listOf(Rect(19f, -7f, 33f, 9f), Rect(-1f, -2f, 13f, 14f)), seenBounds)
        assertEquals(listOf(finalGraphExtension, finalGraphExtension), seenExtensions)
    }

    @Test
    fun deferredContextReflectsGeometryAndKeepsStageShaderCachesIndependent() {
        val scope =
            TestScope().apply {
                size = Size(20f, 30f)
                density = 2f
                fontScale = 1.25f
                layoutDirection = LayoutDirection.Rtl
            }
        val shaders = mutableListOf<RuntimeShader>()
        val densities = mutableListOf<Float>()
        val sizes = mutableListOf<Size>()
        val declaration: BackdropEffectScope.() -> Unit = {
            repeat(2) {
                effect(BackdropSampling.Identity) {
                    val shader =
                        obtainRuntimeShader("same", "half4 main(float2 p) { return half4(1.0); }")
                    shaders += shader
                    densities += density.density
                    sizes += size
                    assertEquals(scope.fontScale, density.fontScale)
                    assertEquals(scope.layoutDirection, layoutDirection)
                    assertSame(scope.shape, shape)
                    assertTrue((this as Any) !is BackdropEffectScope)
                    BlurEffect(1f, 1f)
                }
            }
        }
        scope.resolveEffects(declaration)
        assertNotSame(
            shaders[0],
            shaders[1],
            "equal keys in different stages cannot share mutable uniforms",
        )
        scope.density = 3f
        scope.size = Size(40f, 50f)
        scope.resolveEffects(declaration)
        assertSame(shaders[0], shaders[2])
        assertSame(shaders[1], shaders[3])
        assertEquals(listOf(2f, 2f, 3f, 3f), densities)
        assertEquals(listOf(Size(20f, 30f), Size(20f, 30f), Size(40f, 50f), Size(40f, 50f)), sizes)
        scope.reset()
        scope.resolveEffects(declaration)
        assertNotSame(shaders[0], shaders[4], "resource reset releases deferred shader caches")
    }

    @Test
    fun invalidSamplingRejectsTheStageBeforeItsFactoryRuns() {
        val scope = TestScope().apply { size = Size(10f, 10f) }
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            scope.resolveEffects {
                effect(sampling = { Rect(Float.NaN, 0f, 10f, 10f) }) {
                    calls++
                    BlurEffect(1f, 1f)
                }
            }
        }
        assertEquals(0, calls)
    }

    private class TestScope : BackdropEffectScopeImpl() {
        override val shape: Shape = RectangleShape
    }
}
