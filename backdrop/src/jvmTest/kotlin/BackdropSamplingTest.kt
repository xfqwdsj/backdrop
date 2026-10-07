package top.ltfan.backdrop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import top.ltfan.backdrop.effects.runtimeShaderEffect

class BackdropSamplingTest {
    @Test
    fun sequentialOutsetsAccumulateAcrossStages() {
        val scope = testScope(Size(10f, 10f))

        scope.resolveEffects {
            addEffect(BackdropSampling.outsets(2f, 2f, 2f, 2f)) { blurEffect() }
            addEffect(BackdropSampling.outsets(3f, 3f, 3f, 3f)) { blurEffect() }
        }

        assertEquals(BackdropExtension(5f, 5f, 5f, 5f), scope.extension)
    }

    @Test
    fun translatedAsymmetricSamplingUnionsEveryIntermediateRegion() {
        val scope = testScope(Size(10f, 10f))
        val stageBounds = mutableListOf<Rect>()

        scope.resolveEffects {
            addEffect(BackdropSampling.translated(20f, -5f)) {
                stageBounds += scope.samplingBounds
                blurEffect()
            }
            addEffect(BackdropSampling.outsets(1f, 2f, 3f, 4f)) {
                stageBounds += scope.samplingBounds
                blurEffect()
            }
        }

        assertEquals(Rect(19f, -7f, 33f, 9f), stageBounds[0])
        assertEquals(Rect(-1f, -2f, 13f, 14f), stageBounds[1])
        assertEquals(BackdropExtension(1f, 7f, 23f, 4f), scope.extension)
    }

    @Test
    fun samplerMustReturnFiniteNonInvertedBounds() {
        val scope = testScope(Size(10f, 10f))

        assertFailsWith<IllegalArgumentException> {
            scope.resolveEffects {
                addEffect(sampling = { Rect(Float.NaN, 0f, 1f, 1f) }) { blurEffect() }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            scope.resolveEffects {
                addEffect(sampling = { Rect(2f, 0f, 1f, 1f) }) { blurEffect() }
            }
        }
    }

    @Test
    fun coverageRequestsMergePerSideAndAddToCallerCoverage() {
        val scope = testScope(Size(10f, 10f))

        scope.resolveEffects(
            effects = {
                cover(BackdropInsets(left = 3.dp, top = 1.dp, right = 2.dp, bottom = 5.dp))
                cover(BackdropInsets(left = 1.dp, top = 4.dp, right = 4.dp, bottom = 2.dp))
            },
            requested = BackdropInsets.all(1.dp),
        )

        assertEquals(
            BackdropInsets(left = 3.dp, top = 4.dp, right = 4.dp, bottom = 5.dp),
            scope.cover,
        )
        assertEquals(BackdropExtension(4f, 5f, 5f, 6f), scope.extension)
    }

    @Test
    fun effectsRunOnceAndDeferredFactoriesSeeResolvedExtensionAndStageInput() {
        val scope = testScope(Size(10f, 8f))
        var producerCalls = 0
        var factoryCalls = 0
        var factoryExtension: BackdropExtension? = null
        var factoryBounds: Rect? = null

        scope.resolveEffects {
            producerCalls++
            addEffect(BackdropSampling.outsets(2f, 3f, 4f, 5f)) {
                factoryCalls++
                factoryExtension = extension
                factoryBounds = scope.samplingBounds
                blurEffect()
            }
        }

        assertEquals(1, producerCalls)
        assertEquals(1, factoryCalls)
        assertEquals(BackdropExtension(2f, 3f, 4f, 5f), factoryExtension)
        assertEquals(Rect(-2f, -3f, 14f, 13f), factoryBounds)
    }

    @Test
    fun unspecifiedSizeStillAllowsPointwiseEffects() {
        val scope = testScope(Size.Unspecified)
        var factoryRan = false

        scope.resolveEffects {
            addEffect(BackdropSampling.Identity) {
                factoryRan = true
                blurEffect()
            }
        }

        assertEquals(true, factoryRan)
        assertEquals(Rect.Zero, scope.samplingBounds)
        assertEquals(BackdropExtension.None, scope.extension)
    }

    @Test
    fun repeatedRuntimeShaderKeysGetDistinctStageCacheEntries() {
        val scope = testScope(Size(10f, 10f))
        val shaders = mutableListOf<RuntimeShader>()
        val source =
            "uniform shader content; uniform float value; half4 main(float2 p) { return content.eval(p) * value; }"

        scope.resolveEffects {
            repeat(2) {
                runtimeShaderEffect(
                    key = "same",
                    shaderString = source,
                    uniformShaderName = "content",
                    sampling = BackdropSampling.Identity,
                ) {
                    shaders += this
                    setFloatUniform("value", 1f)
                }
            }
        }

        assertEquals(2, shaders.size)
        assertNotSame(shaders[0], shaders[1])
    }

    private fun testScope(size: Size): TestScope = TestScope().apply { this.size = size }

    private class TestScope : BackdropEffectScopeImpl() {
        override val shape: Shape = RectangleShape
    }

    private fun blurEffect() = BlurEffect(1f, 1f)
}
