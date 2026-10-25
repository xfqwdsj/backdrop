package top.ltfan.backdrop.benchmark.scenarios

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Consumer-level rendering checks exercise intermediate frames, reversals and restored endpoints.
 */
@OptIn(InternalComposeUiApi::class)
class ScenarioAnimationTest {
    @Test
    fun everyFamilyAnimatesAndRecoversAfterReversal() {
        registerSkikoComposeImplementation()
        for (id in ScenarioId.entries) {
            val controller = ScenarioController(id)
            val scene = ImageComposeScene(width = 420, height = 900) { ScenarioScene(controller) }
            var clock = 0L
            fun frame(steps: Int = 4): Long {
                Snapshot.sendApplyNotifications()
                clock += ScenarioTiming.FRAME_NANOS * steps
                return scene.render(clock).use { signature(it.toComposeImageBitmap()) }
            }
            try {
                repeat(16) { frame() }
                val closed = frame()
                controller.expanded = true
                val animation = mutableSetOf<Long>()
                repeat(25) { animation += frame() }
                assertTrue(
                    animation.size >= 3,
                    "$id must render intermediate states, not just endpoints",
                )
                assertTrue(controller.phase > .99f, "$id opening did not settle")
                controller.expanded = false
                repeat(3) { frame() }
                controller.expanded = true
                repeat(2) { frame() }
                controller.expanded = false
                repeat(30) { frame() }
                assertFalse(controller.isRunning, "$id remained active after dismissal")
                assertTrue(controller.phase < .01f)
                assertEquals(
                    closed,
                    frame(),
                    "$id did not restore its closed appearance",
                )
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun backgroundOpacityPreservesForegroundWhileGroupOpacityFadesIt() {
        registerSkikoComposeImplementation()
        val controller = ScenarioController(ScenarioId.Compositing)
        val scene = ImageComposeScene(width = 420, height = 900) { ScenarioScene(controller) }
        try {
            var clock = 0L
            repeat(8) { scene.render(++clock * ScenarioTiming.FRAME_NANOS).close() }
            scene.render(++clock * ScenarioTiming.FRAME_NANOS).use { image ->
                val pixels = image.toComposeImageBitmap().toPixelMap()
                assertEquals(1f, pixels[210, 220].red, .01f)
                assertTrue(pixels[210, 680].red < .99f)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun inputSchedulesCoverCompleteAndInterruptedTransitions() {
        assertEquals(MotionAxis.entries.toSet(), ScenarioId.entries.flatMap { it.axes }.toSet())
        for (journey in ScenarioJourney.entries) {
            val changes =
                (0 until ScenarioTiming.FRAMES_PER_TRANSITION * 2).filter { frame ->
                    frame == 0 || journey.targetAt(frame) != journey.targetAt(frame - 1)
                }
            assertEquals(journey.changeFrames, changes)
        }

        assertTrue(ScenarioJourney.Interrupted.targetAt(0))
        assertFalse(ScenarioJourney.Interrupted.targetAt(12))
        assertTrue(ScenarioJourney.Interrupted.targetAt(20))
        assertFalse(ScenarioJourney.Interrupted.targetAt(ScenarioTiming.FRAMES_PER_TRANSITION))
    }

    private fun signature(image: ImageBitmap): Long {
        val pixels = image.toPixelMap()
        var hash = 1125899906842597L
        for (y in 20 until image.height step 24) for (x in 20 until image.width step 24) hash =
            hash * 31 + pixels[x, y].toArgb()
        return hash
    }
}
