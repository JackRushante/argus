package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.UiWarning
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Banner e superfici di stato (handoff §8). ERROR = rosso bloccante, warning =
// ambra; copy salute verbatim (§9). Colori solo da LocalArgusSemantic/tema.
// =============================================================================

/**
 * Banner di warning sopra la fold (§5.2/§7.3). `ERROR` = rosso bloccante con
 * bordo marcato; `WARNING`/privacy = ambra. La sfumatura privacy (icona
 * `privacy_tip`) è dedotta dal `code` perché `Severity` ha solo ERROR/WARNING.
 */
@Composable
fun WarningBanner(w: UiWarning, modifier: Modifier = Modifier) {
    val semantic = LocalArgusSemantic.current
    val isError = w.severity == Severity.ERROR
    val isPrivacy = w.code == "read_plus_reply" || w.code.contains("privacy", ignoreCase = true)
    val role = if (isError) semantic.error else semantic.pending
    val icon = when {
        isError -> Icons.Rounded.Error
        isPrivacy -> Icons.Rounded.PrivacyTip
        else -> Icons.Rounded.WarningAmber
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(role.bg)
            .border(
                width = if (isError) 1.5.dp else 1.dp,
                color = role.fg.copy(alpha = if (isError) 0.55f else 0.30f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = role.fg, modifier = Modifier.size(20.dp))
        Text(w.text, color = role.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Barra salute persistente e tappabile (§6.2): mappa ogni valore alla sua copy
 * (verbatim §9). `NONE` non rende nulla. Tap -> Sistema (via [onClick]).
 */
@Composable
fun EngineBannerBar(banner: EngineBanner, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    if (banner == EngineBanner.NONE) return
    val semantic = LocalArgusSemantic.current
    val (role, text) = when (banner) {
        EngineBanner.SHIZUKU_DOWN ->
            semantic.pending to "Shizuku non attivo — azioni shell in pausa"
        EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT ->
            semantic.pending to "Dopo il riavvio Shizuku è spento — le azioni shell sono in coda"
        EngineBanner.BATTERY_NOT_EXEMPT ->
            semantic.pending to "Ottimizzazione batteria attiva — le azioni in background possono fallire"
        EngineBanner.BRAIN_UNREACHABLE ->
            semantic.error to "Hermes irraggiungibile — la chat non può proporre regole"
        EngineBanner.NONE -> return
    }
    val icon = if (banner == EngineBanner.BRAIN_UNREACHABLE) Icons.Rounded.CloudOff else Icons.Rounded.WarningAmber
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(role.bg)
            .clickable { onClick() }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = role.fg, modifier = Modifier.size(20.dp))
        Text(text, color = role.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 4.dp))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = role.fg, modifier = Modifier.size(20.dp))
    }
}

/**
 * Attesa one-shot del Brain (§4): spinner + "Argus sta pensando… (Ns)" +
 * range atteso + Annulla. Nessuno streaming: tempo trascorso onesto e visibile.
 */
@Composable
fun LatencyIndicator(
    elapsedSec: Int,
    expectedRangeLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Argus sta pensando… ($elapsedSec s)",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                expectedRangeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Annulla")
        }
    }
}

/** Stato vuoto riusabile (§8): icona grande, titolo, corpo e CTA opzionale. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    ctaLabel: String? = null,
    onCta: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (ctaLabel != null) {
            Button(onClick = onCta, modifier = Modifier.heightIn(min = 48.dp)) { Text(ctaLabel) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Preview — dati finti inline.
// ---------------------------------------------------------------------------------------------

@Preview(name = "WarningBanner (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun WarningBannerPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WarningBanner(
                    UiWarning(
                        Severity.ERROR,
                        "shell_in_generative",
                        "Errore di validazione — arm bloccato: 'shell.run' vietato nelle regole generative.",
                    ),
                )
                WarningBanner(
                    UiWarning(
                        Severity.WARNING,
                        "read_plus_reply",
                        "Legge dati dal dispositivo e può inviarli al mittente. Possibile esfiltrazione di contesto.",
                    ),
                )
                WarningBanner(
                    UiWarning(
                        Severity.WARNING,
                        "cooldown_short",
                        "Cooldown molto breve: la regola potrebbe scattare più volte di seguito.",
                    ),
                )
            }
        }
    }
}

@Preview(name = "EngineBannerBar (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun EngineBannerBarPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EngineBannerBar(EngineBanner.SHIZUKU_DOWN)
                EngineBannerBar(EngineBanner.BATTERY_NOT_EXEMPT)
                EngineBannerBar(EngineBanner.BRAIN_UNREACHABLE)
            }
        }
    }
}

@Preview(name = "LatencyIndicator (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun LatencyIndicatorPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                LatencyIndicator(elapsedSec = 12, expectedRangeLabel = "di solito 10-30 s", onCancel = {})
            }
        }
    }
}

@Preview(name = "EmptyState (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun EmptyStatePreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            EmptyState(
                icon = Icons.Rounded.ChatBubbleOutline,
                title = "Nessuna automazione",
                body = "Chiedila in chat: descrivi cosa vuoi che Argus faccia e proporrà una regola.",
                ctaLabel = "Vai in chat",
                onCta = {},
            )
        }
    }
}
