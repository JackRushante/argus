package dev.argus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.R
import dev.argus.ui.components.WarningBanner
import dev.argus.ui.components.BridgeConfigurationDialog
import dev.argus.ui.components.ProviderConfigurationDialog
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
import dev.argus.ui.model.UiWarning
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Schermo Onboarding / permessi (handoff §6.6, design §7.6). Wizard a 6 step.
// Vincoli resi qui:
//  - Copy Shizuku VERBATIM dalla tabella §9: la sorgente unica è
//    `shizukuOnboardingCopy` (usata anche dalle fixture/host per costruire il body).
//  - WELCOME_PRIVACY = consenso esplicito E11 (CTA "Ho capito, acconsento").
//  - BATTERY_OEM = box conseguenza ambra (rifiuto → azioni background falliscono).
//  - BACKGROUND_LOCATION = opzionale (chip "Opzionale" + step saltabile).
//  - `canFinish` gate: "Concludi" abilitato solo se WELCOME_PRIVACY e BRAIN_CONFIG
//    sono fatti (host calcola canFinish).
// Stateless: (state, callbacks). body/ctaLabel arrivano dallo stato (§6.6), il
// composable non li fabbrica: per gli step con copy vincolata (Shizuku) la verità
// è §9, resa verbatim in `shizukuOnboardingCopy` e nelle preview.
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
            "After the reboot Shizuku is off. Shell actions are queued; everything else works.",
            "Dopo il riavvio Shizuku è spento. Le azioni shell sono in coda; il resto funziona.",
        ) to language.pick("Re-enable", "Riattiva")
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
    var showBridgeEditor by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OnboardingHeader(state.currentIndex, state.steps.size, callbacks::onBack)
            ProgressSegments(state)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                StepHeadline(step)

                // BRAIN_CONFIG: selettore provider ("Scegli il cervello") sopra la checklist.
                if (step.kind == StepKind.BRAIN_CONFIG && state.providerChoices.isNotEmpty()) {
                    ProviderSelector(state.providerChoices, callbacks::onSelectProvider)
                }

                // SHIZUKU: lista onesta di cosa richiede/degrada senza Shizuku (task #54).
                if (step.kind == StepKind.SHIZUKU && state.shizukuCapabilities.isNotEmpty()) {
                    ShizukuCapabilityList(state.shizukuCapabilities)
                }

                // BATTERY_OEM: conseguenza reale del rifiuto, sopra la checklist (§6.6).
                if (step.kind == StepKind.BATTERY_OEM) {
                    WarningBanner(
                        UiWarning(
                            Severity.WARNING,
                            "battery_consequence",
                            stringResource(R.string.onboarding_battery_consequence),
                        ),
                    )
                }

                // BLOCKED: motivo esplicito.
                if (step.status == StepStatus.BLOCKED && step.blockedReason != null) {
                    WarningBanner(UiWarning(Severity.ERROR, "step_blocked", step.blockedReason))
                }

                Spacer(Modifier.height(2.dp))
                Checklist(state)
                Spacer(Modifier.height(8.dp))
            }

            OnboardingFooter(
                state = state,
                step = step,
                callbacks = callbacks,
                onStepCta = { kind ->
                    if (kind == StepKind.BRAIN_CONFIG) showBridgeEditor = true
                    else callbacks.onStepCta(kind)
                },
            )
        }
    }
    if (showBridgeEditor) {
        when (val transport = state.transport) {
            is TransportUi.CliBridge -> BridgeConfigurationDialog(
                initialUrl = transport.url,
                tokenConfigured = transport.tokenConfigured,
                onDismiss = { showBridgeEditor = false },
                onSave = callbacks::onSaveBridge,
            )
            is TransportUi.DirectProvider -> ProviderConfigurationDialog(
                provider = transport,
                onDismiss = { showBridgeEditor = false },
                onSave = { baseUrl, model, apiKey ->
                    callbacks.onSaveProviderConfig(transport.providerId, baseUrl, model, apiKey)
                },
            )
            // Fallback legacy: stato senza transport (fixture vecchie). Il VM reale emette sempre un ramo.
            null -> if (state.bridgeUrl != null) {
                BridgeConfigurationDialog(
                    initialUrl = state.bridgeUrl,
                    tokenConfigured = state.bridgeTokenConfigured,
                    onDismiss = { showBridgeEditor = false },
                    onSave = callbacks::onSaveBridge,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderSelector(choices: List<ProviderChoiceUi>, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice.selected,
                onClick = { onSelect(choice.id) },
                label = { Text(choice.label) },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// SHIZUKU: "Cosa richiede Shizuku" (task #54) — lista onesta raggruppata per grado
// di dipendenza. Il grado e il testo arrivano dallo stato (ShizukuCapabilityCatalog),
// il composable non li fabbrica.
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
    val semantic = LocalArgusSemantic.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Ordine fisso: prima cosa NON puoi fare senza (REQUIRED), poi cosa degrada, poi cosa resta.
        listOf(
            ShizukuRequirement.REQUIRED,
            ShizukuRequirement.RECOMMENDED,
            ShizukuRequirement.NOT_REQUIRED,
        ).forEach { requirement ->
            val group = rows.filter { it.requirement == requirement }
            if (group.isNotEmpty()) {
                ShizukuCapabilityGroup(requirement, group, semantic)
            }
        }
    }
}

@Composable
private fun ShizukuCapabilityGroup(
    requirement: ShizukuRequirement,
    rows: List<ShizukuCapabilityRow>,
    semantic: dev.argus.ui.theme.ArgusSemantic,
) {
    val accent = when (requirement) {
        ShizukuRequirement.REQUIRED -> semantic.error.fg
        ShizukuRequirement.RECOMMENDED -> semantic.pending.fg
        ShizukuRequirement.NOT_REQUIRED -> semantic.armed.fg
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            requirementHeading(requirement),
            color = accent,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            requirementSubhead(requirement),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent),
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        row.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (row.note != null) {
                        Text(
                            row.note,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Header + progresso segmentato
// -----------------------------------------------------------------------------

@Composable
private fun OnboardingHeader(currentIndex: Int, total: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
        Text(
            stringResource(R.string.onboarding_header, currentIndex + 1, total),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Barra segmentata: done=verde, corrente=blu, saltato=muted, da fare=grigio (§7.6). */
@Composable
private fun ProgressSegments(state: OnboardingState) {
    val semantic = LocalArgusSemantic.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        state.steps.forEachIndexed { index, s ->
            val color = when {
                s.status == StepStatus.DONE -> semantic.armed.fg
                index == state.currentIndex -> MaterialTheme.colorScheme.primary
                s.status == StepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Corpo: icona + titolo + body (+ chip Opzionale)
// -----------------------------------------------------------------------------

@Composable
private fun StepHeadline(step: OnboardingStepState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(stepIcon(step.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(step.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            // BACKGROUND_LOCATION: presentato come opzionale/futuro (§6.6).
            if (step.kind == StepKind.BACKGROUND_LOCATION) OptionalChip()
        }
        Text(step.body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OptionalChip() {
    Text(
        stringResource(R.string.onboarding_optional),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// -----------------------------------------------------------------------------
// Checklist di tutti gli step
// -----------------------------------------------------------------------------

@Composable
private fun Checklist(state: OnboardingState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(vertical = 4.dp),
    ) {
        state.steps.forEachIndexed { index, s ->
            ChecklistRow(s, isCurrent = index == state.currentIndex)
        }
    }
}

@Composable
private fun ChecklistRow(step: OnboardingStepState, isCurrent: Boolean) {
    val semantic = LocalArgusSemantic.current
    val (glyph, glyphColor) = when (step.status) {
        StepStatus.DONE -> Icons.Rounded.CheckCircle to semantic.armed.fg
        StepStatus.IN_PROGRESS -> Icons.Rounded.RadioButtonChecked to MaterialTheme.colorScheme.primary
        StepStatus.BLOCKED -> Icons.Rounded.Error to semantic.error.fg
        StepStatus.SKIPPED -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        StepStatus.TODO -> Icons.Rounded.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val (rightText, rightColor) = when (step.status) {
        StepStatus.DONE -> stringResource(R.string.onboarding_status_done) to semantic.armed.fg
        StepStatus.IN_PROGRESS -> stringResource(R.string.onboarding_status_in_progress) to MaterialTheme.colorScheme.primary
        StepStatus.SKIPPED -> stringResource(R.string.onboarding_status_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
        StepStatus.BLOCKED -> (step.blockedReason ?: stringResource(R.string.onboarding_status_blocked)) to semantic.error.fg
        StepStatus.TODO -> if (step.kind == StepKind.BACKGROUND_LOCATION) stringResource(R.string.onboarding_optional) to MaterialTheme.colorScheme.onSurfaceVariant else null to Color.Unspecified
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 13.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(glyph, contentDescription = null, tint = glyphColor, modifier = Modifier.size(20.dp))
        Text(
            shortName(step.kind),
            color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (isCurrent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (rightText != null) {
            Text(rightText, color = rightColor, style = MaterialTheme.typography.bodyMedium)
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
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isMandatory(step.kind)) {
                    Text(stringResource(R.string.onboarding_mandatory), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Salta solo per gli step non obbligatori (§6.6: il resto è skippabile).
                    if (!isMandatory(step.kind)) {
                        OutlinedButton(onClick = { callbacks.onSkip(step.kind) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.onboarding_skip))
                        }
                    }
                    // Primario: azione dello step (ctaLabel) se presente; altrimenti nav
                    // "Avanti" o "Concludi" (gate su canFinish, §6.6).
                    when {
                        step.ctaLabel != null -> Button(
                            onClick = { onStepCta(step.kind) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(step.ctaLabel) }
                        isLast -> Button(
                            onClick = callbacks::onFinish,
                            enabled = state.canFinish,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.onboarding_finish)) }
                        else -> Button(
                            onClick = callbacks::onNext,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.onboarding_next)) }
                    }
                }
            }
        }
    }
}

// =============================================================================
// Preview — fixture inline (i Fixtures centralizzati arrivano in Unit F).
// La copy Shizuku è VERBATIM da §9 tramite `shizukuOnboardingCopy`.
// =============================================================================

private fun stepsFixture(currentKind: StepKind, shizuku: ShizukuStatus = ShizukuStatus.RUNNING_NOT_AUTHORIZED): List<OnboardingStepState> {
    val (shizukuBody, shizukuCta) = shizukuOnboardingCopy(shizuku)
    return listOf(
        OnboardingStepState(
            StepKind.WELCOME_PRIVACY, StepStatus.DONE, "Privacy e consenso",
            "Il testo delle notifiche e ciò che chiedi in chat viaggia verso Hermes (il tuo server) e da lì verso provider cloud (OpenAI/Nous/…). Nulla esce senza una regola che approvi.",
            ctaLabel = "Ho capito, acconsento", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.BRAIN_CONFIG, StepStatus.DONE, "Collega Hermes",
            "Indirizzo del bridge Hermes sul tuo tailnet. Precompilato: puoi testarlo subito.",
            ctaLabel = "Test connessione", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.SHIZUKU,
            if (currentKind == StepKind.SHIZUKU) StepStatus.IN_PROGRESS else StepStatus.TODO,
            "Autorizza Shizuku", shizukuBody, ctaLabel = shizukuCta, blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.NOTIFICATION_ACCESS, StepStatus.TODO, "Accesso alle notifiche",
            "Argus legge le notifiche per far scattare le regole (es. WhatsApp da un contatto in whitelist).",
            ctaLabel = "Concedi", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.BATTERY_OEM,
            if (currentKind == StepKind.BATTERY_OEM) StepStatus.IN_PROGRESS else StepStatus.TODO,
            "Escludi dall'ottimizzazione batteria",
            "OxygenOS può sospendere Argus in background. Escludilo per far girare le azioni pianificate e le risposte AI.",
            ctaLabel = "Apri impostazioni", blockedReason = null,
        ),
        OnboardingStepState(
            StepKind.BACKGROUND_LOCATION,
            if (currentKind == StepKind.BACKGROUND_LOCATION) StepStatus.IN_PROGRESS else StepStatus.TODO,
            "Posizione in background",
            "Serve solo se creerai regole geofence. Puoi concederla più avanti, quando la prima regola di posizione lo richiede.",
            ctaLabel = "Concedi", blockedReason = null,
        ),
    )
}

private fun indexOfKind(kind: StepKind): Int = when (kind) {
    StepKind.WELCOME_PRIVACY -> 0
    StepKind.BRAIN_CONFIG -> 1
    StepKind.SHIZUKU -> 2
    StepKind.NOTIFICATION_ACCESS -> 3
    StepKind.BATTERY_OEM -> 4
    StepKind.BACKGROUND_LOCATION -> 5
}

private object NoopOnboardingCallbacks : OnboardingCallbacks {
    override fun onStepCta(kind: StepKind) {}
    override fun onSkip(kind: StepKind) {}
    override fun onNext() {}
    override fun onBack() {}
    override fun onFinish() {}
}

@Composable
private fun PreviewFor(kind: StepKind, shizuku: ShizukuStatus = ShizukuStatus.RUNNING_NOT_AUTHORIZED) {
    ArgusTheme {
        OnboardingScreen(
            OnboardingState(
                steps = stepsFixture(kind, shizuku),
                currentIndex = indexOfKind(kind),
                canFinish = true,
                shizukuCapabilities = ShizukuCapabilityCatalog.rows(),
            ),
            NoopOnboardingCallbacks,
        )
    }
}

@Preview(name = "Onboarding · Privacy (consenso)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun OnboardingPrivacyPreview() {
    // WELCOME_PRIVACY step corrente (mostra "Passo obbligatorio", no Salta, CTA consenso).
    ArgusTheme {
        OnboardingScreen(
            OnboardingState(steps = stepsFixture(StepKind.WELCOME_PRIVACY).mapIndexed { i, s ->
                if (i == 0) s.copy(status = StepStatus.IN_PROGRESS) else s.copy(status = StepStatus.TODO)
            }, currentIndex = 0, canFinish = false),
            NoopOnboardingCallbacks,
        )
    }
}

@Preview(name = "Onboarding · Shizuku RUNNING_NOT_AUTHORIZED", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun OnboardingShizukuPreview() {
    PreviewFor(StepKind.SHIZUKU, ShizukuStatus.RUNNING_NOT_AUTHORIZED)
}

@Preview(name = "Onboarding · Scegli il cervello (selettore provider)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun OnboardingBrainConfigPreview() {
    val choices = listOf(
        ProviderChoiceUi("hermes", "Hermes (self-hosted)", selected = false),
        ProviderChoiceUi("openai", "OpenAI", selected = true),
        ProviderChoiceUi("anthropic", "Anthropic", selected = false),
        ProviderChoiceUi("gemini", "Google Gemini", selected = false),
        ProviderChoiceUi("openrouter", "OpenRouter", selected = false),
        ProviderChoiceUi("custom_openai_compat", "Custom (OpenAI-compat)", selected = false),
    )
    ArgusTheme {
        OnboardingScreen(
            OnboardingState(
                steps = stepsFixture(StepKind.BRAIN_CONFIG).mapIndexed { i, s ->
                    if (i == 1) {
                        s.copy(
                            status = StepStatus.IN_PROGRESS,
                            title = "Scegli il cervello",
                            body = "Provider diretto: serve una tua chiave API (BYOK), nessun account Argus. La chiave viene cifrata con Android Keystore e non sarà più mostrata.",
                            ctaLabel = "Configura il cervello",
                        )
                    } else {
                        s.copy(status = if (i == 0) StepStatus.DONE else StepStatus.TODO)
                    }
                },
                currentIndex = 1,
                canFinish = false,
                providerChoices = choices,
                transport = TransportUi.DirectProvider(
                    providerId = "openai",
                    providerLabel = "OpenAI",
                    baseUrl = "https://api.openai.com/v1",
                    model = "gpt-5.5",
                    authState = dev.argus.ui.model.AuthState.NOT_CONFIGURED,
                    reachable = null,
                    lastLatencyLabel = null,
                    defaultModels = listOf("gpt-5.5", "gpt-5-mini"),
                ),
            ),
            NoopOnboardingCallbacks,
        )
    }
}

@Preview(name = "Onboarding · Batteria (conseguenza)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun OnboardingBatteryPreview() {
    PreviewFor(StepKind.BATTERY_OEM)
}

@Preview(name = "Onboarding · Posizione (opzionale)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun OnboardingLocationPreview() {
    PreviewFor(StepKind.BACKGROUND_LOCATION)
}
