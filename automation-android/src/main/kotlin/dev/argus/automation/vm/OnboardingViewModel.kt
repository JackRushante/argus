package dev.argus.automation.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.AppPreferences
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.BridgeHealthResult
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftRepository
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuPermissionResult
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.OnboardingState
import dev.argus.ui.model.OnboardingStepState
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.ShizukuCapabilityCatalog
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.StepKind
import dev.argus.ui.model.StepStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.screens.shizukuOnboardingCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface OnboardingEvent {
    data class Message(val text: String) : OnboardingEvent
    data object Complete : OnboardingEvent
    data object Close : OnboardingEvent
}

private data class OnboardingSources(
    val preferences: AppPreferences,
    val shizuku: ShizukuGatewayStatus,
    val geofenceNeeded: Boolean,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferencesStore,
    private val configuration: ProviderConfigStore,
    private val brain: ConfiguredBridgeBrain,
    private val shizuku: ShizukuGateway,
    automations: AutomationStore,
    drafts: DraftRepository,
    private val language: RenderLanguage = RenderLanguage.system(),
) : ViewModel() {
    private val currentIndex = savedStateHandle.getStateFlow(CURRENT_INDEX_KEY, 0)
    private val skipped = savedStateHandle.getStateFlow(SKIPPED_KEY, arrayListOf<String>())
    private val refreshSignal = MutableStateFlow(0L)
    private val tokenConfigured = MutableStateFlow(false)
    private var shizukuRationaleShown = false
    private val mutableEvents = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()

    @Volatile
    private var latestPreferences = preferences.observe().value

    private val sources = combine(
        preferences.observe(),
        shizuku.observeStatus(),
        automations.observeAll(),
        drafts.observeAll(),
    ) { appPreferences, shizukuStatus, rules, pending ->
        latestPreferences = appPreferences
        OnboardingSources(
            preferences = appPreferences,
            shizuku = shizukuStatus,
            geofenceNeeded = rules.any { it.trigger is Trigger.Geofence } ||
                pending.any { it.draft.trigger is Trigger.Geofence },
        )
    }

    val state: StateFlow<OnboardingState> = combine(
        sources,
        currentIndex,
        skipped,
        tokenConfigured,
        refreshSignal,
    ) { values, index, skippedSteps, configured, _ ->
        buildState(values, index, skippedSteps.toSet(), configured)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildState(
            OnboardingSources(
                latestPreferences,
                shizuku.status(),
                geofenceNeeded = false,
            ),
            currentIndex.value,
            skipped.value.toSet(),
            configured = false,
        ),
    )

    init {
        refresh()
    }

    fun acceptPrivacy() {
        viewModelScope.launch {
            if (preferences.setPrivacyAccepted(true)) {
                advance()
            } else {
                message(
                    language.pick(
                        "Unable to save privacy consent.",
                        "Impossibile salvare il consenso privacy.",
                    ),
                )
            }
        }
    }

    fun saveBridge(url: String, bearer: String?) {
        viewModelScope.launch {
            try {
                if (!configuration.saveConfiguration(url, bearer)) {
                    message(
                        language.pick(
                            "Invalid configuration: use HTTPS and an ASCII bearer of at least 16 characters.",
                            "Configurazione non valida: usa HTTPS e un bearer ASCII di almeno 16 caratteri.",
                        ),
                    )
                    return@launch
                }
                tokenConfigured.value = true
                message(
                    language.pick(
                        "Hermes configuration saved in protected storage.",
                        "Configurazione Hermes salvata in storage protetto.",
                    ),
                )
                when (brain.health()) {
                    is BridgeHealthResult.Reachable -> message(
                        language.pick("Hermes is reachable.", "Hermes raggiungibile."),
                    )
                    is BridgeHealthResult.Unreachable -> message(
                        language.pick(
                            "Configuration saved; Hermes is not reachable right now.",
                            "Configurazione salvata; Hermes non è raggiungibile ora.",
                        ),
                    )
                }
                advance()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(
                    language.pick(
                        "Unable to save the Hermes configuration.",
                        "Impossibile salvare la configurazione Hermes.",
                    ),
                )
            }
        }
    }

    fun selectProvider(wireName: String) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableEvents.tryEmit(
                OnboardingEvent.Message(language.pick("Unknown provider.", "Provider sconosciuto.")),
            )
            return
        }
        viewModelScope.launch {
            if (configuration.selectProvider(id)) {
                refresh()
            } else {
                message(
                    language.pick("Unable to select the provider.", "Impossibile selezionare il provider."),
                )
            }
        }
    }

    fun saveProviderConfig(wireName: String, baseUrl: String?, model: String?, apiKey: String?) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableEvents.tryEmit(
                OnboardingEvent.Message(language.pick("Unknown provider.", "Provider sconosciuto.")),
            )
            return
        }
        viewModelScope.launch {
            try {
                if (!configuration.saveProviderConfig(id, baseUrl = baseUrl, model = model, apiKey = apiKey)) {
                    message(language.pick("Invalid configuration.", "Configurazione non valida."))
                    return@launch
                }
                // La chiave salvata potrebbe essere di un provider NON selezionato: rileggi invece di assumere true.
                tokenConfigured.value = configuration.bearerToken() != null
                message(
                    language.pick(
                        "Configuration saved in protected storage.",
                        "Configurazione salvata in storage protetto.",
                    ),
                )
                when (brain.health()) {
                    is BridgeHealthResult.Reachable -> message(
                        language.pick("Provider is reachable.", "Provider raggiungibile."),
                    )
                    is BridgeHealthResult.Unreachable -> message(
                        language.pick(
                            "Configuration saved; the provider is not reachable right now.",
                            "Configurazione salvata; il provider non è raggiungibile ora.",
                        ),
                    )
                }
                advance()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(
                    language.pick(
                        "Unable to save the provider configuration.",
                        "Impossibile salvare la configurazione del provider.",
                    ),
                )
            }
        }
    }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            val result = shizuku.requestPermission(rationaleShown = shizukuRationaleShown)
            message(
                when (result) {
                    ShizukuPermissionResult.GRANTED -> {
                        shizukuRationaleShown = false
                        language.pick(
                            "Shizuku authorization granted.",
                            "Autorizzazione Shizuku concessa.",
                        )
                    }
                    ShizukuPermissionResult.DENIED -> language.pick(
                        "Shizuku authorization denied.",
                        "Autorizzazione Shizuku negata.",
                    )
                    ShizukuPermissionResult.RATIONALE_REQUIRED -> {
                        shizukuRationaleShown = true
                        language.pick(
                            "Tap the button again to confirm the Shizuku request.",
                            "Premi di nuovo il pulsante per confermare la richiesta Shizuku.",
                        )
                    }
                    ShizukuPermissionResult.UNAVAILABLE -> language.pick(
                        "Shizuku is unavailable.",
                        "Shizuku non è disponibile.",
                    )
                },
            )
            refresh()
            if (result == ShizukuPermissionResult.GRANTED) advance()
        }
    }

    fun refresh() {
        refreshSignal.value = System.nanoTime()
        viewModelScope.launch {
            tokenConfigured.value = cancellationSafeOrNull {
                configuration.bearerToken() != null
            } ?: false
        }
    }

    fun onSkip(kind: StepKind) {
        if (kind == StepKind.WELCOME_PRIVACY || kind == StepKind.BRAIN_CONFIG) return
        val values = skipped.value.toMutableSet().apply { add(kind.name) }
        savedStateHandle[SKIPPED_KEY] = ArrayList(values)
        advance()
    }

    fun onNext() {
        advance()
    }

    fun onBack() {
        val index = currentIndex.value
        when {
            index > 0 -> savedStateHandle[CURRENT_INDEX_KEY] = index - 1
            latestPreferences.onboardingCompleted -> mutableEvents.tryEmit(OnboardingEvent.Close)
            else -> mutableEvents.tryEmit(
                OnboardingEvent.Message(
                    language.pick(
                        "Complete the two required steps to use Argus.",
                        "Completa i due passaggi obbligatori per usare Argus.",
                    ),
                ),
            )
        }
    }

    fun onFinish() {
        val current = state.value
        if (!current.canFinish) {
            mutableEvents.tryEmit(
                OnboardingEvent.Message(
                    language.pick(
                        "Privacy consent and brain configuration are required.",
                        "Consenso privacy e configurazione del cervello sono obbligatori.",
                    ),
                ),
            )
            return
        }
        viewModelScope.launch {
            if (preferences.setOnboardingCompleted(true)) {
                mutableEvents.emit(OnboardingEvent.Complete)
            } else {
                message(
                    language.pick(
                        "Unable to complete setup.",
                        "Impossibile completare la configurazione.",
                    ),
                )
            }
        }
    }

    fun restart() {
        savedStateHandle[CURRENT_INDEX_KEY] = 0
        savedStateHandle[SKIPPED_KEY] = arrayListOf<String>()
        refresh()
    }

    fun currentShizukuStatus(): ShizukuGatewayStatus = shizuku.status()

    private fun buildState(
        sources: OnboardingSources,
        requestedIndex: Int,
        skipped: Set<String>,
        configured: Boolean,
    ): OnboardingState {
        val health = readAndroidUiHealth(context)
        val uiShizuku = sources.shizuku.toUiStatus(degradedAfterReboot = false)
        val done = mapOf(
            StepKind.WELCOME_PRIVACY to sources.preferences.privacyAccepted,
            StepKind.BRAIN_CONFIG to configured,
            StepKind.SHIZUKU to (sources.shizuku == ShizukuGatewayStatus.AUTHORIZED),
            // Lo step notifiche è "fatto" solo con pubblicazione E lettura: le CTA arrivano in
            // quest'ordine e restano skippabili (le regole WhatsApp resteranno non armabili).
            StepKind.NOTIFICATION_ACCESS to
                (health.notificationsGranted && health.notificationListenerGranted),
            StepKind.BATTERY_OEM to health.batteryExempt,
            StepKind.BACKGROUND_LOCATION to
                (!sources.geofenceNeeded ||
                    (health.foregroundLocationGranted && health.backgroundLocationGranted)),
        )
        val index = requestedIndex.coerceIn(0, STEP_ORDER.lastIndex)
        val steps = STEP_ORDER.mapIndexed { stepIndex, kind ->
            step(kind, uiShizuku, sources.geofenceNeeded, health).copy(
                status = when {
                    done.getValue(kind) -> StepStatus.DONE
                    kind.name in skipped -> StepStatus.SKIPPED
                    stepIndex == index -> StepStatus.IN_PROGRESS
                    else -> StepStatus.TODO
                },
                ctaLabel = step(kind, uiShizuku, sources.geofenceNeeded, health).ctaLabel
                    .takeUnless { done.getValue(kind) },
            )
        }
        return OnboardingState(
            steps = steps,
            currentIndex = index,
            canFinish = sources.preferences.privacyAccepted && configured,
            bridgeUrl = configuration.baseUrl(),
            bridgeTokenConfigured = configured,
            providerChoices = providerChoices(),
            transport = transportUi(configured),
            shizukuCapabilities = ShizukuCapabilityCatalog.rows(language),
        )
    }

    /**
     * Ramo transport del provider selezionato, versione onboarding: nessuna reachability misurata
     * (health si prova solo al salvataggio). [configured] = chiave del selezionato presente.
     */
    private fun transportUi(configured: Boolean): TransportUi {
        val id = configuration.selectedProviderId()
        val config = configuration.providerConfig(id)
        return if (id == ProviderId.HERMES) {
            TransportUi.CliBridge(
                url = config.baseUrl,
                reachable = null,
                lastLatencyLabel = null,
                tokenConfigured = configured,
            )
        } else {
            val spec = ProviderCatalog.spec(id)
            TransportUi.DirectProvider(
                providerId = id.wireName,
                providerLabel = spec.displayName,
                baseUrl = config.baseUrl,
                model = config.model,
                authState = if (configured) AuthState.OK else AuthState.NOT_CONFIGURED,
                reachable = null,
                lastLatencyLabel = null,
                defaultModels = spec.defaultModels,
                baseUrlEditable = id == ProviderId.CUSTOM_OPENAI_COMPAT,
                apiKeyPrefixHint = spec.apiKeyPrefixHint,
            )
        }
    }

    private fun providerChoices(): List<ProviderChoiceUi> {
        val selected = configuration.selectedProviderId()
        return ProviderCatalog.specs.values.map {
            ProviderChoiceUi(it.id.wireName, it.displayName, it.id == selected)
        }
    }

    private fun step(
        kind: StepKind,
        shizukuStatus: ShizukuStatus,
        geofenceNeeded: Boolean,
        health: AndroidUiHealth,
    ): OnboardingStepState {
        val (shizukuBody, shizukuCta) = shizukuOnboardingCopy(shizukuStatus, language)
        return when (kind) {
            StepKind.WELCOME_PRIVACY -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                language.pick("Privacy and consent", "Privacy e consenso"),
                language.pick(
                    "What you type in chat is sent to the configured AI service. Depending on your setup, it may go through your Hermes server or directly to a cloud provider. Rules are always shown for approval before activation.",
                    "Ciò che scrivi in chat viene inviato al servizio AI configurato. In base alla configurazione può passare dal tuo server Hermes oppure andare direttamente a un provider cloud. Le regole vengono sempre mostrate e approvate prima dell'attivazione.",
                ),
                language.pick("I understand and consent", "Ho capito, acconsento"),
                null,
            )
            StepKind.BRAIN_CONFIG -> {
                val hermesSelected = configuration.selectedProviderId() == ProviderId.HERMES
                OnboardingStepState(
                    kind,
                    StepStatus.TODO,
                    language.pick("Choose the brain", "Scegli il cervello"),
                    if (hermesSelected) {
                        language.pick(
                            "Configure the bridge HTTPS address and bearer. The bearer is encrypted with Android Keystore and will not be shown again.",
                            "Configura l'indirizzo HTTPS del bridge e il bearer. Il bearer viene cifrato con Android Keystore e non sarà più mostrato.",
                        )
                    } else {
                        language.pick(
                            "Direct provider: use your own API key (BYOK); no Argus account is required. The key is encrypted with Android Keystore and will not be shown again.",
                            "Provider diretto: serve una tua chiave API (BYOK), nessun account Argus. La chiave viene cifrata con Android Keystore e non sarà più mostrata.",
                        )
                    },
                    language.pick("Configure the brain", "Configura il cervello"),
                    null,
                )
            }
            StepKind.SHIZUKU -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                language.pick("Authorize Shizuku", "Autorizza Shizuku"),
                shizukuBody,
                shizukuCta,
                null,
            )
            StepKind.NOTIFICATION_ACCESS -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                language.pick("Notifications", "Notifiche"),
                if (!health.notificationsGranted) {
                    language.pick(
                        "First allow Argus to show results and alerts. Then grant notification access to read WhatsApp messages and reply.",
                        "Prima consenti ad Argus di mostrare esiti e avvisi. Poi servirà l'accesso " +
                            "alle notifiche per leggere i messaggi WhatsApp e rispondere.",
                    )
                } else {
                    language.pick(
                        "Results and alerts are active. Now grant notification access so approved rules can read incoming WhatsApp messages and reply.",
                        "Esiti e avvisi sono attivi. Ora concedi l'accesso alle notifiche: serve a " +
                            "leggere i messaggi WhatsApp in arrivo e a rispondere dalle regole approvate.",
                    )
                },
                if (!health.notificationsGranted) {
                    language.pick("Allow", "Concedi")
                } else {
                    language.pick("Grant notification access", "Consenti lettura notifiche")
                },
                null,
            )
            StepKind.BATTERY_OEM -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                language.pick("Battery optimization", "Ottimizzazione batteria"),
                language.pick(
                    "Recommended: grant the Android exemption, then manually check background activity and auto-launch in OxygenOS if available. Argus cannot reliably read or change these OEM switches.",
                    "Consigliato: concedi l'esclusione Android, poi verifica manualmente in OxygenOS " +
                        "attività in background e avvio automatico, se presenti. Argus non può leggere " +
                        "né cambiare in modo affidabile questi interruttori OEM.",
                ),
                language.pick("Open settings", "Apri impostazioni"),
                null,
            )
            StepKind.BACKGROUND_LOCATION -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                language.pick("Background location", "Posizione in background"),
                if (geofenceNeeded) {
                    language.pick(
                        "A geofence rule requires precise location and “Allow all the time” to detect entries and exits while the screen is off.",
                        "Una regola geofence richiede posizione precisa e «Consenti sempre» per rilevare entrate e uscite anche a schermo spento.",
                    )
                } else {
                    language.pick(
                        "Not required until you create a rule based on entering or leaving a place.",
                        "Non necessaria finché non crei una regola basata su entrata o uscita da un luogo.",
                    )
                },
                if (geofenceNeeded) language.pick("Open permissions", "Apri permessi") else null,
                null,
            )
        }
    }

    private fun advance() {
        val next = (currentIndex.value + 1).coerceAtMost(STEP_ORDER.lastIndex)
        savedStateHandle[CURRENT_INDEX_KEY] = next
    }

    private suspend fun message(text: String) {
        mutableEvents.emit(OnboardingEvent.Message(text))
    }

    private companion object {
        const val CURRENT_INDEX_KEY = "currentIndex"
        const val SKIPPED_KEY = "skippedSteps"
        val STEP_ORDER = listOf(
            StepKind.WELCOME_PRIVACY,
            StepKind.BRAIN_CONFIG,
            StepKind.SHIZUKU,
            StepKind.NOTIFICATION_ACCESS,
            StepKind.BATTERY_OEM,
            StepKind.BACKGROUND_LOCATION,
        )
    }
}
