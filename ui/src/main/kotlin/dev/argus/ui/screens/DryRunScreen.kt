package dev.argus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.argus.engine.dryrun.DryRunField
import dev.argus.ui.R
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusChoiceChip
import dev.argus.ui.components.ArgusDetailTopBar
import dev.argus.ui.components.ArgusTextField
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.CodeBlock
import dev.argus.ui.components.CodePart
import dev.argus.ui.components.CodeTone
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.SectionLabel
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.components.rememberRevealed
import dev.argus.ui.model.DryRunCallbacks
import dev.argus.ui.model.DryRunChoiceUi
import dev.argus.ui.model.DryRunCodeSpan
import dev.argus.ui.model.DryRunFieldUi
import dev.argus.ui.model.DryRunOptionUi
import dev.argus.ui.model.DryRunState
import dev.argus.ui.model.DryRunStepUi
import dev.argus.ui.model.DryRunTone
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusCode
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.RolePair

// =============================================================================
// 0.8 · «Prova a secco» (tavola approvata «ProvaASecco»). Una esecuzione simulata della regola, passo per passo:
// evento di prova modificabile, poi cosa succederebbe. Niente si esegue: lo dice il banner e lo garantisce il
// simulatore. I passi sono resi dai tipi (invariante 4); un comando shell è sempre integrale (invariante 6).
// Stateless: (state, callbacks).
// =============================================================================

@Composable
fun DryRunScreen(
    state: DryRunState,
    callbacks: DryRunCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArgusDetailTopBar(
                title = state.ruleName,
                onBack = callbacks::onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, bottom = ArgusSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                Text(
                    stringResource(R.string.dry_run_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                )
                SimulationBanner(state.notes)
                if (state.missing) {
                    Text(
                        stringResource(R.string.dry_run_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (!state.loading) {
                    if (state.span) PhaseChoice(state.simulateEnd, callbacks::onSimulateEndChange)
                    SampleEvent(state, callbacks)
                    if (state.steps.isNotEmpty()) StepList(state.steps, callbacks)
                    Text(
                        stringResource(R.string.dry_run_footer),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.dry_run_not_simulated),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** «Simulazione: non viene eseguito nulla…» più le note su come si simula questa esecuzione. */
@Composable
private fun SimulationBanner(notes: List<String>) {
    val tone = ArgusTheme.semantic.cloud
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArgusRadius.Lg))
            .background(tone.bg)
            .padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = tone.fg)
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            Text(stringResource(R.string.dry_run_banner), color = tone.fg, style = MaterialTheme.typography.bodyMedium)
            notes.forEach { Text(it, color = tone.fg, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseChoice(simulateEnd: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.dry_run_phase_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            ArgusChoiceChip(stringResource(R.string.dry_run_phase_start), selected = !simulateEnd, onClick = { onChange(false) })
            ArgusChoiceChip(stringResource(R.string.dry_run_phase_end), selected = simulateEnd, onClick = { onChange(true) })
        }
    }
}

@Composable
private fun SampleEvent(state: DryRunState, callbacks: DryRunCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.dry_run_event_header))
        ArgusCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
                state.fields.forEach { field -> SampleField(field, callbacks) }
                if (state.nfc) {
                    ArgusChoiceChip(
                        stringResource(R.string.dry_run_verified_scan),
                        selected = state.verifiedScan,
                        onClick = { callbacks.onVerifiedScanChange(!state.verifiedScan) },
                        role = Role.Checkbox,
                    )
                }
                if (state.fields.isEmpty() && !state.nfc) {
                    Text(
                        stringResource(R.string.dry_run_no_fields),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ArgusButton(
                    text = stringResource(R.string.dry_run_run_again),
                    onClick = callbacks::onRun,
                    style = ButtonStyle.Tonal,
                    fillWidth = true,
                )
            }
        }
    }
}

@Composable
private fun SampleField(field: DryRunFieldUi, callbacks: DryRunCallbacks) {
    val label = when (field.field) {
        DryRunField.SENDER -> R.string.dry_run_field_sender
        DryRunField.TITLE -> R.string.dry_run_field_title
        DryRunField.TEXT -> R.string.dry_run_field_text
        DryRunField.NUMBER -> R.string.dry_run_field_number
        DryRunField.NAME -> R.string.dry_run_field_name
        DryRunField.MODE -> R.string.dry_run_field_mode
    }
    ArgusTextField(
        value = field.value,
        onValueChange = { callbacks.onFieldChange(field.field, it) },
        label = stringResource(label),
        keyboardOptions = if (field.field == DryRunField.NUMBER) {
            KeyboardOptions(keyboardType = KeyboardType.Phone)
        } else {
            KeyboardOptions.Default
        },
    )
}

@Composable
private fun StepList(steps: List<DryRunStepUi>, callbacks: DryRunCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.dry_run_steps_header))
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
            steps.forEachIndexed { index, step ->
                ArgusCard(
                    shape = groupShape(groupPositionOf(index, steps.size), outer = 22.dp, inner = 5.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    StepRow(step, callbacks)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepRow(step: DryRunStepUi, callbacks: DryRunCallbacks) {
    val (role, icon, description) = toneVisual(step.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ArgusSpacing.Large + ArgusSpacing.Medium * step.depth.coerceAtMost(MAX_INDENT),
                end = ArgusSpacing.Large,
                top = ArgusSpacing.Medium,
                bottom = ArgusSpacing.Medium,
            ),
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(
            icon = icon,
            container = role.bg,
            content = role.fg,
            size = 30.dp,
            iconSize = 16.dp,
            contentDescription = description,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(rememberRevealed(step.title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            step.detail?.let {
                Text(rememberRevealed(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            step.code?.let { spans ->
                CodeBlock(
                    parts = spans.map(::codePart),
                    label = if (step.shell) stringResource(R.string.dry_run_shell_label) else null,
                    labelIcon = if (step.shell) Icons.Rounded.Terminal else null,
                    style = ArgusCode,
                )
            }
            step.codeNote?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            step.choice?.let { AnswerChoice(it, callbacks) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerChoice(choice: DryRunChoiceUi, callbacks: DryRunCallbacks) {
    if (choice.freeText) {
        ArgusTextField(
            value = choice.text,
            onValueChange = { callbacks.onAnswer(choice.key, it) },
            label = stringResource(R.string.dry_run_answer_label),
            supportingText = choice.hint,
            singleLine = choice.numeric,
            keyboardOptions = if (choice.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            choice.options.forEach { option ->
                val selected = if (option.noAnswer) choice.noAnswerSelected else !choice.noAnswerSelected && option.value == choice.selected
                ArgusChoiceChip(
                    text = option.label,
                    selected = selected,
                    onClick = {
                        if (option.noAnswer) callbacks.onNoAnswer(choice.key) else callbacks.onAnswer(choice.key, option.value)
                    },
                )
            }
        }
    }
}

private fun codePart(span: DryRunCodeSpan): CodePart =
    CodePart(span.text, if (span.external) CodeTone.External else CodeTone.Plain)

@Composable
private fun toneVisual(tone: DryRunTone): Triple<RolePair, androidx.compose.ui.graphics.vector.ImageVector, String> {
    val semantic = ArgusTheme.semantic
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        DryRunTone.OK -> Triple(semantic.armed, Icons.Rounded.Check, stringResource(R.string.dry_run_tone_ok))
        DryRunTone.AI -> Triple(semantic.generative, Icons.Rounded.AutoAwesome, stringResource(R.string.dry_run_tone_ai))
        DryRunTone.ASK -> Triple(semantic.pending, Icons.Rounded.QuestionMark, stringResource(R.string.dry_run_tone_ask))
        DryRunTone.BLOCKED -> Triple(semantic.needsReview, Icons.Rounded.PriorityHigh, stringResource(R.string.dry_run_tone_blocked))
        DryRunTone.STOPPED -> Triple(semantic.error, Icons.Rounded.Block, stringResource(R.string.dry_run_tone_stopped))
        DryRunTone.SKIPPED -> Triple(semantic.disabled, Icons.Rounded.Remove, stringResource(R.string.dry_run_tone_skipped))
        DryRunTone.INFO -> Triple(
            RolePair(scheme.onSecondaryContainer, scheme.secondaryContainer),
            Icons.Rounded.Science,
            stringResource(R.string.dry_run_tone_info),
        )
    }
}

private const val MAX_INDENT = 4

// -----------------------------------------------------------------------------
// Anteprime
// -----------------------------------------------------------------------------

private object NoopDryRunCallbacks : DryRunCallbacks {
    override fun onBack() = Unit
    override fun onFieldChange(field: DryRunField, value: String) = Unit
    override fun onVerifiedScanChange(verified: Boolean) = Unit
    override fun onSimulateEndChange(end: Boolean) = Unit
    override fun onRun() = Unit
    override fun onAnswer(key: String, value: String) = Unit
    override fun onNoAnswer(key: String) = Unit
}

private val previewMockup = DryRunState(
    ruleName = "Messaggio urgente → avviso",
    loading = false,
    fields = listOf(
        DryRunFieldUi(DryRunField.SENDER, "Marta"),
        DryRunFieldUi(DryRunField.TEXT, "Sei libero stasera? È urgente"),
    ),
    steps = listOf(
        DryRunStepUi(DryRunTone.OK, "Il trigger corrisponde", "Notifica WhatsApp, chat 1:1 con Marta"),
        DryRunStepUi(DryRunTone.OK, "Condizioni vere", "Solo tra le 08:00 e le 22:00 (Europe/Rome) · vera · adesso sono le 19:12"),
        DryRunStepUi(
            DryRunTone.AI,
            "Chiederebbe all'AI: «è urgente?»",
            "In prova l'AI non viene chiamata. Scegli la risposta da simulare:",
            choice = DryRunChoiceUi(
                key = "step:1",
                options = listOf(DryRunOptionUi("true", "sì"), DryRunOptionUi("false", "no")),
                selected = "true",
            ),
        ),
        DryRunStepUi(
            DryRunTone.OK,
            "Eseguirebbe questo comando",
            code = listOf(
                DryRunCodeSpan("cmd notification post -t Urgente argus "),
                DryRunCodeSpan("'Sei libero stasera? È urgente'", external = true),
            ),
            shell = true,
            codeNote = "La parte evidenziata viene da fuori (dato non attendibile): entra tra apici, come un solo argomento.",
            depth = 1,
        ),
        DryRunStepUi(
            DryRunTone.BLOCKED,
            "Non partirebbe: Vibra",
            "Shizuku non è attivo\nLe altre azioni non cambiano.",
            depth = 1,
        ),
    ),
)

@ArgusThemePreviews
@Composable
private fun DryRunMockupPreview() {
    ArgusTheme { DryRunScreen(previewMockup, NoopDryRunCallbacks) }
}

@ArgusThemePreviews
@Composable
private fun DryRunNotMatchedPreview() {
    ArgusTheme {
        DryRunScreen(
            previewMockup.copy(
                fields = listOf(DryRunFieldUi(DryRunField.SENDER, "Luca"), DryRunFieldUi(DryRunField.TEXT, "Ciao")),
                steps = listOf(
                    DryRunStepUi(
                        DryRunTone.SKIPPED,
                        "Il trigger non corrisponde",
                        "Notifica WhatsApp, chat 1:1 con Marta\nCon questo evento la regola non scatterebbe.",
                    ),
                ),
            ),
            NoopDryRunCallbacks,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun DryRunSpanEndPreview() {
    ArgusTheme {
        DryRunScreen(
            DryRunState(
                ruleName = "In auto: volume al massimo",
                loading = false,
                span = true,
                simulateEnd = true,
                notes = listOf("Simula la fine come se la regola fosse in corso."),
                steps = listOf(
                    DryRunStepUi(DryRunTone.OK, "Il trigger di fine corrisponde", "Bluetooth: Auto disconnesso"),
                    DryRunStepUi(DryRunTone.OK, "Rimetterebbe com'era", "Non disturbare\nvolume multimediale"),
                    DryRunStepUi(
                        DryRunTone.ASK,
                        "Ti chiederebbe",
                        "In prova non compare nessuna notifica. Scegli la risposta da simulare:",
                        code = listOf(DryRunCodeSpan("Riapro Spotify?")),
                        choice = DryRunChoiceUi(
                            key = "step:1",
                            options = listOf(
                                DryRunOptionUi("si", "si"),
                                DryRunOptionUi("no", "no"),
                                DryRunOptionUi("", "Nessuna risposta", noAnswer = true),
                            ),
                            noAnswerSelected = true,
                        ),
                    ),
                    DryRunStepUi(DryRunTone.STOPPED, "Il programma si fermerebbe", "Nessuna risposta alla domanda"),
                ),
            ),
            NoopDryRunCallbacks,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun DryRunNoFormPreview() {
    ArgusTheme {
        DryRunScreen(
            DryRunState(
                ruleName = "Sveglia delle 7",
                loading = false,
                steps = listOf(
                    DryRunStepUi(DryRunTone.OK, "Il trigger corrisponde", "Ogni giorno alle 07:00 (Europe/Rome)"),
                    DryRunStepUi(DryRunTone.INFO, "Aspetterebbe 5 minuti", "In prova non si aspetta."),
                    DryRunStepUi(
                        DryRunTone.OK,
                        "Eseguirebbe: Apri URL",
                        code = listOf(DryRunCodeSpan("https://example.com/meteo")),
                    ),
                ),
            ),
            NoopDryRunCallbacks,
        )
    }
}
