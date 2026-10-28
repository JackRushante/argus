package dev.argus.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coppie colore nuove della lista e della chat (redesign A, 0.7.0) che `DesignContrastTest` non copre:
 * il messaggio di errore della chat (testo e azioni sul fondo rosso), il riepilogo con testo normale.
 * Testo >= 4,5:1, nei due temi.
 */
class ListChatContrastTest {
    private class Theme(val name: String, val scheme: androidx.compose.material3.ColorScheme, val semantic: ArgusSemantic, val surfaces: ArgusSurfaces)

    private val themes = listOf(
        Theme("scuro", ArgusDarkColors, ArgusSemanticDark, ArgusSurfacesDark),
        Theme("chiaro", ArgusLightColors, ArgusSemanticLight, ArgusSurfacesLight),
    )

    private fun check(what: String, pairs: Theme.() -> List<Triple<String, Color, Color>>) {
        themes.forEach { theme ->
            theme.pairs().forEach { (label, fg, bg) ->
                val ratio = wcagContrast(fg, bg)
                assertTrue(ratio >= 4.5, "${theme.name}: $what · $label = ${"%.2f".format(ratio)}:1 (minimo 4,5)")
            }
        }
    }

    @Test
    fun `the error message of the chat is readable`() = check("messaggio di errore") {
        listOf(
            Triple("testo su fondo rosso", scheme.onSurface, semantic.error.bg),
            Triple("«Riprova» e «Apri Sistema» su fondo rosso", scheme.primary, semantic.error.bg),
            Triple("«Dettaglio tecnico» su fondo rosso", semantic.error.fg, semantic.error.bg),
            Triple("codice nel dettaglio tecnico", semantic.codeText, surfaces.codeSurface),
        )
    }

    @Test
    fun `chat bubbles, wait card and composer are readable`() = check("chat") {
        listOf(
            Triple("bolla utente", scheme.onPrimaryContainer, scheme.primaryContainer),
            Triple("bolla di Argus", scheme.onSurface, surfaces.card),
            Triple("ora sotto la bolla", scheme.onSurfaceVariant, surfaces.page),
            Triple("«Annulla» sulla scheda d'attesa", scheme.primary, surfaces.card),
            Triple("numeri dell'asse dei tempi", scheme.onSurfaceVariant, surfaces.card),
            Triple("segnaposto del campo", scheme.onSurfaceVariant, surfaces.field),
            Triple("campo disabilitato in attesa", scheme.onSurfaceVariant, surfaces.card2),
            Triple("avviso informativo", scheme.onSurface, surfaces.card2),
            Triple("avvisi contati nella card bozza", semantic.pending.fg, surfaces.card),
        )
    }
}
