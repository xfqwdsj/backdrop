package top.ltfan.backdrop.benchmark.macro

import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.ltfan.backdrop.benchmark.scenarios.ScenarioId
import top.ltfan.backdrop.benchmark.scenarios.ScenarioJourney
import top.ltfan.backdrop.benchmark.scenarios.ScenarioTiming

@RunWith(Parameterized::class)
class SurfaceAnimationBenchmark(
    private val scenario: String,
    private val journey: ScenarioJourney,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun workloads(): List<Array<Any>> =
            ScenarioId.entries.flatMap { scenario ->
                ScenarioJourney.entries.map { journey -> arrayOf(scenario.name, journey) }
            }
    }

    @get:Rule val benchmark = MacrobenchmarkRule()
    private var idleTimeout = 0L

    @Before
    fun observeActiveAnimations() {
        val configuration = Configurator.getInstance()
        idleTimeout = configuration.waitForIdleTimeout
        configuration.waitForIdleTimeout = 0L
    }

    @After
    fun restoreIdleTimeout() {
        Configurator.getInstance().waitForIdleTimeout = idleTimeout
    }

    @Test
    fun animateSurface() {
        benchmark.measureRepeated(
            packageName = "top.ltfan.backdrop.benchmark",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = 10,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(
                            "top.ltfan.backdrop.benchmark",
                            "top.ltfan.backdrop.benchmark.BenchmarkActivity",
                        )
                        .putExtra("scenario", scenario)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ) {
            check(device.wait(Until.hasObject(By.descStartsWith("Toggle surface:")), 5000))
            val toggle = device.findObject(By.descStartsWith("Toggle surface:")).visibleCenter
            repeat(3) {
                val start = SystemClock.uptimeMillis()
                for (frame in journey.changeFrames) {
                    val deadline = start + frame * ScenarioTiming.FRAME_NANOS / 1_000_000L
                    SystemClock.sleep((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                    check(device.click(toggle.x, toggle.y))
                    device.requireObject(
                        By.descStartsWith("Toggle surface:").checked(journey.targetAt(frame)),
                        1000,
                    )
                }
                // Synchronous touch injection can return after a slow animation has finished.
                device.requireObject(By.desc("Toggle surface: idle").checked(false), 3000)
            }
        }
    }
}

private fun UiDevice.requireObject(selector: BySelector, timeoutMillis: Long) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    do {
        if (hasObject(selector)) return
        SystemClock.sleep(16)
    } while (SystemClock.uptimeMillis() < deadline)
    error("Surface did not reach $selector within $timeoutMillis ms")
}
