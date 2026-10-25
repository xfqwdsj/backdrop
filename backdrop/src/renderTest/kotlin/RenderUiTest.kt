package top.ltfan.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.test.TestResult

/** Keeps surface pixels and test coordinates identical across desktop and device densities. */
internal fun runRenderTest(block: suspend ComposeUiTest.() -> Unit): TestResult = runComposeUiTest {
    val host = this
    prepareRenderTestWindow(host)
    val test =
        object : ComposeUiTest by host {
            override val density: Density = Density(1f)

            override fun setContent(composable: @Composable () -> Unit) {
                host.setContent {
                    CompositionLocalProvider(LocalDensity provides density) {
                        RenderTestContent(composable)
                    }
                }
            }
        }
    block(test)
}

internal expect fun prepareRenderTestWindow(test: ComposeUiTest)

@Composable internal expect fun RenderTestContent(content: @Composable () -> Unit)

internal expect fun SemanticsNodeInteraction.captureToImage(): ImageBitmap

/** Android maps each new MotionEvent gesture to a fresh Compose PointerId. */
internal expect val touchGesturesReuseComposePointerIds: Boolean

internal expect fun layeredBlurDifferenceLimits(): List<FloatArray>
