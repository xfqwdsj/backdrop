package top.ltfan.backdrop.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.colorspace.Illuminant
import androidx.compose.ui.graphics.colorspace.Rgb
import androidx.compose.ui.graphics.colorspace.adapt
import kotlin.math.abs
import kotlin.math.pow

internal data class HighlightLinearColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
)

/**
 * Converts known sRGB transfer functions without passing extended values through a clamping path.
 */
internal fun Color.toHighlightLinearExtendedSrgb(): HighlightLinearColor {
    val space = colorSpace
    if (space === ColorSpaces.LinearExtendedSrgb || space === ColorSpaces.LinearSrgb) {
        return HighlightLinearColor(red, green, blue, alpha)
    }
    if (space === ColorSpaces.ExtendedSrgb || space === ColorSpaces.Srgb) {
        return HighlightLinearColor(
            red = decodeSrgb(red),
            green = decodeSrgb(green),
            blue = decodeSrgb(blue),
            alpha = alpha,
        )
    }
    val xyz =
        if (space is Rgb) {
            val adapted = space.adapt(Illuminant.D65) as Rgb
            val matrix = adapted.getTransform()
            val linearRed = space.eotf(red.toDouble()).toFloat()
            val linearGreen = space.eotf(green.toDouble()).toFloat()
            val linearBlue = space.eotf(blue.toDouble()).toFloat()
            multiply3x3(matrix, linearRed, linearGreen, linearBlue)
        } else {
            space.toXyz(floatArrayOf(red, green, blue))
        }
    val target = ColorSpaces.LinearExtendedSrgb
    val targetInverse =
        if (space is Rgb) target.getInverseTransform()
        else target.adapt(Illuminant.D50).let { (it as Rgb).getInverseTransform() }
    val converted = multiply3x3(targetInverse, xyz[0], xyz[1], xyz[2])
    return HighlightLinearColor(converted[0], converted[1], converted[2], alpha)
}

private fun multiply3x3(matrix: FloatArray, x: Float, y: Float, z: Float): FloatArray =
    floatArrayOf(
        matrix[0] * x + matrix[3] * y + matrix[6] * z,
        matrix[1] * x + matrix[4] * y + matrix[7] * z,
        matrix[2] * x + matrix[5] * y + matrix[8] * z,
    )

/** Encodes linear extended-sRGB channels without clamping values outside the display range. */
internal fun encodeHighlightExtendedSrgb(value: Float): Float =
    sign(value) *
        if (abs(value) <= .0031308f) abs(value) * 12.92f
        else 1.055f * abs(value).toDouble().pow(1.0 / 2.4).toFloat() - .055f

internal fun decodeHighlightExtendedSrgb(value: Float): Float = decodeSrgb(value)

private fun decodeSrgb(value: Float): Float =
    sign(value) *
        if (abs(value) <= .04045f) abs(value) / 12.92f
        else ((abs(value) + .055f) / 1.055f).toDouble().pow(2.4).toFloat()

private fun sign(value: Float): Float =
    when {
        value > 0f -> 1f
        value < 0f -> -1f
        else -> 0f
    }
