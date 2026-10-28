package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.LocalPresetUi
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.TransportUi
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.presentation.ConnectionFormValidation
import dev.argus.ui.presentation.LocalPresetHints
import dev.argus.ui.presentation.ModelFieldHints
import dev.argus.ui.theme.ArgusCodeSmall
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Foglio «Configura provider» (tavola «Configura provider (bottom sheet)», redesign A). Sostituisce i due dialoghi
// di prima (Hermes e provider diretto) con un solo bottom sheet: in cima la scelta del provider (se l'host la
// offre), poi i campi del ramo scelto.
//
// Disciplina dei segreti (invarianti 7 e 8 del design): la chiave API / il bearer vivono in `remember` (MAI
// `rememberSaveable`: niente segreti nel SavedState), il campo parte SEMPRE vuoto e non viene mai precompilato;
// con una chiave gia' salvata si legge solo il chip «Chiave configurata» e «Lascia vuoto per conservare la chiave
// attuale.». Un campo vuoto in `onSave` = `null` = conserva quella esistente.
//
// «Salva e verifica» controlla i dati PRIMA di chiudersi (B48): con dati non validi il foglio resta aperto, il testo
// scritto resta com'e' e l'errore compare sotto il campo; si chiude solo quando i dati passano il controllo
// ([ConnectionFormValidation], le stesse regole del salvataggio).
// =============================================================================

/**
 * Foglio di configurazione del Brain.
 *
 * @param transport il ramo da configurare: Hermes ([TransportUi.CliBridge]) o un provider diretto.
 * @param providerChoices i provider tra cui scegliere; vuoto = la scelta si fa altrove (onboarding) e qui non compare.
 * @param onSelectProvider cambia il provider selezionato (`id` = `ProviderId.wireName`); lo stato [transport] che
 *   arriva dall'host segue la scelta e il foglio mostra i campi del nuovo ramo.
 */
@Composable
fun TransportConfigurationSheet(
    transport: TransportUi,
    onDismiss: () -> Unit,
    onSaveBridge: (url: String, bearerToken: String?) -> Unit,
    onSaveProvider: (providerId: String, baseUrl: String?, model: String?, apiKey: String?, reasoningEffort: String?) -> Unit,
    modifier: Modifier = Modifier,
    providerChoices: List<ProviderChoiceUi> = emptyList(),
    onSelectProvider: (String) -> Unit = {},
) {
    val title = when (transport) {
        is TransportUi.CliBridge -> stringResource(R.string.bridge_dialog_title)
        is TransportUi.DirectProvider -> stringResource(R.string.provider_dialog_title, transport.providerLabel)
    }
    ArgusBottomSheet(onDismiss = onDismiss, title = title, modifier = modifier) {
        TransportSheetContent(transport, onDismiss, onSaveBridge, onSaveProvider, providerChoices, onSelectProvider)
    }
}

/** Il contenuto del foglio, separato dal contenitore modale per poterlo mostrare nelle anteprime. */
@Composable
internal fun ColumnScope.TransportSheetContent(
    transport: TransportUi,
    onDismiss: () -> Unit,
    onSaveBridge: (url: String, bearerToken: String?) -> Unit,
    onSaveProvider: (providerId: String, baseUrl: String?, model: String?, apiKey: String?, reasoningEffort: String?) -> Unit,
    providerChoices: List<ProviderChoiceUi>,
    onSelectProvider: (String) -> Unit,
) {
    if (providerChoices.isNotEmpty()) ProviderChoiceGroup(providerChoices, onSelectProvider)
    when (transport) {
        is TransportUi.CliBridge -> key("bridge") {
            BridgeForm(transport.url, transport.tokenConfigured, onDismiss, onSaveBridge)
        }
        is TransportUi.DirectProvider -> key(transport.providerId) {
            ProviderForm(
                provider = transport,
                onDismiss = onDismiss,
                onSave = { baseUrl, model, apiKey, reasoningEffort ->
                    onSaveProvider(transport.providerId, baseUrl, model, apiKey, reasoningEffort)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChoiceGroup(choices: List<ProviderChoiceUi>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
        Text(
            stringResource(R.string.rda_ls_provider_group),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        ) {
            choices.forEach { choice ->
                ArgusChoiceChip(text = choice.label, selected = choice.selected, onClick = { onSelect(choice.id) })
            }
        }
    }
}

@Composable
private fun ColumnScope.BridgeForm(
    initialUrl: String,
    tokenConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (url: String, bearerToken: String?) -> Unit,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    // Non serializzare mai il bearer in SavedState/process-death storage.
    var token by remember { mutableStateOf("") }
    // Gli errori compaiono dal primo tentativo di salvare, non mentre si scrive.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val cleanUrl = url.trim()
    val cleanToken = token.trim()
    val problems = ConnectionFormValidation.bridgeForm(url, token, tokenConfigured)
    val urlProblem = problems.url.takeIf { attempted }
    val tokenProblem = problems.token.takeIf { attempted }
    val tokenHint = stringResource(if (tokenConfigured) R.string.bridge_token_keep_hint else R.string.bridge_token_required_hint)

    ArgusTextField(
        value = url,
        onValueChange = { url = it },
        label = stringResource(R.string.bridge_url_label),
        monospace = true,
        errorText = urlProblem?.let { urlProblemText(it) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    ArgusTextField(
        value = token,
        onValueChange = { token = it },
        label = stringResource(R.string.bridge_token_label),
        monospace = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        labelAccessory = if (tokenConfigured) {
            { StatusChip(ArgusTheme.semantic.armed, Icons.Rounded.CheckCircle, stringResource(R.string.bridge_token_configured)) }
        } else {
            null
        },
        supportingText = tokenHint,
        errorText = tokenProblem?.let { secretProblemText(it, ConnectionFormValidation.MIN_SECRET_LENGTH, tokenHint) },
        trailing = { KeyGlyph() },
    )
    ArgusActionRow {
        ArgusButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Text)
        ArgusButton(
            text = stringResource(R.string.action_save_and_test),
            onClick = {
                attempted = true
                if (problems.accepted) {
                    onSave(cleanUrl, cleanToken.takeIf(String::isNotEmpty))
                    token = ""
                    onDismiss()
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ProviderForm(
    provider: TransportUi.DirectProvider,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String?, model: String?, apiKey: String?, reasoningEffort: String?) -> Unit,
) {
    var baseUrl by rememberSaveable(provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var model by rememberSaveable(provider.model) { mutableStateOf(provider.model.orEmpty()) }
    var reasoningEffort by rememberSaveable(provider.reasoningEffort) { mutableStateOf(provider.reasoningEffort) }
    // Non serializzare mai la chiave in SavedState/process-death storage. Il campo parte sempre vuoto.
    var apiKey by remember { mutableStateOf("") }
    // Gli errori compaiono dal primo tentativo di salvare, non mentre si scrive.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val cleanBaseUrl = baseUrl.trim()
    val cleanModel = model.trim()
    val cleanKey = apiKey.trim()
    val keyConfigured = provider.authState == AuthState.OK
    val presetMistyped = provider.modelPresetHint && ModelFieldHints.isMistypedPreset(model)
    // L'indirizzo si modifica solo per il provider Custom: e' anche l'unico che ammette http verso
    // localhost e chiavi corte (endpoint locali).
    val minKeyLength = if (provider.baseUrlEditable) {
        ConnectionFormValidation.MIN_CUSTOM_SECRET_LENGTH
    } else {
        ConnectionFormValidation.MIN_SECRET_LENGTH
    }
    val problems = ConnectionFormValidation.providerForm(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        baseUrlEditable = provider.baseUrlEditable,
        cleartextLoopbackAllowed = provider.baseUrlEditable,
        keyRequired = provider.apiKeyRequired,
        keyConfigured = keyConfigured,
        minKeyLength = minKeyLength,
    )
    val urlProblem = problems.url.takeIf { attempted }
    val keyProblem = problems.key.takeIf { attempted }
    val modelMissing = problems.modelMissing && attempted
    val keyHint = when {
        keyConfigured -> stringResource(R.string.provider_key_keep_hint)
        provider.apiKeyRequired -> stringResource(R.string.provider_key_required_hint)
        else -> stringResource(R.string.provider_key_optional_hint)
    }

    ArgusTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = stringResource(R.string.provider_endpoint_label),
        monospace = true,
        enabled = provider.baseUrlEditable,
        errorText = urlProblem?.let { urlProblemText(it) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        trailing = if (provider.baseUrlEditable) {
            null
        } else {
            {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = stringResource(R.string.rda_ls_provider_endpoint_locked_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
    if (provider.localPresets.isNotEmpty()) {
        LocalServerPresetsSection(
            presets = provider.localPresets,
            currentBaseUrl = cleanBaseUrl,
            onPick = { baseUrl = it.baseUrl },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        ArgusTextField(
            value = model,
            onValueChange = { model = it },
            label = stringResource(R.string.provider_model_label),
            monospace = true,
            // OpenRouter: suggerisce i preset `@preset/<nome>`; se si scrive `@presets/…` avvisa del formato giusto
            // senza correggere il valore e senza bloccare il salvataggio.
            errorText = when {
                modelMissing -> stringResource(R.string.ui40_model_error_empty)
                presetMistyped -> stringResource(R.string.provider_model_preset_format)
                else -> null
            },
            supportingText = if (provider.modelPresetHint) stringResource(R.string.provider_model_hint_openrouter) else null,
        )
        if (provider.defaultModels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                provider.defaultModels.forEach { suggestion -> ModelSuggestion(suggestion) { model = suggestion } }
            }
        }
        // Consiglio generico (nessun nome di modello: i nomi cambiano e stanno solo nei suggerimenti).
        Text(
            text = stringResource(R.string.provider_model_advice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ArgusSpacing.Unit),
        )
    }
    ArgusTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = stringResource(R.string.provider_key_label),
        monospace = true,
        placeholder = provider.apiKeyPrefixHint,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        // Una chiave salvata non rientra mai nel campo: si legge solo che c'e'.
        labelAccessory = if (keyConfigured) {
            { StatusChip(ArgusTheme.semantic.armed, Icons.Rounded.CheckCircle, stringResource(R.string.settings_key_configured)) }
        } else {
            null
        },
        supportingText = keyHint,
        errorText = keyProblem?.let { secretProblemText(it, minKeyLength, keyHint) },
        trailing = { KeyGlyph() },
    )
    if (provider.reasoningEffortEditable) {
        ReasoningChoice(selected = reasoningEffort, onSelect = { reasoningEffort = it })
    }
    ArgusActionRow {
        ArgusButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Text)
        ArgusButton(
            text = stringResource(R.string.action_save_and_test),
            onClick = {
                attempted = true
                if (problems.accepted) {
                    onSave(
                        cleanBaseUrl.takeIf { provider.baseUrlEditable },
                        cleanModel,
                        cleanKey.takeIf(String::isNotEmpty),
                        reasoningEffort.takeIf { provider.reasoningEffortEditable },
                    )
                    apiKey = ""
                    onDismiss()
                }
            },
        )
    }
}

/** Glifo «chiave» a fine campo: decorativo, la chiave non si mostra mai. */
@Composable
private fun KeyGlyph() {
    Icon(
        Icons.Rounded.VpnKey,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** Suggerimento di modello: chip monospace 32 dp, un tocco compila il campo. */
@Composable
private fun ModelSuggestion(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(ArgusRadius.Sm))
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(ArgusRadius.Sm))
                .background(ArgusTheme.surfaces.card2)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = ArgusCodeSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningChoice(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "default" to stringResource(R.string.provider_reasoning_default),
        "none" to stringResource(R.string.provider_reasoning_none),
        "low" to stringResource(R.string.provider_reasoning_low),
        "medium" to stringResource(R.string.provider_reasoning_medium),
        "high" to stringResource(R.string.provider_reasoning_high),
    )
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
        Text(
            stringResource(R.string.provider_reasoning_label),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            options.forEach { (value, label) ->
                ArgusChoiceChip(text = label, selected = selected == value, onClick = { onSelect(value) })
            }
        }
    }
}

/**
 * Preset dei server locali (0.5.1, F4): un tocco compila l'indirizzo. Con un preset scelto compaiono la nota
 * sul contesto (circa 16.000 token), quella del server scelto e quella sul loopback. Stateless: l'indirizzo
 * corrente arriva dal foglio, che resta l'unico a conservarlo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalServerPresetsSection(
    presets: List<LocalPresetUi>,
    currentBaseUrl: String,
    onPick: (LocalPresetUi) -> Unit,
) {
    val active = LocalPresetHints.active(presets, currentBaseUrl)
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
        Text(
            text = stringResource(R.string.ai51_local_presets_label),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            presets.forEach { preset ->
                ArgusChoiceChip(text = preset.label, selected = preset.id == active?.id, onClick = { onPick(preset) })
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
                    modifier = Modifier.padding(horizontal = ArgusSpacing.Unit),
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

// =============================================================================
// Anteprime: un ModalBottomSheet non si disegna in un'anteprima statica, qui si mostra il contenuto sul fondo `sheet`.
// Nessuna anteprima contiene una chiave: i campi dei segreti sono sempre vuoti.
// =============================================================================

private val previewChoices = listOf(
    ProviderChoiceUi("hermes", "Hermes (self-hosted)", selected = false),
    ProviderChoiceUi("openai", "OpenAI", selected = false),
    ProviderChoiceUi("anthropic", "Anthropic", selected = false),
    ProviderChoiceUi("gemini", "Google Gemini", selected = false),
    ProviderChoiceUi("openrouter", "OpenRouter", selected = true),
    ProviderChoiceUi("custom_openai_compat", "Custom (OpenAI-compat)", selected = false),
)

private fun previewOpenRouter(authState: AuthState = AuthState.OK) = TransportUi.DirectProvider(
    providerId = "openrouter",
    providerLabel = "OpenRouter",
    baseUrl = "https://openrouter.ai/api/v1",
    model = "google/gemini-2.5-flash",
    authState = authState,
    reachable = true,
    lastLatencyLabel = "2,4 s",
    defaultModels = listOf("google/gemini-2.5-flash", "openai/gpt-5-mini"),
    apiKeyPrefixHint = "sk-or-…",
    reasoningEffortEditable = true,
    modelPresetHint = true,
)

@Composable
private fun SheetPreview(
    title: String,
    transport: TransportUi,
    choices: List<ProviderChoiceUi> = previewChoices,
) {
    ArgusPreviewSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = ArgusRadius.Xxl, topEnd = ArgusRadius.Xxl))
                .background(ArgusTheme.surfaces.sheet)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            TransportSheetContent(transport, {}, { _, _ -> }, { _, _, _, _, _ -> }, choices, {})
        }
    }
}

@ArgusThemePreviews
@Composable
private fun ProviderSheetKeyConfiguredPreview() {
    SheetPreview("Configura OpenRouter", previewOpenRouter())
}

@ArgusThemePreviews
@Composable
private fun ProviderSheetKeyMissingPreview() {
    SheetPreview("Configura OpenRouter", previewOpenRouter(AuthState.NOT_CONFIGURED))
}

@ArgusThemePreviews
@Composable
private fun ProviderSheetCustomPreview() {
    SheetPreview(
        "Configura Custom (OpenAI-compat)",
        TransportUi.DirectProvider(
            providerId = "custom_openai_compat",
            providerLabel = "Custom (OpenAI-compat)",
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen-local",
            authState = AuthState.NOT_CONFIGURED,
            reachable = null,
            lastLatencyLabel = null,
            baseUrlEditable = true,
            apiKeyRequired = false,
            localPresets = listOf(
                LocalPresetUi("ollama", "Ollama", "http://127.0.0.1:11434/v1"),
                LocalPresetUi("lmstudio", "LM Studio", "http://127.0.0.1:1234/v1"),
                LocalPresetUi("llamacpp", "llama.cpp", "http://127.0.0.1:8080/v1"),
            ),
        ),
        choices = previewChoices.map { it.copy(selected = it.id == "custom_openai_compat") },
    )
}

@ArgusThemePreviews
@Composable
private fun BridgeSheetPreview() {
    SheetPreview(
        "Configura Hermes",
        TransportUi.CliBridge(url = "https://hermes.example", reachable = true, lastLatencyLabel = null, tokenConfigured = true),
        choices = previewChoices.map { it.copy(selected = it.id == "hermes") },
    )
}

/** Dall'onboarding la scelta del provider e' nella pagina: il foglio non la ripete. */
@ArgusThemePreviews
@Composable
private fun ProviderSheetWithoutChoicesPreview() {
    SheetPreview("Configura OpenRouter", previewOpenRouter(AuthState.NOT_CONFIGURED), choices = emptyList())
}
