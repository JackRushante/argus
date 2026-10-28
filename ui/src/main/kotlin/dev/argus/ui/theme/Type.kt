package dev.argus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.argus.ui.R

// =============================================================================
// Tipografia (tavola «Tipografia»). Tutto in sp: regge la scala del testo 1,3-2,0 andando a capo.
//
// Font: nel pacchetto, nessun download a runtime (niente font scaricabili di Google Play Services,
// incompatibili con F-Droid). Entrambi OFL 1.1, testi in docs/third-party/fonts/.
//  - Roboto (v3), istanze statiche 400/500/600/700 ricavate dal font variabile e ridotte a Latin (circa 66 KB l'una):
//    il 600 esiste davvero, cosa che i font di sistema OEM (OnePlus Sans, SamsungOne, ...) non garantiscono.
//  - JetBrains Mono 400/500, ridotto a Latin e SENZA ligature (calt/liga rimossi dal file): in un comando
//    shell `->` o `!=` devono restare i caratteri scritti, mai un glifo fuso. Distingue 0/O e 1/l/I.
// I caratteri fuori dal sottoinsieme (cirillico, CJK, emoji, ...) ricadono sul font di sistema, come per ogni font.
// =============================================================================

/** Roboto incluso nel pacchetto: pesi 400, 500, 600, 700. */
val ArgusSans: FontFamily = FontFamily(
    Font(R.font.roboto_regular, FontWeight.W400),
    Font(R.font.roboto_medium, FontWeight.W500),
    Font(R.font.roboto_semibold, FontWeight.W600),
    Font(R.font.roboto_bold, FontWeight.W700),
)

/** JetBrains Mono incluso nel pacchetto: pesi 400 e 500. Per comandi, identificatori, endpoint, codici. */
val ArgusMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.W400),
    Font(R.font.jetbrains_mono_medium, FontWeight.W500),
)

private fun sans(size: Float, line: Float, weight: FontWeight, spacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = ArgusSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing)

/**
 * Scala Material 3 con i valori della tavola. Ruoli non nella tavola (display, headlineLarge, bodySmall...)
 * sono derivati dalla stessa famiglia: nessuna schermata li usa.
 */
val ArgusType = Typography(
    displayLarge = sans(57f, 64f, FontWeight.W400),
    displayMedium = sans(45f, 52f, FontWeight.W400),
    displaySmall = sans(36f, 44f, FontWeight.W400),
    headlineLarge = sans(32f, 40f, FontWeight.W700),
    headlineMedium = sans(28f, 36f, FontWeight.W700, (-0.2f).sp),
    headlineSmall = sans(26f, 34f, FontWeight.W700),
    titleLarge = sans(24f, 32f, FontWeight.W700),
    titleMedium = sans(22f, 28f, FontWeight.W600),
    titleSmall = sans(16f, 22f, FontWeight.W600),
    labelLarge = sans(15f, 20f, FontWeight.W600),
    labelMedium = sans(12f, 16f, FontWeight.W600),
    labelSmall = sans(11f, 16f, FontWeight.W700, 0.08.em),
    bodyLarge = sans(16f, 24f, FontWeight.W400),
    bodyMedium = sans(14f, 20f, FontWeight.W400),
    bodySmall = sans(13f, 18f, FontWeight.W400),
)

/** `code` 13/20 · 400. Comandi shell, endpoint, modelli. Il colore si imposta a mano (`ArgusTheme.semantic.codeText`). */
val ArgusCode: TextStyle = TextStyle(
    fontFamily = ArgusMono,
    fontWeight = FontWeight.W400,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)

/** `codeSmall` 12,5/19 · 400. Dettagli tecnici del log, identificatori. */
val ArgusCodeSmall: TextStyle = TextStyle(
    fontFamily = ArgusMono,
    fontWeight = FontWeight.W400,
    fontSize = 12.5f.sp,
    lineHeight = 19.sp,
)
