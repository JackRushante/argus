package dev.argus.ui.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.argus.ui.theme.ArgusDarkColors
import dev.argus.ui.theme.ArgusLightColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B45 (0.4.0): orari, «ultimo scatto», versione e righe attenuate del Log erano scritti con `outline`,
 * che sui fondi scuri dell'app ha un contrasto di circa 1,6:1. Qui si calcola il contrasto WCAG di ogni
 * coppia testo/sfondo realmente usata, in entrambi i temi.
 */
class TextContrastTest {
    private val themes: Map<String, ColorScheme> = mapOf(
        "dark" to ArgusDarkColors,
        "light" to ArgusLightColors,
    )

    private fun surfaces(scheme: ColorScheme): List<Color> =
        listOf(scheme.background, scheme.surface, scheme.surfaceVariant)

    @Test
    fun `secondary text keeps at least 4_5 to 1 on every surface in both themes`() {
        themes.forEach { (name, scheme) ->
            surfaces(scheme).forEach { background ->
                val ratio = contrast(scheme.secondaryText(), background)
                assertTrue(ratio >= 4.5, "$name: testo secondario $ratio:1 su $background")
            }
        }
    }

    @Test
    fun `outline is a border token and not the text token`() {
        themes.forEach { (name, scheme) ->
            assertNotEquals(scheme.outline, scheme.secondaryText(), "$name: outline non e' un token per i testi")
            // Redesign A: outline e' un contorno di controllo, quindi regge 3:1 (WCAG 1.4.11) su fondo e card.
            listOf(scheme.background, scheme.surfaceVariant).forEach { background ->
                val ratio = contrast(scheme.outline, background)
                assertTrue(ratio >= 3.0, "$name: outline $ratio:1 su $background")
            }
        }
    }

    @Test
    fun `attenuated rows keep primary and secondary text above 4_5 to 1 in both themes`() {
        themes.forEach { (name, scheme) ->
            listOf(scheme.onSurface, scheme.secondaryText()).forEach { text ->
                listOf(scheme.background, scheme.surfaceVariant).forEach { background ->
                    val shown = over(text, ATTENUATED_ROW_ALPHA, background)
                    val ratio = contrast(shown, background)
                    assertTrue(ratio >= 4.5, "$name: riga attenuata $ratio:1 ($text su $background)")
                }
            }
        }
    }

    @Test
    fun `the old attenuation of the log was too faint`() {
        // Documenta il perche' della correzione: con 0.5 il testo secondario scendeva sotto 4,5:1.
        val dark = ArgusDarkColors
        val old = over(dark.secondaryText(), 0.5f, dark.background)
        assertTrue(contrast(old, dark.background) < 4.5)
    }

    @Test
    fun `the attenuation is still visibly lighter than a normal row`() {
        assertTrue(ATTENUATED_ROW_ALPHA < 1f)
    }

    // ---- WCAG 2.x -------------------------------------------------------------------------------

    private fun linear(channel: Float): Double =
        if (channel <= 0.03928f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [foreground] con opacita' [alpha] sopra [background], come lo compone Compose in sRGB. */
    private fun over(foreground: Color, alpha: Float, background: Color): Color = Color(
        red = foreground.red * alpha + background.red * (1 - alpha),
        green = foreground.green * alpha + background.green * (1 - alpha),
        blue = foreground.blue * alpha + background.blue * (1 - alpha),
    )
}
