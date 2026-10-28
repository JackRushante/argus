package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.R
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.ChatItem
import dev.argus.ui.model.DraftCardStatus
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.UiWarning
import dev.argus.ui.model.iconFor
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.RuleRenderMapper
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.RolePair

// =============================================================================
// Card bozza della chat (tavola «Chat · bozza proposta»). Invariante 1: dalla chat non si arma. La card ha
// UN solo pulsante, «Rivedi e approva», che apre il Dettaglio; «Arma» esiste solo li'. La regola e' resa
// dai tipi (RuleRender), mai dalla prosa del modello, come una linea del tempo QUANDO / SOLO SE / ALLORA.
// Un ERROR della bozza compare PER INTERO come «Non armabile: <motivo>»; gli avvisi sono solo contati e si
// leggono nel Dettaglio. Una regola generativa porta sempre i due chip «generativa» + «esce verso il cloud».
// =============================================================================

/** Il trigger senza il prefisso «Quando: »/«When: »: l'etichetta QUANDO della linea del tempo lo dice gia'. */
internal fun stripWhenPrefix(line: String): String {
    val prefix = RenderLanguage.entries.map { RuleRenderMapper.whenPrefix(it) }.firstOrNull { line.startsWith(it) }
    return if (prefix != null) line.removePrefix(prefix) else line
}

/** Gli ERROR della bozza, nell'ordine in cui arrivano: ognuno si mostra per intero. */
internal fun blockingIssues(issues: List<UiWarning>): List<UiWarning> = issues.filter { it.severity == Severity.ERROR }

/** Quanti avvisi (WARNING) ha la bozza: nella card si contano soltanto. */
internal fun warningCountOf(issues: List<UiWarning>): Int = issues.count { it.severity == Severity.WARNING }

/** Se la card porta il pulsante «Rivedi e approva»: solo una bozza ancora in attesa e' azionabile. */
internal fun showsReviewButton(status: DraftCardStatus): Boolean = status == DraftCardStatus.PROPOSED

/**
 * Card di una bozza proposta nella conversazione. [onOpenDraft] apre il Dettaglio (approvazione): e' l'UNICO
 * modo di procedere, la chat non arma mai niente.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatDraftCard(
    draft: ChatItem.DraftCard,
    onOpenDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = draft.rule
    val errors = blockingIssues(draft.issues)
    val warnings = warningCountOf(draft.issues)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArgusRadius.Xxl))
            .background(ArgusTheme.surfaces.card)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(ArgusRadius.Xxl))
            .padding(ArgusSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DraftStatusLine(draft.status)
            if (rule.isGenerative) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GenerativeTag()
                    CloudTag()
                }
            }
        }

        RuleTimeline(rule)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
            errors.forEach { NotArmableRow(it.text) }
            if (warnings > 0) WarningsLine(warnings)
            if (showsReviewButton(draft.status)) {
                ArgusButton(
                    text = stringResource(R.string.chat_review_cta),
                    onClick = { onOpenDraft(draft.draftId) },
                    fillWidth = true,
                    trailingIcon = Icons.Rounded.ChevronRight,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Intestazione: stato della bozza
// ---------------------------------------------------------------------------------------------

@Composable
private fun DraftStatusLine(status: DraftCardStatus) {
    val semantic = ArgusTheme.semantic
    val (role, icon, label) = when (status) {
        DraftCardStatus.PROPOSED -> Triple(semantic.pending, Icons.Rounded.Pending, stringResource(R.string.chat_draft_pending))
        DraftCardStatus.APPROVED -> Triple(semantic.armed, Icons.Rounded.Shield, stringResource(R.string.chat_draft_approved))
        DraftCardStatus.REJECTED -> Triple(semantic.disabled, Icons.Rounded.Block, stringResource(R.string.chat_draft_rejected))
        DraftCardStatus.SUPERSEDED -> Triple(semantic.disabled, Icons.Rounded.History, stringResource(R.string.chat_draft_superseded))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = role.fg, modifier = Modifier.size(16.dp))
        Text(label, color = role.fg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.W600)
    }
}

// ---------------------------------------------------------------------------------------------
// Linea del tempo QUANDO / SOLO SE / ALLORA
// ---------------------------------------------------------------------------------------------

@Composable
private fun RuleTimeline(rule: RuleRender) {
    val scheme = MaterialTheme.colorScheme
    val semantic = ArgusTheme.semantic
    val hasConditions = rule.conditionLines.isNotEmpty()
    val hasActions = rule.actions.isNotEmpty()
    Column {
        TimelineStep(
            label = stringResource(R.string.rule_section_when),
            icon = iconFor(rule.triggerIconKey),
            container = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            shape = RoundedCornerShape(ArgusRadius.Sm),
            isLast = !hasConditions && !hasActions,
        ) {
            Text(stripWhenPrefix(rule.triggerLine), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        if (hasConditions) {
            TimelineStep(
                label = stringResource(R.string.rule_section_only_if),
                icon = Icons.Rounded.FilterAlt,
                container = ArgusTheme.surfaces.card2,
                content = scheme.onSurfaceVariant,
                shape = RoundedCornerShape(ArgusRadius.Sm),
                outlined = true,
                isLast = !hasActions,
            ) {
                rule.conditionLines.forEach {
                    Text(it, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (hasActions) {
            val generative = rule.actions.any { it.isGenerative }
            val role: RolePair? = if (generative) semantic.generative else null
            TimelineStep(
                label = stringResource(R.string.rule_section_then),
                icon = if (generative) Icons.Rounded.SmartToy else Icons.Rounded.Bolt,
                container = role?.bg ?: scheme.secondaryContainer,
                content = role?.fg ?: scheme.onSecondaryContainer,
                shape = RoundedCornerShape(percent = 50),
                isLast = true,
            ) {
                rule.actions.forEach { ActionLine(it) }
            }
        }
    }
}

@Composable
private fun ActionLine(action: ActionRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            iconFor(action.iconKey),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            action.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W600,
        )
    }
}

/**
 * Un passo della linea del tempo: contenitore icona 32 dp a sinistra con il filo che lo lega al passo
 * successivo, etichetta in maiuscolo e contenuto a destra.
 */
@Composable
private fun TimelineStep(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    shape: Shape,
    isLast: Boolean,
    outlined: Boolean = false,
    body: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(
                icon = icon,
                container = container,
                content = content,
                size = 32.dp,
                iconSize = 18.dp,
                shape = shape,
                modifier = if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier,
            )
            if (!isLast) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(vertical = ArgusSpacing.Unit)
                        .width(2.dp)
                        .heightIn(min = 8.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else ArgusSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            body()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Piede: ERROR per intero, avvisi contati, unico pulsante
// ---------------------------------------------------------------------------------------------

/** «Non armabile: <motivo>» per intero (invarianti 1-2): la bozza resta rivedibile ma non si arma. */
@Composable
private fun NotArmableRow(reason: String) {
    val role = ArgusTheme.semantic.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArgusRadius.Md))
            .background(role.bg)
            .padding(horizontal = ArgusSpacing.Medium, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        Icon(Icons.Rounded.Error, contentDescription = null, tint = role.fg, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.chat_not_armable, reason),
            color = role.fg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** «1 avviso da leggere nel Dettaglio»: gli avvisi non si elencano nella chat, si contano. */
@Composable
private fun WarningsLine(count: Int) {
    val text = if (count == 1) {
        stringResource(R.string.rda_lc_draft_warning_one)
    } else {
        stringResource(R.string.rda_lc_draft_warning_many, count)
    }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = ArgusTheme.semantic.pending.fg,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.W500,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Anteprime
// ---------------------------------------------------------------------------------------------

private val previewRule = RuleRender(
    triggerLine = "Quando: notifica WhatsApp da \"Moglie\" (chat 1:1)",
    triggerIconKey = "notification",
    conditionLines = listOf("Solo tra le 18:00 e le 22:00 (Europe/Rome)"),
    actions = listOf(
        ActionRow("generative", "Rispondi con l'AI", null, false, null, isGenerative = true, requiresLiveConfirm = false),
    ),
    isGenerative = true,
    privacyNote = null,
)

private val previewPlainRule = RuleRender(
    triggerLine = "Quando: ogni giorno alle 23:00 (Europe/Rome)",
    triggerIconKey = "time",
    conditionLines = emptyList(),
    actions = listOf(
        ActionRow("wifi_off", "Disattiva Wi-Fi", null, false, null, false, false),
        ActionRow("dnd", "Attiva Non disturbare (totale)", null, false, null, false, false),
    ),
    isGenerative = false,
    privacyNote = null,
)

private fun draftOf(
    rule: RuleRender,
    status: DraftCardStatus = DraftCardStatus.PROPOSED,
    vararg issues: UiWarning,
) = ChatItem.DraftCard(draftId = "d1", rule = rule, issues = issues.toList(), status = status)

private val previewWarning = UiWarning(Severity.WARNING, "read_plus_reply", "Legge dati dal dispositivo e li può inviare al mittente.")
private val previewError = UiWarning(
    Severity.ERROR,
    "shell_in_generative",
    "il tool 'shell.run' è vietato nelle regole generative.",
)

@ArgusThemePreviews
@Composable
private fun ChatDraftCardPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Generativa con un avviso: i due chip, la linea del tempo, «1 avviso» e l'unico pulsante.
            ChatDraftCard(draftOf(previewRule, DraftCardStatus.PROPOSED, previewWarning), onOpenDraft = {})
            // Non generativa, senza avvisi.
            ChatDraftCard(draftOf(previewPlainRule), onOpenDraft = {})
        }
    }
}

@ArgusThemePreviews
@Composable
private fun ChatDraftCardNotArmablePreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Un ERROR compare per intero; il pulsante resta (la bozza e' rivedibile, non armabile).
            ChatDraftCard(
                draftOf(previewRule, DraftCardStatus.PROPOSED, previewError, previewWarning, previewWarning.copy(code = "w2")),
                onOpenDraft = {},
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun ChatDraftCardClosedPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChatDraftCard(draftOf(previewRule, DraftCardStatus.APPROVED), onOpenDraft = {})
            ChatDraftCard(draftOf(previewPlainRule, DraftCardStatus.REJECTED), onOpenDraft = {})
            ChatDraftCard(draftOf(previewPlainRule, DraftCardStatus.SUPERSEDED), onOpenDraft = {})
        }
    }
}
