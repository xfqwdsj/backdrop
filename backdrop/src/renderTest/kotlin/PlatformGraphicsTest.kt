package top.ltfan.backdrop

import androidx.compose.ui.unit.IntRect
import kotlin.math.pow
import kotlin.test.BeforeTest

/** Initializes platform graphics for tests that run without a Compose scene. */
abstract class PlatformGraphicsTest {
    @BeforeTest
    fun initializeComposeGraphics() {
        initializeTestGraphics()
    }
}

internal expect fun initializeTestGraphics()

/**
 * Reads shader numerics through a floating-point target independently of display configuration.
 * Optional [tiles] clip one complete draw, preserving the local-coordinate interpolation used by
 * the unmasked shader comparison.
 */
internal expect fun renderTestShader(
    shader: RuntimeShader,
    width: Int,
    height: Int,
    premultiplied: Boolean = true,
    tiles: List<IntRect>? = null,
): FloatArray

internal fun testHalfToFloat(bits: Int): Float {
    val sign = if (bits and 0x8000 == 0) 1f else -1f
    val exponent = (bits ushr 10) and 31
    val mantissa = bits and 1023
    return sign *
        when (exponent) {
            0 -> mantissa / 16_777_216f
            31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + mantissa / 1024f) * 2f.pow(exponent - 15)
        }
}
