package dev.argus.ui.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colore dei testi secondari: orari dei messaggi, «ultimo scatto», versione, etichette del log.
 *
 * Mai `outline`: è un token per bordi e divisori e sui fondi scuri dell'app ha un contrasto di circa
 * 1,6:1, illeggibile come testo. `onSurfaceVariant` tiene il rapporto sopra 4,5:1 su `background`,
 * `surface` e `surfaceVariant` in entrambi i temi (verificato da `TextContrastTest`).
 */
fun ColorScheme.secondaryText(): Color = onSurfaceVariant

/**
 * Opacità delle righe «rumore» (soppressioni, condizioni non soddisfatte) in Log e Dettaglio.
 * Abbastanza alta da lasciare il testo sopra 4,5:1; la differenza dalle righe normali resta visibile
 * dall'icona e dal colore del riepilogo.
 */
const val ATTENUATED_ROW_ALPHA: Float = 0.85f
