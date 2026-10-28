package dev.argus.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Contrasto WCAG 2.x tra due colori opachi (sRGB), come lo calcola TextContrastTest. */
internal fun wcagContrast(a: Color, b: Color): Double {
    fun linear(channel: Float): Double =
        if (channel <= 0.03928f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** Questo colore (con il suo alpha) steso sopra [background] opaco, come lo compone Compose in sRGB. */
internal fun Color.flattenOn(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha),
    green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha),
)
