package top.ltfan.backdrop.benchmark.scenarios

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

public enum class MotionAxis {
    MeasuredSize,
    Translation,
    UniformScale,
    Rotation,
    Blur,
    Alpha,
    Perspective,
    Stretch,
    Clip,
    SourceMotion,
    Reflow,
}

/** Interaction families with distinct layout, transform and compositing boundaries. */
public enum class ScenarioId(public val axes: Set<MotionAxis>) {
    SharedBounds(
        setOf(MotionAxis.MeasuredSize, MotionAxis.Translation, MotionAxis.Alpha, MotionAxis.Blur),
    ),
    Sheet(
        setOf(MotionAxis.MeasuredSize, MotionAxis.Translation, MotionAxis.Blur, MotionAxis.Alpha),
    ),
    Carousel(setOf(MotionAxis.Translation, MotionAxis.UniformScale, MotionAxis.Rotation)),
    Perspective(setOf(MotionAxis.Perspective, MotionAxis.Rotation)),
    Stretch(setOf(MotionAxis.Stretch, MotionAxis.Translation)),
    Compositing(setOf(MotionAxis.Alpha, MotionAxis.Clip)),
    Scroll(setOf(MotionAxis.SourceMotion)),
    Adaptive(setOf(MotionAxis.MeasuredSize, MotionAxis.Reflow)),
}

public object ScenarioTiming {
    public const val DURATION_MILLIS: Int = 600
    public const val FRAMES_PER_TRANSITION: Int = 96
    public const val FRAME_NANOS: Long = 16_666_667L
}

/** Snapshot-backed target state shared by interactive and deterministic frame-clock hosts. */
@Stable
public class ScenarioController(public val scenario: ScenarioId) {
    public var expanded: Boolean by mutableStateOf(false)
    private var phaseState: State<Float>? = null
    private var running: () -> Boolean = { false }
    internal val phase: Float
        get() = phaseState?.value ?: 0f

    internal val isRunning: Boolean
        get() = running()

    internal fun bind(phase: State<Float>, running: () -> Boolean) {
        phaseState = phase
        this.running = running
    }
}

/** Input schedules are shared by hosts; frame clocks and device input remain platform-owned. */
public enum class ScenarioJourney {
    Complete,
    Interrupted;

    public val changeFrames: List<Int>
        get() =
            when (this) {
                Complete -> listOf(0, ScenarioTiming.FRAMES_PER_TRANSITION)
                Interrupted -> listOf(0, 12, 20, ScenarioTiming.FRAMES_PER_TRANSITION)
            }

    public fun targetAt(frame: Int): Boolean =
        when (this) {
            Complete -> frame < ScenarioTiming.FRAMES_PER_TRANSITION
            Interrupted -> frame < 12 || frame in 20 until ScenarioTiming.FRAMES_PER_TRANSITION
        }
}
