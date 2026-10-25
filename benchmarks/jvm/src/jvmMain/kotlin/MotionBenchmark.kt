package top.ltfan.backdrop

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import top.ltfan.backdrop.benchmark.scenarios.ScenarioController
import top.ltfan.backdrop.benchmark.scenarios.ScenarioId
import top.ltfan.backdrop.benchmark.scenarios.ScenarioJourney
import top.ltfan.backdrop.benchmark.scenarios.ScenarioScene
import top.ltfan.backdrop.benchmark.scenarios.ScenarioTiming

/** Measures unpaced CPU raster throughput for shared consumer scenes. */
// JMH discovers these entry points through generated harness code.
@Suppress("unused")
@State(Scope.Benchmark)
open class MotionBenchmark {
    @Param var scenario: ScenarioId = ScenarioId.SharedBounds
    @Param var journey: ScenarioJourney = ScenarioJourney.Complete
    private lateinit var controller: ScenarioController
    private lateinit var scene: ImageComposeScene
    private var frame = 0L
    private var inputFrame = 0L

    @OptIn(InternalComposeUiApi::class)
    @Setup
    fun setup() {
        registerSkikoComposeImplementation()
        frame = 0L
        inputFrame = 0L
        controller = ScenarioController(scenario)
        scene = ImageComposeScene(width = 420, height = 900) { ScenarioScene(controller) }
        repeat(4) { scene.render(++frame * ScenarioTiming.FRAME_NANOS).close() }
    }

    @Benchmark
    fun animatedFrame(): Boolean {
        val cycleFrame = (inputFrame++ % (ScenarioTiming.FRAMES_PER_TRANSITION * 2)).toInt()
        controller.expanded = journey.targetAt(cycleFrame)
        Snapshot.sendApplyNotifications()
        scene.render(++frame * ScenarioTiming.FRAME_NANOS).close()
        return controller.expanded
    }

    @TearDown
    fun teardown() {
        scene.close()
    }
}
