package top.ltfan.backdrop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import top.ltfan.backdrop.internal.ShapeProvider

class ShapeProviderCachingTest {
    @Test
    fun outlineFactoryTracksSnapshotInputsAndDensityWithoutCallingFactoryPerDraw() {
        val radius = mutableStateOf(2f)
        var factoryCalls = 0
        var outlineCalls = 0
        val provider = ShapeProvider {
            factoryCalls++
            val currentRadius = radius.value
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline {
                    outlineCalls++
                    return Outline.Rounded(
                        RoundRect(
                            0f,
                            0f,
                            size.width,
                            size.height,
                            CornerRadius(currentRadius * density.fontScale),
                        )
                    )
                }
            }
        }
        val size = Size(40f, 30f)
        val density = Density(1f, 1f)
        val first = provider.shape.createOutline(size, LayoutDirection.Ltr, density)
        val second = provider.shape.createOutline(size, LayoutDirection.Ltr, density)
        assertSame(first, second)
        assertEquals(1, factoryCalls)
        assertEquals(1, outlineCalls)

        radius.value = 3f
        val snapshotUpdated = provider.shape.createOutline(size, LayoutDirection.Ltr, density)
        assertEquals(2, factoryCalls)
        assertEquals(2, outlineCalls)
        assertEquals(3f, (snapshotUpdated as Outline.Rounded).roundRect.topLeftCornerRadius.x)

        val fontScaled = provider.shape.createOutline(size, LayoutDirection.Ltr, Density(1f, 2f))
        assertEquals(3, factoryCalls)
        assertEquals(3, outlineCalls)
        assertEquals(6f, (fontScaled as Outline.Rounded).roundRect.topLeftCornerRadius.x)

        provider.shape.createOutline(Size(41f, 30f), LayoutDirection.Ltr, Density(1f, 2f))
        assertEquals(4, factoryCalls)
        assertEquals(4, outlineCalls)
    }
}
