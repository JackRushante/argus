package dev.argus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.components.ArgusActionFooter
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusIconButton
import dev.argus.ui.components.BannerTone
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.CookieShape
import dev.argus.ui.components.HealthBanner
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.TransportConfigurationSheet
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.model.OnboardingCallbacks
import dev.argus.ui.model.OnboardingState
import dev.argus.ui.model.OnboardingStepState
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.ShizukuCapabilityCatalog
import dev.argus.ui.model.ShizukuCapabilityRow
import dev.argus.ui.model.ShizukuRequirement
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.StepKind
import dev.argus.ui.model.StepStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Schermo Onboarding / permessi (handoff §6.6, design §7.6; redesign A, tavole «Onboarding · Cervello» e
// «Onboarding · Shizuku»). Wizard a 6 passi.
// Vincoli resi qui:
//  - Copy Shizuku VERBATIM dalla tabella §9: la sorgente unica è `shizukuOnboardingCopy` (usata anche dalle
//    fixture/host per costruire il body).
//  - WELCOME_PRIVACY = consenso esplicito E11 (CTA "Ho capito, acconsento"). Privacy e Cervello sono OBBLIGATORI
//    (chip «Passo obbligatorio», nessun «Salta»); gli altri passi si possono saltare (chip «Opzionale»).
//  - SHIZUKU = la lista "Richiede / Meglio con / Non richiede Shizuku" resta COMPLETA e nell'ordine di sempre
//    (invariante 12: onesta' sui limiti); lo stato di Shizuku e' nel riquadro sopra la lista.
//  - BATTERY_OEM = riquadro conseguenza ambra (rifiuto → azioni background falliscono).
//  - `canFinish` gate: "Concludi" abilitato solo se WELCOME_PRIVACY e BRAIN_CONFIG sono fatti (host calcola
//    canFinish); disabilitato dice perche', sopra i pulsanti.
//  - Il progresso e' una barra a sei segmenti con il nome del passo; lo stato di ogni passo (fatto, in corso,
//    saltato, bloccato) e' nell'icona, nel colore E nella descrizione per TalkBack.
// Stateless: (state, callbacks). body/ctaLabel arrivano dallo stato (§6.6), il composable non li fabbrica.
// =============================================================================

/**
 * Copy VERBATIM degli stati Shizuku (handoff §9, tabella microcopy). Sorgente unica
 * per costruire body + ctaLabel dello step SHIZUKU (host/fixture). Nessuna parafrasi.
 */
fun shizukuOnboardingCopy(
    status: ShizukuStatus,
    language: RenderLanguage = RenderLanguage.system(),
): Pair<String, String?> = when (status) {
    ShizukuStatus.NOT_INSTALLED ->
        language.pick(
            "Shizuku is not installed. It gives Argus shell privileges (like adb).",
            "Shizuku non è installato. Serve per dare ad Argus i privilegi shell (come adb).",
        ) to language.pick("Download Shizuku", "Scarica Shizuku")
    ShizukuStatus.INSTALLED_NOT_RUNNING ->
        language.pick(
            "Shizuku is installed but not running. Start it (with root it starts on boot).",
            "Shizuku è installato ma non in esecuzione. Avvialo (via root parte da solo al boot).",
        ) to language.pick("Open Shizuku", "Apri Shizuku")
    ShizukuStatus.RUNNING_NOT_AUTHORIZED ->
        language.pick(
            "Shizuku is running but Argus is not authorized.",
            "Shizuku è attivo ma Argus non è autorizzato.",
        ) to language.pick("Request authorization", "Richiedi autorizzazione")
    ShizukuStatus.AUTHORIZED ->
        language.pick(
            "Shizuku active — shell privileges available.",
            "Shizuku attivo — privilegi shell disponibili.",
        ) to null
    ShizukuStatus.DEGRADED_AFTER_REBOOT ->
        language.pick(
            "After the reboot Shizuku is off. Privileged actions are skipped until you re-enable it; everything else works.",
            "Dopo il riavvio Shizuku è spento. Le azioni privilegiate vengono saltate finché non lo riattivi; il resto funziona.",
        ) to language.pick("Re-enable", "Riattiva")
    ShizukuStatus.UNSUPPORTED ->
        language.pick(
            "This Shizuku version is too old for Argus: update it and come back",
            "Questa versione di Shizuku è troppo vecchia per Argus: aggiornala e torna qui",
        ) to language.pick("Update Shizuku", "Aggiorna Shizuku")
}

private fun isMandatory(kind: StepKind): Boolean =
    kind == StepKind.WELCOME_PRIVACY || kind == StepKind.BRAIN_CONFIG

private fun stepIcon(kind: StepKind): ImageVector = when (kind) {
    StepKind.WELCOME_PRIVACY -> Icons.Rounded.PrivacyTip
    StepKind.BRAIN_CONFIG -> Icons.Rounded.Hub
    StepKind.SHIZUKU -> Icons.Rounded.Terminal
    StepKind.NOTIFICATION_ACCESS -> Icons.Rounded.Notifications
    StepKind.BATTERY_OEM -> Icons.Rounded.BatterySaver
    StepKind.BACKGROUND_LOCATION -> Icons.Rounded.MyLocation
}

@Composable
private fun shortName(kind: StepKind): String = when (kind) {
    StepKind.WELCOME_PRIVACY -> stringResource(R.string.onboarding_step_privacy)
    StepKind.BRAIN_CONFIG -> stringResource(R.string.onboarding_step_brain)
    StepKind.SHIZUKU -> stringResource(R.string.onboarding_step_shizuku)
    StepKind.NOTIFICATION_ACCESS -> stringResource(R.string.onboarding_step_notifications)
    StepKind.BATTERY_OEM -> stringResource(R.string.onboarding_step_battery)
    StepKind.BACKGROUND_LOCATION -> stringResource(R.string.onboarding_step_location)
}

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
) {
    val step = state.steps.getOrNull(state.currentIndex) ?: return
    var showBrainSheet by rememberSaveable { mutableStateOf(false) }
    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OnboardingHeader(state.currentIndex, state.steps.size, callbacks::onBack)
            ProgressSteps(state)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = 22.dp, bottom = ArgusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepHero(step.kind)
                StepTitleRow(step)
                StepBody(step)

                // BRAIN_CONFIG: selettore provider ("Scegli il cervello") sotto il testo.
                if (step.kind == StepKind.BRAIN_CONFIG && state.providerChoices.isNotEmpty()) {
                    ProviderRadioList(state.providerChoices, callbacks::onSelectProvider)
                }

                // BRAIN_CONFIG configurato ma non verificato: avviso, mai un blocco (D9).
                if (step.kind == StepKind.BRAIN_CONFIG && state.brainUnverified) {
                    HealthBanner(stringResource(R.string.onboarding_brain_unverified), BannerTone.Warning, Icons.Rounded.WarningAmber)
                }

                // SHIZUKU: lista onesta di cosa richiede/degrada senza Shizuku (task #54).
                if (step.kind == StepKind.SHIZUKU && state.shizukuCapabilities.isNotEmpty()) {
                    ShizukuCapabilityList(state.shizukuCapabilities)
                }

                // BATTERY_OEM: conseguenza reale del rifiuto (§6.6).
                if (step.kind == StepKind.BATTERY_OEM) {
                    HealthBanner(stringResource(R.string.onboarding_battery_consequence), BannerTone.Warning, Icons.Rounded.WarningAmber)
                }

                // BLOCKED: motivo esplicito.
                if (step.status == StepStatus.BLOCKED && step.blockedReason != null) {
                    HealthBanner(step.blockedReason, BannerTone.Blocking, Icons.Rounded.Error)
                }
            }

            OnboardingFooter(
                state = state,
                step = step,
                callbacks = callbacks,
                onStepCta = { kind ->
                    if (kind == StepKind.BRAIN_CONFIG) showBrainSheet = true else callbacks.onStepCta(kind)
                },
            )
        }
    }
    if (showBrainSheet) {
        // La scelta del provider e' nella pagina: il foglio mostra solo i campi del ramo scelto.
        val transport = state.transport ?: state.bridgeUrl?.let {
            TransportUi.CliBridge(url = it, reachable = null, lastLatencyLabel = null, tokenConfigured = state.bridgeTokenConfigured)
        }
        if (transport != null) {
            TransportConfigurationSheet(
                transport = transport,
                onDismiss = { showBrainSheet = false },
                onSaveBridge = callbacks::onSaveBridge,
                onSaveProvider = callbacks::onSaveProviderConfig,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Header + progresso a segmenti con nome
// -----------------------------------------------------------------------------

@Composable
private fun OnboardingHeader(currentIndex: Int, total: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = 4.dp, end = ArgusSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArgusIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.onboarding_header, currentIndex + 1, total),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** Sei segmenti col nome: fatto = verde con spunta, corrente = blu in grassetto, saltato = icona, bloccato = rosso. */
@Composable
private fun ProgressSteps(state: OnboardingState) {
    val semantic = ArgusTheme.semantic
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Unit),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.steps.forEachIndexed { index, s ->
            val current = index == state.currentIndex
            val bar = when {
                s.status == StepStatus.DONE -> semantic.armed.fg
                s.status == StepStatus.BLOCKED -> semantic.error.fg
                current -> scheme.primary
                s.status == StepStatus.SKIPPED -> scheme.onSurfaceVariant
                else -> ArgusTheme.surfaces.field
            }
            val labelColor = when {
                s.status == StepStatus.DONE -> semantic.armed.fg
                s.status == StepStatus.BLOCKED -> semantic.error.fg
                current -> scheme.onSurface
                else -> scheme.onSurfaceVariant
            }
            val glyph: ImageVector? = when (s.status) {
                StepStatus.DONE -> Icons.Rounded.Check
                StepStatus.SKIPPED -> Icons.Rounded.RemoveCircleOutline
                StepStatus.BLOCKED -> Icons.Rounded.Error
                else -> null
            }
            val name = shortName(s.kind)
            val statusText = when (s.status) {
                StepStatus.DONE -> stringResource(R.string.onboarding_status_done)
                StepStatus.IN_PROGRESS -> stringResource(R.string.onboarding_status_in_progress)
                StepStatus.SKIPPED -> stringResource(R.string.onboarding_status_skipped)
                StepStatus.BLOCKED -> stringResource(R.string.onboarding_status_blocked)
                StepStatus.TODO -> null
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {
                        selected = current
                        contentDescription = if (statusText != null) "$name, $statusText" else name
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(bar))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (glyph != null) Icon(glyph, contentDescription = null, tint = labelColor, modifier = Modifier.size(12.dp))
                    Text(
                        name,
                        color = labelColor,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
                        fontWeight = if (current) FontWeight.W700 else FontWeight.W600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Corpo: icona + titolo + chip + testo
// -----------------------------------------------------------------------------

/** Icona grande del passo nel contenitore a nove lobi (cookie) dell'identita' Argus. Decorativa. */
@Composable
private fun StepHero(kind: StepKind) {
    Box(
        modifier = Modifier.size(72.dp).clip(CookieShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(stepIcon(kind), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(30.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepTitleRow(step: OnboardingStepState) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            step.title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (isMandatory(step.kind)) {
            StepChip(stringResource(R.string.onboarding_mandatory), filled = true)
        } else {
            // Gli altri passi si possono saltare: «Opzionale» (Shizuku, notifiche, batteria, posizione).
            StepChip(stringResource(R.string.onboarding_optional), filled = false)
        }
    }
}

/** Chip del passo: «Passo obbligatorio» pieno, «Opzionale» a contorno. */
@Composable
private fun StepChip(text: String, filled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        modifier = Modifier
            .defaultMinSize(minHeight = 26.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(scheme.primaryContainer) else Modifier.border(1.dp, scheme.outline, CircleShape))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (filled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * Il testo guida del passo. Per SHIZUKU e' lo stato corrente di Shizuku (copy §9) e sta in un riquadro: ambra
 * finche' c'e' qualcosa da fare, verde quando Argus e' autorizzato. Per gli altri passi e' un paragrafo.
 */
@Composable
private fun StepBody(step: OnboardingStepState) {
    if (step.kind == StepKind.SHIZUKU) {
        StateBanner(step.body, ok = step.status == StepStatus.DONE)
    } else {
        Text(step.body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Riquadro di stato letto da TalkBack come messaggio: ambra (da fare) o verde (a posto), con icona e testo. */
@Composable
private fun StateBanner(text: String, ok: Boolean) {
    val semantic = ArgusTheme.semantic
    val container = if (ok) semantic.armed.bg else semantic.pending.bg
    val textColor = if (ok) semantic.armed.fg else semantic.onAttentionContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArgusRadius.Lg))
            .background(container)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(
            icon = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            container = if (ok) semantic.armed.fg else semantic.attentionContainer,
            content = if (ok) semantic.armed.bg else semantic.attention,
            size = 34.dp,
            iconSize = 20.dp,
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.W500,
        )
    }
}

// -----------------------------------------------------------------------------
// Scelta del cervello: gruppo di opzioni radio
// -----------------------------------------------------------------------------

@Composable
private fun ProviderRadioList(choices: List<ProviderChoiceUi>, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap),
    ) {
        choices.forEachIndexed { index, choice ->
            val shape = groupShape(groupPositionOf(index, choices.size), outer = 22.dp, inner = ArgusRadius.Xs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(if (choice.selected) MaterialTheme.colorScheme.secondaryContainer else ArgusTheme.surfaces.card)
                    .selectable(selected = choice.selected, role = Role.RadioButton, onClick = { onSelect(choice.id) })
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = choice.selected, onClick = null)
                Text(
                    choice.label,
                    color = if (choice.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (choice.selected) FontWeight.W600 else FontWeight.W400,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// SHIZUKU: "Cosa richiede Shizuku" (task #54) — lista onesta raggruppata per grado di dipendenza. Il grado e il
// testo arrivano dallo stato (ShizukuCapabilityCatalog), il composable non li fabbrica.
// -----------------------------------------------------------------------------

@Composable
private fun requirementHeading(requirement: ShizukuRequirement): String = when (requirement) {
    ShizukuRequirement.REQUIRED -> stringResource(R.string.onboarding_shizuku_required)
    ShizukuRequirement.RECOMMENDED -> stringResource(R.string.onboarding_shizuku_recommended)
    ShizukuRequirement.NOT_REQUIRED -> stringResource(R.string.onboarding_shizuku_not_required)
}

@Composable
private fun requirementSubhead(requirement: ShizukuRequirement): String = when (requirement) {
    ShizukuRequirement.REQUIRED -> stringResource(R.string.onboarding_shizuku_required_sub)
    ShizukuRequirement.RECOMMENDED -> stringResource(R.string.onboarding_shizuku_recommended_sub)
    ShizukuRequirement.NOT_REQUIRED -> stringResource(R.string.onboarding_shizuku_not_required_sub)
}

@Composable
private fun ShizukuCapabilityList(rows: List<ShizukuCapabilityRow>) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Ordine fisso: prima cosa NON puoi fare senza (REQUIRED), poi cosa degrada, poi cosa resta.
        listOf(
            ShizukuRequirement.REQUIRED,
            ShizukuRequirement.RECOMMENDED,
            ShizukuRequirement.NOT_REQUIRED,
        ).forEach { requirement ->
            val group = rows.filter { it.requirement == requirement }
            if (group.isNotEmpty()) ShizukuCapabilityGroup(requirement, group)
        }
    }
}

@Composable
private fun ShizukuCapabilityGroup(requirement: ShizukuRequirement, rows: List<ShizukuCapabilityRow>) {
    val semantic = ArgusTheme.semantic
    // Il grado e' nel colore, nell'icona E nel titolo scritto («Richiede / Meglio con / Non richiede»).
    val (role, headerIcon, itemIcon) = when (requirement) {
        ShizukuRequirement.REQUIRED -> Triple(semantic.error, Icons.Rounded.Lock, Icons.Rounded.Terminal)
        ShizukuRequirement.RECOMMENDED -> Triple(semantic.pending, Icons.Rounded.Bolt, Icons.Rounded.Bolt)
        ShizukuRequirement.NOT_REQUIRED -> Triple(semantic.armed, Icons.Rounded.CheckCircle, Icons.Rounded.Check)
    }
    val count = rows.size + 1
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(groupShape(groupPositionOf(0, count)))
                .background(ArgusTheme.surfaces.card)
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(headerIcon, role.bg, role.fg, size = 36.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    requirementHeading(requirement),
                    modifier = Modifier.semantics { heading() },
                    color = role.fg,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W700),
                )
                Text(
                    requirementSubhead(requirement),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(groupShape(groupPositionOf(index + 1, count)))
                    .background(ArgusTheme.surfaces.card)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBadge(itemIcon, ArgusTheme.surfaces.card2, MaterialTheme.colorScheme.onSurface, size = 32.dp, iconSize = 18.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    if (row.note != null) {
                        Text(row.note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Footer: [Salta?] [CTA]
// -----------------------------------------------------------------------------

@Composable
private fun OnboardingFooter(
    state: OnboardingState,
    step: OnboardingStepState,
    callbacks: OnboardingCallbacks,
    onStepCta: (StepKind) -> Unit,
) {
    val isLast = state.currentIndex == state.steps.lastIndex
    // «Concludi» disabilitato dice perche': Privacy e Cervello restano obbligatori.
    val finishBlocked = isLast && !state.canFinish
    ArgusActionFooter(
        disabledReason = stringResource(R.string.rda_ls_onboarding_finish_blocked).takeIf { finishBlocked },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            // Salta solo per gli step non obbligatori (§6.6: il resto è skippabile). Sull'ultimo
            // passo c'è sempre «Concludi»: Salta sarebbe un doppione.
            if (!isMandatory(step.kind) && !isLast) {
                ArgusButton(
                    text = stringResource(R.string.onboarding_skip),
                    onClick = { callbacks.onSkip(step.kind) },
                    style = ButtonStyle.Outlined,
                    minHeight = 56.dp,
                )
            }
            val ctaIcon = if (step.kind == StepKind.BRAIN_CONFIG) Icons.AutoMirrored.Rounded.ArrowForward else null
            // Primario: azione dello step (ctaLabel) se presente; altrimenti nav "Avanti" o "Concludi" (gate su
            // canFinish, §6.6). Ultimo passo: «Concludi» c'è sempre, accanto all'eventuale CTA, così nessuna CTA
            // ignorata blocca la fine. Cervello non verificato: la CTA (correggi le credenziali) sta accanto ad «Avanti».
            val navAlongsideCta = isLast || (step.kind == StepKind.BRAIN_CONFIG && state.brainUnverified)
            when {
                step.ctaLabel != null && navAlongsideCta -> {
                    ArgusButton(
                        text = step.ctaLabel,
                        onClick = { onStepCta(step.kind) },
                        style = ButtonStyle.Outlined,
                        modifier = Modifier.weight(1f),
                        fillWidth = true,
                        minHeight = 56.dp,
                    )
                    if (isLast) {
                        ArgusButton(
                            text = stringResource(R.string.onboarding_finish),
                            onClick = callbacks::onFinish,
                            enabled = state.canFinish,
                            modifier = Modifier.weight(1f),
                            fillWidth = true,
                            minHeight = 56.dp,
                        )
                    } else {
                        ArgusButton(
                            text = stringResource(R.string.onboarding_next),
                            onClick = callbacks::onNext,
                            modifier = Modifier.weight(1f),
                            fillWidth = true,
                            minHeight = 56.dp,
                        )
                    }
                }
                step.ctaLabel != null -> ArgusButton(
                    text = step.ctaLabel,
                    onClick = { onStepCta(step.kind) },
                    icon = ctaIcon,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                    minHeight = 56.dp,
                )
                isLast -> ArgusButton(
                    text = stringResource(R.string.onboarding_finish),
                    onClick = callbacks::onFinish,
                    enabled = state.canFinish,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                    minHeight = 56.dp,
                )
                else -> ArgusButton(
                    text = stringResource(R.string.onboarding_next),
                    onClick = callbacks::onNext,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                    minHeight = 56.dp,
                )
            }
        }
    }
}

// =============================================================================
// Preview — fixture inline, un'anteprima per stato nei due temi.
// La copy Shizuku è VERBATIM da §9 tramite `shizukuOnboardingCopy`. Nessuna anteprima contiene una chiave.
// =============================================================================

private fun stepsFixture(
    currentKind: StepKind,
    shizuku: ShizukuStatus = ShizukuStatus.RUNNING_NOT_AUTHORIZED,
    done: Set<StepKind> = emptySet(),
    skipped: Set<StepKind> = emptySet(),
): List<OnboardingStepState> {
    val (shizukuBody, shizukuCta) = shizukuOnboardingCopy(shizuku)
    fun statusFor(kind: StepKind) = when {
        kind == currentKind -> StepStatus.IN_PROGRESS
        kind in done -> StepStatus.DONE
        kind in skipped -> StepStatus.SKIPPED
        else -> StepStatus.TODO
    }
    return listOf(
        OnboardingStepState(
            StepKind.WELCOME_PRIVACY, statusFor(StepKind.WELCOME_PRIVACY), "Privacy e consenso",
            "Il testo delle notifiche e ciò che chiedi in chat viaggia verso Hermes (il tuo server) e da lì verso provider cloud (OpenAI/Nous/…). Nulla esce senza una regola che approvi.",
            ctaLabel = "Ho capito, acconsento", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.BRAIN_CONFIG, statusFor(StepKind.BRAIN_CONFIG), "Scegli il cervello",
            "Hermes self-hosted (bridge sul tuo tailnet) oppure un provider diretto con la tua chiave API (BYOK). La chiave viene cifrata con Android Keystore e non sarà più mostrata.",
            ctaLabel = "Configura il cervello", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.SHIZUKU,
            if (shizuku == ShizukuStatus.AUTHORIZED && currentKind != StepKind.SHIZUKU) StepStatus.DONE else statusFor(StepKind.SHIZUKU),
            "Autorizza Shizuku", shizukuBody, ctaLabel = shizukuCta, blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.NOTIFICATION_ACCESS, statusFor(StepKind.NOTIFICATION_ACCESS), "Accesso alle notifiche",
            "Argus legge le notifiche per far scattare le regole (es. WhatsApp da un contatto in whitelist).",
            ctaLabel = "Concedi", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.BATTERY_OEM, statusFor(StepKind.BATTERY_OEM), "Escludi dall'ottimizzazione batteria",
            "La gestione energetica del produttore può sospendere Argus in background. Escludilo per far girare le azioni pianificate e le risposte AI.",
            ctaLabel = "Apri impostazioni", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.BACKGROUND_LOCATION, statusFor(StepKind.BACKGROUND_LOCATION), "Posizione in background",
            "Serve solo se creerai regole geofence. Puoi concederla più avanti, quando la prima regola di posizione lo richiede.",
            ctaLabel = "Concedi", blockedReason = null,
        ),
    )
}

private fun indexOfKind(kind: StepKind): Int = StepKind.entries.indexOf(kind)

private object NoopOnboardingCallbacks : OnboardingCallbacks {
    override fun onStepCta(kind: StepKind) {}
    override fun onSkip(kind: StepKind) {}
    override fun onNext() {}
    override fun onBack() {}
    override fun onFinish() {}
}

private val previewChoices = listOf(
    ProviderChoiceUi("hermes", "Hermes (self-hosted)", selected = true),
    ProviderChoiceUi("openai", "OpenAI", selected = false),
    ProviderChoiceUi("anthropic", "Anthropic", selected = false),
    ProviderChoiceUi("gemini", "Google Gemini", selected = false),
    ProviderChoiceUi("openrouter", "OpenRouter", selected = false),
    ProviderChoiceUi("custom_openai_compat", "Custom (OpenAI-compat)", selected = false),
)

@Composable
private fun PreviewFor(
    kind: StepKind,
    shizuku: ShizukuStatus = ShizukuStatus.RUNNING_NOT_AUTHORIZED,
    done: Set<StepKind> = emptySet(),
    skipped: Set<StepKind> = emptySet(),
    canFinish: Boolean = true,
    brainUnverified: Boolean = false,
    steps: List<OnboardingStepState> = stepsFixture(kind, shizuku, done, skipped),
) {
    ArgusPreviewSurface {
        OnboardingScreen(
            OnboardingState(
                steps = steps,
                currentIndex = indexOfKind(kind),
                canFinish = canFinish,
                providerChoices = previewChoices,
                shizukuCapabilities = ShizukuCapabilityCatalog.rows(),
                brainUnverified = brainUnverified,
                transport = TransportUi.CliBridge(url = "https://hermes.example", reachable = null, lastLatencyLabel = null),
            ),
            NoopOnboardingCallbacks,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun OnboardingPrivacyPreview() {
    // WELCOME_PRIVACY step corrente: passo obbligatorio, nessun «Salta», CTA di consenso.
    PreviewFor(StepKind.WELCOME_PRIVACY, canFinish = false)
}

@ArgusThemePreviews
@Composable
private fun OnboardingBrainPreview() {
    PreviewFor(StepKind.BRAIN_CONFIG, done = setOf(StepKind.WELCOME_PRIVACY), canFinish = false)
}

@ArgusThemePreviews
@Composable
private fun OnboardingBrainUnverifiedPreview() {
    PreviewFor(StepKind.BRAIN_CONFIG, done = setOf(StepKind.WELCOME_PRIVACY), canFinish = false, brainUnverified = true)
}

@ArgusThemePreviews
@Composable
private fun OnboardingShizukuNotAuthorizedPreview() {
    PreviewFor(StepKind.SHIZUKU, ShizukuStatus.RUNNING_NOT_AUTHORIZED, done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG))
}

@ArgusThemePreviews
@Composable
private fun OnboardingShizukuUnsupportedPreview() {
    PreviewFor(StepKind.SHIZUKU, ShizukuStatus.UNSUPPORTED, done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG))
}

@ArgusThemePreviews
@Composable
private fun OnboardingShizukuAuthorizedPreview() {
    PreviewFor(StepKind.SHIZUKU, ShizukuStatus.AUTHORIZED, done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG, StepKind.SHIZUKU))
}

@ArgusThemePreviews
@Composable
private fun OnboardingNotificationsPreview() {
    PreviewFor(
        StepKind.NOTIFICATION_ACCESS,
        done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG),
        skipped = setOf(StepKind.SHIZUKU),
    )
}

@ArgusThemePreviews
@Composable
private fun OnboardingBatteryPreview() {
    PreviewFor(StepKind.BATTERY_OEM, done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG, StepKind.NOTIFICATION_ACCESS))
}

@ArgusThemePreviews
@Composable
private fun OnboardingLocationLastStepPreview() {
    PreviewFor(
        StepKind.BACKGROUND_LOCATION,
        done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG, StepKind.SHIZUKU, StepKind.NOTIFICATION_ACCESS, StepKind.BATTERY_OEM),
    )
}

/** Ultimo passo con «Concludi» bloccato: il motivo e' scritto sopra i pulsanti. */
@ArgusThemePreviews
@Composable
private fun OnboardingLocationFinishBlockedPreview() {
    PreviewFor(StepKind.BACKGROUND_LOCATION, canFinish = false, skipped = setOf(StepKind.SHIZUKU))
}

/** Passo bloccato: il motivo sta in un riquadro rosso e il passo e' rosso nella barra. */
@ArgusThemePreviews
@Composable
private fun OnboardingBlockedStepPreview() {
    val base = stepsFixture(StepKind.NOTIFICATION_ACCESS, done = setOf(StepKind.WELCOME_PRIVACY, StepKind.BRAIN_CONFIG))
    PreviewFor(
        StepKind.NOTIFICATION_ACCESS,
        steps = base.map {
            if (it.kind == StepKind.NOTIFICATION_ACCESS) {
                it.copy(
                    status = StepStatus.BLOCKED,
                    blockedReason = "Android blocca questa impostazione per le app installate da APK: consentila dalle impostazioni dell'app.",
                )
            } else {
                it
            }
        },
    )
}
