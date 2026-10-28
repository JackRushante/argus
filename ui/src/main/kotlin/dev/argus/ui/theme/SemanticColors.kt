package dev.argus.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Coppia primo piano / sfondo di un ruolo di stato. Il colore non e' mai l'unico segnale: serve sempre anche icona e testo. */
@Immutable
data class RolePair(val fg: Color, val bg: Color)

/**
 * Coppie semantiche di stato (tavola «Coppie semantiche di stato», sostituisce il vecchio ArgusSemantic).
 *
 * Scala di gravita' leggibile anche da chi distingue male i colori: ambra ([pending], aspetta te) ->
 * arancio ([needsReview]) -> rosso ([error], bloccante), con icone diverse e luminosita' del fondo decrescente.
 * Ogni `fg` regge 4,5:1 sul proprio `bg` e sulle superfici `page` e `card` (test `DesignContrastTest`).
 */
@Immutable
data class ArgusSemantic(
    val armed: RolePair,
    val pending: RolePair,
    val error: RolePair,
    val needsReview: RolePair,
    val disabled: RolePair,
    val generative: RolePair,
    val cloud: RolePair,
    /** Testo dei blocchi di codice, da usare su [ArgusSurfaces.codeSurface]. */
    val codeText: Color,
    /** Accento caldo «attenzione»: badge di conteggio, contatore «in attesa», pulsante «Correggi». */
    val attention: Color,
    val onAttention: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
)

/**
 * Alias delle superfici (tavola «Ruoli Argus · alias delle superfici») piu' i colori del riepilogo (hero),
 * dello scrim e dei controlli disabilitati. Sono i nomi che usano le tavole (`--page`, `--card`, ...).
 */
@Immutable
data class ArgusSurfaces(
    /** Fondo schermata. */
    val page: Color,
    /** Righe, gruppi, pannelli. */
    val card: Color,
    /** Annidati, chip a riposo, segmenti non selezionati. */
    val card2: Color,
    /** Campi, binari, switch spento. */
    val field: Color,
    /** Barra di navigazione, footer di azione. */
    val nav: Color,
    /** Bottom sheet. */
    val sheet: Color,
    /** Blocco shell, dettaglio tecnico. */
    val codeSurface: Color,
    /** Oscuramento sotto i bottom sheet (gia' con la sua opacita': 64% scuro, 42% chiaro). */
    val scrim: Color,
    /** Sfondo di un pulsante disabilitato. */
    val disabledContainer: Color,
    /** Testo e icona di un controllo disabilitato (esente da 4,5:1 per WCAG: il motivo e' scritto a parte). */
    val disabledContent: Color,
    /** Riquadri interni del riepilogo, semitrasparenti sopra `secondaryContainer`. */
    val heroTile: Color,
    val heroLine: Color,
    /** Etichette secondarie nel riepilogo. */
    val heroSecondary: Color,
)

val ArgusSemanticDark = ArgusSemantic(
    armed = RolePair(Color(0xFF7FD99E), Color(0xFF103A25)),
    pending = RolePair(Color(0xFFFFC86B), Color(0xFF463100)),
    error = RolePair(Color(0xFFFFB4AB), Color(0xFF5B1712)),
    needsReview = RolePair(Color(0xFFFFA36B), Color(0xFF4E2508)),
    disabled = RolePair(Color(0xFFB2BAC6), Color(0xFF2A313A)),
    generative = RolePair(Color(0xFFD0BCFF), Color(0xFF352763)),
    cloud = RolePair(Color(0xFF7FD6E6), Color(0xFF0B3540)),
    codeText = Color(0xFFD6E8FF),
    attention = Color(0xFFFFC86B),
    onAttention = Color(0xFF422C00),
    attentionContainer = Color(0xFF5A4000),
    onAttentionContainer = Color(0xFFFFDFA6),
)

val ArgusSemanticLight = ArgusSemantic(
    armed = RolePair(Color(0xFF0F6B3A), Color(0xFFD0F0DA)),
    pending = RolePair(Color(0xFF7A5600), Color(0xFFFFE6B0)),
    error = RolePair(Color(0xFFA3211A), Color(0xFFFFDAD6)),
    needsReview = RolePair(Color(0xFF8E3B0E), Color(0xFFFFDBC9)),
    disabled = RolePair(Color(0xFF4C5460), Color(0xFFE2E6EB)),
    generative = RolePair(Color(0xFF5B42A8), Color(0xFFECE3FF)),
    cloud = RolePair(Color(0xFF00617D), Color(0xFFD2F0FC)),
    codeText = Color(0xFF0D2A4D),
    attention = Color(0xFF7C5800),
    onAttention = Color(0xFFFFFFFF),
    attentionContainer = Color(0xFFFFDFA6),
    onAttentionContainer = Color(0xFF271900),
)

val ArgusSurfacesDark = ArgusSurfaces(
    page = ArgusDarkPalette.Surface,
    card = ArgusDarkPalette.SurfaceContainer,
    card2 = ArgusDarkPalette.SurfaceContainerHigh,
    field = ArgusDarkPalette.SurfaceContainerHighest,
    nav = ArgusDarkPalette.SurfaceContainerLow,
    sheet = ArgusDarkPalette.SurfaceContainerLow,
    codeSurface = Color(0xFF0A0E13),
    scrim = ArgusDarkPalette.Scrim.copy(alpha = 0.64f),
    disabledContainer = Color(0xFFE2E7EF).copy(alpha = 0.12f),
    disabledContent = Color(0xFFE2E7EF).copy(alpha = 0.38f),
    heroTile = Color(0xFF9ECAFF).copy(alpha = 0.09f),
    heroLine = Color(0xFFD7E3FF).copy(alpha = 0.14f),
    heroSecondary = ArgusDarkPalette.Secondary,
)

val ArgusSurfacesLight = ArgusSurfaces(
    page = ArgusLightPalette.SurfaceContainer,
    card = ArgusLightPalette.SurfaceContainerLowest,
    card2 = ArgusLightPalette.SurfaceContainerLow,
    field = ArgusLightPalette.SurfaceContainerHighest,
    nav = ArgusLightPalette.SurfaceContainerLowest,
    sheet = ArgusLightPalette.SurfaceContainerLow,
    codeSurface = Color(0xFFE9EDF3),
    scrim = ArgusLightPalette.Scrim.copy(alpha = 0.42f),
    disabledContainer = Color(0xFF171C22).copy(alpha = 0.10f),
    disabledContent = Color(0xFF171C22).copy(alpha = 0.38f),
    heroTile = Color(0xFFFFFFFF).copy(alpha = 0.62f),
    heroLine = Color(0xFF0E1D38).copy(alpha = 0.12f),
    heroSecondary = ArgusLightPalette.Secondary,
)

val LocalArgusSemantic = staticCompositionLocalOf { ArgusSemanticDark }
val LocalArgusSurfaces = staticCompositionLocalOf { ArgusSurfacesDark }
