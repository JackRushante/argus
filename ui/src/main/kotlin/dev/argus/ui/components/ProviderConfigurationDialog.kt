package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.LocalPresetUi
import dev.argus.ui.model.TransportUi
import dev.argus.ui.presentation.ConnectionFormValidation
import dev.argus.ui.presentation.LocalPresetHints
import dev.argus.ui.presentation.ModelFieldHints

/**
 * Editor di un provider diretto BYOK. Stessa disciplina di [BridgeConfigurationDialog]: la chiave
 * vive in `remember` (MAI `rememberSaveable` — niente segreti nel SavedState) e non viene mai
 * precompilata. `apiKey == null` in [onSave] conserva la chiave esistente.
 *
 * «Salva e verifica» controlla i dati PRIMA di chiudersi (B48): con dati non validi il dialogo resta
 * aperto, il testo scritto resta com'è e l'errore compare sotto il campo; si chiude solo quando i dati
 * passano il controllo ([ConnectionFormValidation], le stesse regole del salvataggio).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderConfigurationDialog(
    provider: TransportUi.DirectProvider,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String?, model: String?, apiKey: String?, reasoningEffort: String?) -> Unit,
) {
    var baseUrl by rememberSaveable(provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var model by rememberSaveable(provider.model) { mutableStateOf(provider.model.orEmpty()) }
    var reasoningEffort by rememberSaveable(provider.reasoningEffort) {
        mutableStateOf(provider.reasoningEffort)
    }
    // Non serializzare mai la chiave in SavedState/process-death storage.
    var key by remember { mutableStateOf("") }
    // Gli errori compaiono dal primo tentativo di salvare, non mentre si scrive.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val cleanBaseUrl = baseUrl.trim()
    val cleanModel = model.trim()
    val cleanKey = key.trim()
    val keyConfigured = provider.authState == AuthState.OK
    val presetMistyped = provider.modelPresetHint && ModelFieldHints.isMistypedPreset(model)
    // L'indirizzo si modifica solo per il provider Custom: è anche l'unico che ammette http verso
    // localhost e chiavi corte (endpoint locali).
    val minKeyLength = if (provider.baseUrlEditable) {
        ConnectionFormValidation.MIN_CUSTOM_SECRET_LENGTH
    } else {
        ConnectionFormValidation.MIN_SECRET_LENGTH
    }
    val problems = ConnectionFormValidation.providerForm(
        baseUrl = baseUrl,
        model = model,
        apiKey = key,
        baseUrlEditable = provider.baseUrlEditable,
        cleartextLoopbackAllowed = provider.baseUrlEditable,
        keyRequired = provider.apiKeyRequired,
        keyConfigured = keyConfigured,
        minKeyLength = minKeyLength,
    )
    val urlProblem = problems.url.takeIf { attempted }
    val keyProblem = problems.key.takeIf { attempted }
    val modelMissing = problems.modelMissing && attempted
    val reasoningOptions = listOf(
        "default" to stringResource(R.string.provider_reasoning_default),
        "none" to stringResource(R.string.provider_reasoning_none),
        "low" to stringResource(R.string.provider_reasoning_low),
        "medium" to stringResource(R.string.provider_reasoning_medium),
        "high" to stringResource(R.string.provider_reasoning_high),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_dialog_title, provider.providerLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = provider.baseUrlEditable,
                    isError = urlProblem != null,
                    label = { Text(stringResource(R.string.provider_endpoint_label)) },
                    supportingText = urlProblem?.let { problem -> { Text(urlProblemText(problem)) } },
                )
                if (provider.localPresets.isNotEmpty()) {
                    LocalServerPresetsSection(
                        presets = provider.localPresets,
                        currentBaseUrl = cleanBaseUrl,
                        onPick = { baseUrl = it.baseUrl },
                    )
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = presetMistyped || modelMissing,
                    label = { Text(stringResource(R.string.provider_model_label)) },
                    // OpenRouter: suggerisce i preset `@preset/<nome>`; se si scrive `@presets/…` avvisa
                    // del formato giusto senza correggere il valore e senza bloccare il salvataggio.
                    supportingText = if (modelMissing) {
                        { Text(stringResource(R.string.ui40_model_error_empty)) }
                    } else if (provider.modelPresetHint) {
                        {
                            Text(
                                if (presetMistyped) {
                                    stringResource(R.string.provider_model_preset_format)
                                } else {
                                    stringResource(R.string.provider_model_hint_openrouter)
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
                if (provider.defaultModels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        provider.defaultModels.forEach { suggestion ->
                            AssistChip(
                                onClick = { model = suggestion },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }
                // Consiglio generico (nessun nome di modello: i nomi cambiano e stanno solo nei suggerimenti).
                Text(
                    text = stringResource(R.string.provider_model_advice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (provider.reasoningEffortEditable) {
                    Text(stringResource(R.string.provider_reasoning_label))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reasoningOptions.forEach { (value, label) ->
                            FilterChip(
                                selected = reasoningEffort == value,
                                onClick = { reasoningEffort = value },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = keyProblem != null,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.provider_key_label)) },
                    placeholder = provider.apiKeyPrefixHint?.let { { Text(it) } },
                    supportingText = {
                        val keyHint = when {
                            keyConfigured -> stringResource(R.string.provider_key_keep_hint)
                            provider.apiKeyRequired -> stringResource(R.string.provider_key_required_hint)
                            else -> stringResource(R.string.provider_key_optional_hint)
                        }
                        Text(
                            if (keyProblem != null) {
                                secretProblemText(keyProblem, minKeyLength, keyHint)
                            } else {
                                keyHint
                            },
                        )
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            Button(
                onClick = {
                    attempted = true
                    if (problems.accepted) {
                        onSave(
                            cleanBaseUrl.takeIf { provider.baseUrlEditable },
                            cleanModel,
                            cleanKey.takeIf(String::isNotEmpty),
                            reasoningEffort.takeIf { provider.reasoningEffortEditable },
                        )
                        key = ""
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.action_save_and_test)) }
        },
    )
}

/**
 * Preset dei server locali (0.5.1, F4): un tocco compila l'indirizzo. Con un preset scelto compaiono la nota
 * sul contesto (circa 16.000 token), quella del server scelto e quella sul loopback. Stateless: l'indirizzo
 * corrente arriva dal dialogo, che resta l'unico a conservarlo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalServerPresetsSection(
    presets: List<LocalPresetUi>,
    currentBaseUrl: String,
    onPick: (LocalPresetUi) -> Unit,
) {
    val active = LocalPresetHints.active(presets, currentBaseUrl)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ai51_local_presets_label),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(
                    selected = preset.id == active?.id,
                    onClick = { onPick(preset) },
                    label = { Text(preset.label) },
                )
            }
        }
        if (active != null) {
            val notes = listOfNotNull(
                R.string.ai51_local_note,
                localPresetHintRes(active.id),
                R.string.ai51_local_loopback_note,
            )
            notes.forEach { note ->
                Text(
                    text = stringResource(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** La nota specifica del server locale (come portare il contesto a 16.000 token), o null per un id sconosciuto. */
internal fun localPresetHintRes(id: String): Int? = when (id) {
    "ollama" -> R.string.ai51_local_hint_ollama
    "lmstudio" -> R.string.ai51_local_hint_lmstudio
    "llamacpp" -> R.string.ai51_local_hint_llamacpp
    else -> null
}
