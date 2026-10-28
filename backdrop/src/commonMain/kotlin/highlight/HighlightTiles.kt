package top.ltfan.backdrop.highlight

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/** Retains disjoint output rectangles around the contour's certified transparent interior. */
internal class HighlightTiles {
    private val tiles = arrayOfNulls<IntRect>(4)
    var count: Int = 0
        private set

    operator fun get(index: Int): IntRect = requireNotNull(tiles[index])

    fun prepare(size: IntSize, empty: Rect, maximumBandWidth: Float) {
        count = 0
        if (size.width <= 0 || size.height <= 0) return
        val margin = maximumBandWidth + maxOf(.0001f, maximumBandWidth * .000001f) + .5f
        // Rounding inward keeps every omitted pixel footprint within the transparent certificate.
        val left = ceil(empty.left + margin).toInt().coerceIn(0, size.width)
        val top = ceil(empty.top + margin).toInt().coerceIn(0, size.height)
        val right = floor(empty.right - margin).toInt().coerceIn(0, size.width)
        val bottom = floor(empty.bottom - margin).toInt().coerceIn(0, size.height)
        if (empty.isEmpty || left >= right || top >= bottom) {
            add(0, 0, size.width, size.height)
            return
        }
        add(0, 0, size.width, top)
        add(0, bottom, size.width, size.height)
        add(0, top, left, bottom)
        add(right, top, size.width, bottom)
    }

    private fun add(left: Int, top: Int, right: Int, bottom: Int) {
        if (left >= right || top >= bottom) return
        val previous = tiles[count]
        if (
            previous == null ||
                previous.left != left ||
                previous.top != top ||
                previous.right != right ||
                previous.bottom != bottom
        ) {
            tiles[count] = IntRect(left, top, right, bottom)
        }
        count++
    }
}
