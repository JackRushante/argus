package dev.argus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Redesign A (0.7.0): ogni coppia testo/sfondo dei token regge almeno 4,5:1 nel tema chiaro E nel tema scuro;
 * i controlli non testuali (bordi di campi e interruttori, indicatori di focus) almeno 3:1.
 * Le coppie sono quelle che i componenti di components/ usano davvero.
 */
class DesignContrastTest {
    private class Tokens(
        val name: String,
        val scheme: ColorScheme,
        val semantic: ArgusSemantic,
        val surfaces: ArgusSurfaces,
    )

    private val themes = listOf(
        Tokens("scuro", ArgusDarkColors, ArgusSemanticDark, ArgusSurfacesDark),
        Tokens("chiaro", ArgusLightColors, ArgusSemanticLight, ArgusSurfacesLight),
    )

    private fun check(minimum: Double, what: String, pairs: Tokens.() -> List<Triple<String, Color, Color>>) {
        themes.forEach { theme ->
            theme.pairs().forEach { (label, fg, bg) ->
                val ratio = wcagContrast(fg, bg)
                assertTrue(ratio >= minimum, "${theme.name}: $what · $label = ${"%.2f".format(ratio)}:1 (minimo $minimum)")
            }
        }
    }

    private fun Tokens.statusRoles(): List<Pair<String, RolePair>> = listOf(
        "armed" to semantic.armed, "pending" to semantic.pending, "error" to semantic.error,
        "needsReview" to semantic.needsReview, "disabled" to semantic.disabled,
        "generative" to semantic.generative, "cloud" to semantic.cloud,
    )

    @Test
    fun `material role pairs reach 4_5 to 1`() = check(4.5, "ruoli M3") {
        listOf(
            Triple("onPrimary/primary", scheme.onPrimary, scheme.primary),
            Triple("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
            Triple("onSecondary/secondary", scheme.onSecondary, scheme.secondary),
            Triple("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer),
            Triple("onTertiary/tertiary", scheme.onTertiary, scheme.tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer),
            Triple("onError/error", scheme.onError, scheme.error),
            Triple("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer),
            Triple("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface),
            Triple("onAttention/attention", semantic.onAttention, semantic.attention),
            Triple("onAttentionContainer/attentionContainer", semantic.onAttentionContainer, semantic.attentionContainer),
        )
    }

    @Test
    fun `every status pair reaches 4_5 to 1 on its own background`() = check(4.5, "coppia di stato") {
        statusRoles().map { (name, role) -> Triple("$name fg/bg", role.fg, role.bg) } +
            Triple("codeText/codeSurface", semantic.codeText, surfaces.codeSurface)
    }

    @Test
    fun `status foregrounds stay readable as text on page card and sheet`() = check(4.5, "fg di stato come testo") {
        statusRoles().flatMap { (name, role) ->
            listOf(
                Triple("$name.fg su page", role.fg, surfaces.page),
                Triple("$name.fg su card", role.fg, surfaces.card),
                Triple("$name.fg su card2", role.fg, surfaces.card2),
                Triple("$name.fg su sheet", role.fg, surfaces.sheet),
                Triple("$name.fg su nav", role.fg, surfaces.nav),
            )
        }
    }

    @Test
    fun `primary and secondary text reach 4_5 to 1 on every Argus surface`() = check(4.5, "testo su superfici") {
        val all = listOf(
            "page" to surfaces.page, "card" to surfaces.card, "card2" to surfaces.card2,
            "field" to surfaces.field, "nav" to surfaces.nav, "sheet" to surfaces.sheet,
        )
        all.flatMap { (name, surface) ->
            listOf(
                Triple("onSurface su $name", scheme.onSurface, surface),
                Triple("onSurfaceVariant su $name", scheme.onSurfaceVariant, surface),
            )
        } + listOf("page", "card", "card2", "sheet", "nav").map { name ->
            Triple("primary (testo-link) su $name", scheme.primary, all.first { it.first == name }.second)
        }
    }

    @Test
    fun `summary hero counters and labels reach 4_5 to 1`() = check(4.5, "riepilogo") {
        val hero = scheme.secondaryContainer
        val tile = surfaces.heroTile.flattenOn(hero)
        listOf(
            Triple("armate (primary) su hero", scheme.primary, hero),
            Triple("in attesa (attention) su hero", semantic.attention, hero),
            Triple("da rivedere su hero", semantic.needsReview.fg, hero),
            Triple("etichetta (heroSecondary) su hero", surfaces.heroSecondary, hero),
            Triple("onSecondaryContainer su hero", scheme.onSecondaryContainer, hero),
            Triple("valore su riquadro", scheme.onSecondaryContainer, tile),
            Triple("etichetta su riquadro", surfaces.heroSecondary, tile),
            Triple("errore su riquadro", semantic.error.fg, tile),
            Triple("armate su riquadro", scheme.primary, tile),
        )
    }

    @Test
    fun `banners tiles fields and navigation reach 4_5 to 1`() = check(4.5, "banner, campi, navigazione") {
        listOf(
            Triple("banner ambra", semantic.onAttentionContainer, semantic.pending.bg),
            Triple("banner rosso", scheme.onErrorContainer, semantic.error.bg),
            Triple("icona ambra su cerchio", semantic.attention, semantic.attentionContainer),
            Triple("etichetta campo su field", scheme.onSurfaceVariant, surfaces.field),
            Triple("campo di sola lettura su card2", scheme.onSurfaceVariant, surfaces.card2),
            Triple("segmento non scelto", scheme.onSurfaceVariant, surfaces.card2),
            Triple("segmento scelto", scheme.onPrimary, scheme.primary),
            Triple("scelta in un modulo", scheme.onSecondaryContainer, scheme.secondaryContainer),
            Triple("etichetta nav scelta", scheme.onSurface, surfaces.nav),
            Triple("etichetta nav non scelta", scheme.onSurfaceVariant, surfaces.nav),
            Triple("icona nav scelta su indicatore", scheme.onSecondaryContainer, scheme.secondaryContainer),
            Triple("distruttivo su page", semantic.error.fg, surfaces.page),
            Triple("distruttivo su nav (footer)", semantic.error.fg, surfaces.nav),
            Triple("variabile locale nel codice", scheme.onSurface, surfaces.field),
            Triple("variabile esterna nel codice", semantic.pending.fg, semantic.pending.bg),
            Triple("etichetta shell (attention) su page", semantic.attention, surfaces.page),
            Triple("etichetta shell (attention) su card", semantic.attention, surfaces.card),
        )
    }

    @Test
    fun `borders and focus indicators reach 3 to 1 as non text contrast`() = check(3.0, "non testo") {
        listOf(
            Triple("outline su page", scheme.outline, surfaces.page),
            Triple("outline su card", scheme.outline, surfaces.card),
            Triple("outline su card2", scheme.outline, surfaces.card2),
            Triple("outline su sheet", scheme.outline, surfaces.sheet),
            Triple("outline su field (interruttore spento)", scheme.outline, surfaces.field),
            Triple("indicatore di focus primary su field", scheme.primary, surfaces.field),
            Triple("indicatore di errore su field", semantic.error.fg, surfaces.field),
        )
    }

    @Test
    fun `surface aliases map to the documented M3 roles`() {
        assertEquals(ArgusDarkColors.surface, ArgusSurfacesDark.page)
        assertEquals(ArgusDarkColors.surfaceContainer, ArgusSurfacesDark.card)
        assertEquals(ArgusDarkColors.surfaceContainerHigh, ArgusSurfacesDark.card2)
        assertEquals(ArgusDarkColors.surfaceContainerHighest, ArgusSurfacesDark.field)
        assertEquals(ArgusDarkColors.surfaceContainerLow, ArgusSurfacesDark.nav)
        assertEquals(ArgusDarkColors.surfaceContainerLow, ArgusSurfacesDark.sheet)

        assertEquals(ArgusLightColors.surfaceContainer, ArgusSurfacesLight.page)
        assertEquals(ArgusLightColors.surfaceContainerLowest, ArgusSurfacesLight.card)
        assertEquals(ArgusLightColors.surfaceContainerLow, ArgusSurfacesLight.card2)
        assertEquals(ArgusLightColors.surfaceContainerHighest, ArgusSurfacesLight.field)
        assertEquals(ArgusLightColors.surfaceContainerLowest, ArgusSurfacesLight.nav)
        assertEquals(ArgusLightColors.surfaceContainerLow, ArgusSurfacesLight.sheet)
    }

    @Test
    fun `existing screens keep reading background as page and surfaceVariant as card`() {
        assertEquals(ArgusSurfacesDark.page, ArgusDarkColors.background)
        assertEquals(ArgusSurfacesDark.card, ArgusDarkColors.surfaceVariant)
        assertEquals(ArgusSurfacesLight.page, ArgusLightColors.background)
        assertEquals(ArgusSurfacesLight.card, ArgusLightColors.surfaceVariant)
    }

    @Test
    fun `the two themes are different and the dark page is darker than the light one`() {
        assertNotEquals(ArgusDarkColors.primary, ArgusLightColors.primary)
        assertNotEquals(ArgusSurfacesDark.page, ArgusSurfacesLight.page)
        assertTrue(
            wcagContrast(ArgusSurfacesDark.page, Color.Black) < wcagContrast(ArgusSurfacesLight.page, Color.Black),
            "il fondo scuro deve essere piu' vicino al nero di quello chiaro",
        )
    }
}
