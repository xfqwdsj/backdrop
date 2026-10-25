package top.ltfan.backdrop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import top.ltfan.backdrop.internal.ColorUniformConverter
import top.ltfan.backdrop.internal.colorUniformComponents

class SkikoColorUniformStorageTest {
    @Test
    fun reusesConversionStorageAcrossColorSpacesWithoutChangingColorValues() {
        val colors =
            listOf(
                Color(0.2f, 0.4f, 0.6f, 0.7f),
                Color(4f, 3f, 2f, colorSpace = ColorSpaces.LinearExtendedSrgb),
                Color(-0.25f, 2f, 0.5f, colorSpace = ColorSpaces.LinearExtendedSrgb),
                Color(2f, -0.2f, 0.3f, colorSpace = ColorSpaces.ExtendedSrgb),
                Color(1.2f, -0.1f, 0.4f, alpha = 0f, colorSpace = ColorSpaces.DisplayP3),
                Color(0.1f, 0.3f, 0.5f, colorSpace = ColorSpaces.Srgb),
            )
        val converter = ColorUniformConverter()
        val savedCallerOwned = colorUniformComponents(colors[1])
        val savedValues = savedCallerOwned.copyOf()

        repeat(3) {
            for (color in colors) {
                assertContentEquals(colorUniformComponents(color), converter.write(color))
            }
        }
        assertContentEquals(savedValues, savedCallerOwned)
        assertNotSame(savedCallerOwned, colorUniformComponents(colors[1]))

        val linearExtended = converter.write(colors[1])
        assertTrue(linearExtended.all { it > 1f })
        val linearNegative = converter.write(colors[2])
        assertTrue(linearNegative[0] < 0f)
        assertTrue(linearNegative[1] > 1f)
        val extended = converter.write(colors[3])
        assertTrue(extended[0] > 1f)
        assertTrue(extended[1] < 0f)
        assertTrue(converter.write(colors[4]).all { it.isFinite() })
        assertEquals(0f, colors[4].alpha)
    }
}
