package top.ltfan.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import top.ltfan.backdrop.backdrops.rememberCanvasBackdrop
import top.ltfan.backdrop.effects.runtimeShaderEffect

class RuntimeShaderCacheTest : PlatformGraphicsTest() {
    @Test
    fun processSlotRetainsShadersAcrossUpdatesAndReleasesThemWhenOmitted() = runRenderTest {
        val gain = mutableFloatStateOf(1f)
        val enabled = mutableStateOf(true)
        val observed = mutableListOf<RuntimeShader>()
        setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.Blue) }
            Box(
                Modifier.size(40.dp).drawBackdrop(source, { RectangleShape }) {
                    if (enabled.value)
                        effects {
                            runtimeShaderEffect(
                                "gain",
                                "uniform shader content; uniform float gain; half4 main(float2 p) { return content.eval(p) * gain; }",
                                "content",
                                BackdropSampling.Identity,
                            ) {
                                observed += this
                                setFloatUniform("gain", gain.floatValue)
                            }
                        }
                },
            )
        }
        waitForIdle()
        val initial = observed.last()
        repeat(3) { i ->
            runOnIdle { gain.floatValue = .2f + i * .1f }
            waitForIdle()
            assertSame(initial, observed.last())
        }
        runOnIdle { enabled.value = false }
        waitForIdle()
        val disabledCount = observed.size
        runOnIdle { gain.floatValue = .9f }
        waitForIdle()
        assertEquals(disabledCount, observed.size)
        runOnIdle { enabled.value = true }
        waitForIdle()
        assertNotSame(initial, observed.last())
    }

    @Test
    fun uniformChangesReuseShaderButSourceChangesReplaceIt() {
        val cache = RuntimeShaderCacheImpl()
        val source = "uniform float gain; half4 main(float2 p) { return half4(gain); }"
        val first = cache.obtainRuntimeShader("effect", source)
        repeat(180) { frame ->
            val shader = cache.obtainRuntimeShader("effect", source)
            shader.setFloatUniform("gain", frame / 180f)
            assertSame(first, shader)
        }
        val replacement =
            cache.obtainRuntimeShader("effect", "half4 main(float2 p) { return half4(0.0); }")
        assertNotSame(first, replacement)
    }

    @Test
    fun lazySourcesAreBuiltOncePerKeyUntilCacheIsReleased() {
        val cache = RuntimeShaderCacheImpl()
        var builds = 0
        val source = {
            builds++
            "half4 main(float2 p) { return half4(1.0); }"
        }
        val first = cache.obtainRuntimeShader("capacity4", source)
        repeat(180) { assertSame(first, cache.obtainRuntimeShader("capacity4", source)) }
        assertEquals(1, builds)
        assertNotSame(first, cache.obtainRuntimeShader("capacity8", source))
        assertEquals(2, builds)
        cache.clear()
        assertNotSame(first, cache.obtainRuntimeShader("capacity4", source))
        assertEquals(3, builds)
    }
}
