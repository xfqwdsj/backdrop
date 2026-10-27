package top.ltfan.backdrop.internal

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor

/** Expands a sampling request to complete texels while retaining already aligned bounds. */
internal fun Rect.pixelAligned(): Rect {
    val alignedLeft = floor(left)
    val alignedTop = floor(top)
    val alignedRight = ceil(right)
    val alignedBottom = ceil(bottom)
    return if (
        left == alignedLeft && top == alignedTop && right == alignedRight && bottom == alignedBottom
    )
        this
    else Rect(alignedLeft, alignedTop, alignedRight, alignedBottom)
}
