package dev.argus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.iconFor
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// RuleCard e affini (handoff §8). La regola si mostra SEMPRE dai dati (§5.1);
// invarianti di sicurezza: shell integrale/monospace/scrollabile (§5.3),
// generativa => GenerativeTag + CloudTag ovunque (§5.4).
// =============================================================================

/**
 * Blocco comando shell — invariante di sicurezza §5.3/§7.3.
 * Superficie scura recessa, label UID-2000 in ambra, comando in monospace
 * `codeText` con scroll orizzontale: MAI a capo, MAI troncato (nessun maxLines).
 */
@Composable
fun ShellCommandBlock(cmd: String, modifier: Modifier = Modifier) {
    val semantic = LocalArgusSemantic.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, semantic.cloud.fg.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = semantic.cloud.fg, modifier = Modifier.size(16.dp))
            Text(
                "esegue comandi con privilegi shell (UID 2000)",
                color = semantic.cloud.fg,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // Integrale: softWrap=false + horizontalScroll, senza maxLines/ellipsis.
        Text(
            cmd,
            color = semantic.codeText,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/** Chip outline "conferma live" — azione del catalogo sempre-conferma (spec §10.2). */
@Composable
private fun LiveConfirmChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Text("conferma live", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Riga azione dell'"ALLORA" (vista estesa): icona + label, badge generativa e
 * chip conferma-live quando pertinenti, e sotto il pannello shell se `isShell`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRowItem(row: ActionRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                iconFor(row.iconKey),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(row.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    if (row.isGenerative) GenerativeTag()
                    if (row.requiresLiveConfirm) LiveConfirmChip()
                }
                row.detail?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (row.isShell && row.shellCommand != null) {
            ShellCommandBlock(row.shellCommand)
        }
    }
}

/** Chip azione compatta (chat/lista): viola se generativa, altrimenti outline. */
@Composable
private fun ActionChip(row: ActionRow, modifier: Modifier = Modifier) {
    val generative = if (row.isGenerative) LocalArgusSemantic.current.generative else null
    val base = modifier.clip(RoundedCornerShape(20.dp))
    val shaped = if (generative != null) {
        base.background(generative.bg)
    } else {
        base.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    }
    val fg = generative?.fg ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = shaped.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(iconFor(row.iconKey), contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(row.label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/** Sezione etichettata (QUANDO/SOLO SE/ALLORA) = label uppercase + card surface/1 (§7.3). */
@Composable
private fun RuleSection(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(Modifier.padding(14.dp)) { content() }
        }
    }
}

@Composable
private fun TriggerLine(rule: RuleRender) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            iconFor(rule.triggerIconKey),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(rule.triggerLine, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerativeHeader(rule: RuleRender) {
    if (!rule.isGenerative) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GenerativeTag()
        CloudTag()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactRuleCard(rule: RuleRender, modifier: Modifier, showGenerativeHeader: Boolean) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showGenerativeHeader) GenerativeHeader(rule)
            TriggerLine(rule)
            if (rule.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rule.actions.forEach { ActionChip(it) }
                }
            }
        }
    }
}

@Composable
private fun ExtendedRuleCard(rule: RuleRender, modifier: Modifier, showGenerativeHeader: Boolean) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showGenerativeHeader) GenerativeHeader(rule)
        RuleSection("QUANDO") { TriggerLine(rule) }
        if (rule.conditionLines.isNotEmpty()) {
            RuleSection("SOLO SE") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Rounded.FilterAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        rule.conditionLines.forEach {
                            Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        RuleSection("ALLORA") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rule.actions.forEach { ActionRowItem(it) }
            }
        }
    }
}

/**
 * Card regola: variante `compact` (chat/lista) = trigger + chip azioni + badge;
 * variante estesa (dettaglio) = blocchi QUANDO/SOLO SE/ALLORA. La verità visiva
 * è SEMPRE `rule` (RuleRender), mai la prosa LLM (§5.1).
 *
 * [showGenerativeHeader] = false sopprime la coppia GenerativeTag+CloudTag interna:
 * il Dettaglio la rende già nella riga badge dell'header (§5.4), quindi la disattiva
 * qui per non duplicarla (design §7.3). Ogni altro chiamante usa il default (true).
 */
@Composable
fun RuleCard(
    rule: RuleRender,
    compact: Boolean,
    modifier: Modifier = Modifier,
    showGenerativeHeader: Boolean = true,
) {
    if (compact) {
        CompactRuleCard(rule, modifier, showGenerativeHeader)
    } else {
        ExtendedRuleCard(rule, modifier, showGenerativeHeader)
    }
}

// ---------------------------------------------------------------------------------------------
// Preview — dati finti inline (i Fixtures centralizzati arrivano in Unit F).
// ---------------------------------------------------------------------------------------------

private val previewDeterministic = RuleRender(
    triggerLine = "Quando: ogni giorno alle 23:00 (Europe/Rome)",
    triggerIconKey = "time",
    conditionLines = listOf("Solo se Wi-Fi connesso (casa)"),
    actions = listOf(
        ActionRow("wifi_off", "Disattiva Wi-Fi", null, false, null, false, false),
        ActionRow("dnd", "Attiva Non disturbare (totale)", null, false, null, false, false),
    ),
    isGenerative = false,
    privacyNote = null,
)

private val previewGenerative = RuleRender(
    triggerLine = "Quando: notifica WhatsApp da \"Moglie\" (chat 1:1)",
    triggerIconKey = "notification",
    conditionLines = listOf("Solo tra le 18:00 e le 22:00 (Europe/Rome)"),
    actions = listOf(
        ActionRow(
            iconKey = "generative",
            label = "Rispondi con l'AI",
            detail = "Obiettivo: rispondere nel tono concordato · tool: whatsapp_reply",
            isShell = false,
            shellCommand = null,
            isGenerative = true,
            requiresLiveConfirm = false,
        ),
    ),
    isGenerative = true,
    privacyNote = "Il testo delle notifiche verrà inviato a Hermes e ai provider cloud per generare la risposta.",
)

private val previewShell = RuleRender(
    triggerLine = "Quando: collegato all'alimentazione",
    triggerIconKey = "connectivity",
    conditionLines = emptyList(),
    actions = listOf(
        ActionRow(
            iconKey = "shell",
            label = "Esegui comando shell",
            detail = null,
            isShell = true,
            shellCommand = "settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            isGenerative = false,
            requiresLiveConfirm = true,
        ),
    ),
    isGenerative = false,
    privacyNote = null,
)

@Preview(name = "RuleCard compatta (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun RuleCardCompactPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RuleCard(previewDeterministic, compact = true)
                RuleCard(previewGenerative, compact = true)
            }
        }
    }
}

@Preview(name = "RuleCard estesa + shell (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun RuleCardExtendedPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                RuleCard(previewGenerative, compact = false)
                RuleCard(previewShell, compact = false)
            }
        }
    }
}

@Preview(name = "ShellCommandBlock (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun ShellCommandBlockPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                ShellCommandBlock("pm uninstall --user 0 com.supercell.clashofclans && echo done")
            }
        }
    }
}
