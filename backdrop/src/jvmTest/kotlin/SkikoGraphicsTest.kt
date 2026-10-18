package top.ltfan.backdrop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlin.test.BeforeTest

/** Initializes Compose primitives for tests that use Skia directly without a Compose scene. */
abstract class SkikoGraphicsTest {
    @OptIn(InternalComposeUiApi::class)
    @BeforeTest
    fun initializeComposeGraphics() {
        registerSkikoComposeImplementation()
    }
}
