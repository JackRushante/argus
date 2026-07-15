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
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftRepository
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuPermissionResult
import dev.argus.ui.model.OnboardingState
import dev.argus.ui.model.OnboardingStepState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.StepKind
import dev.argus.ui.model.StepStatus
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
    private val configuration: BridgeConfigurationStore,
    private val brain: ConfiguredBridgeBrain,
    private val shizuku: ShizukuGateway,
    automations: AutomationStore,
    drafts: DraftRepository,
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
                message("Impossibile salvare il consenso privacy.")
            }
        }
    }

    fun saveBridge(url: String, bearer: String?) {
        viewModelScope.launch {
            try {
                if (!configuration.saveConfiguration(url, bearer)) {
                    message(
                        "Configurazione non valida: usa HTTPS e un bearer ASCII di almeno 16 caratteri.",
                    )
                    return@launch
                }
                tokenConfigured.value = true
                message("Configurazione Hermes salvata in storage protetto.")
                when (brain.health()) {
                    is BridgeHealthResult.Reachable -> message("Hermes raggiungibile.")
                    is BridgeHealthResult.Unreachable -> message(
                        "Configurazione salvata; Hermes non è raggiungibile ora.",
                    )
                }
                advance()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile salvare la configurazione Hermes.")
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
                        "Autorizzazione Shizuku concessa."
                    }
                    ShizukuPermissionResult.DENIED -> "Autorizzazione Shizuku negata."
                    ShizukuPermissionResult.RATIONALE_REQUIRED -> {
                        shizukuRationaleShown = true
                        "Premi di nuovo il pulsante per confermare la richiesta Shizuku."
                    }
                    ShizukuPermissionResult.UNAVAILABLE -> "Shizuku non è disponibile."
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
                OnboardingEvent.Message("Completa i due passaggi obbligatori per usare Argus."),
            )
        }
    }

    fun onFinish() {
        val current = state.value
        if (!current.canFinish) {
            mutableEvents.tryEmit(
                OnboardingEvent.Message("Privacy e configurazione Hermes sono obbligatorie."),
            )
            return
        }
        viewModelScope.launch {
            if (preferences.setOnboardingCompleted(true)) {
                mutableEvents.emit(OnboardingEvent.Complete)
            } else {
                message("Impossibile completare la configurazione.")
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
        )
    }

    private fun step(
        kind: StepKind,
        shizukuStatus: ShizukuStatus,
        geofenceNeeded: Boolean,
        health: AndroidUiHealth,
    ): OnboardingStepState {
        val (shizukuBody, shizukuCta) = shizukuOnboardingCopy(shizukuStatus)
        return when (kind) {
            StepKind.WELCOME_PRIVACY -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                "Privacy e consenso",
                "Ciò che scrivi in chat viaggia verso Hermes, il tuo server, e da lì può raggiungere il provider cloud configurato. Le regole vengono sempre mostrate e approvate prima dell'attivazione.",
                "Ho capito, acconsento",
                null,
            )
            StepKind.BRAIN_CONFIG -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                "Collega Hermes",
                "Configura l'indirizzo HTTPS del bridge e il bearer. Il bearer viene cifrato con Android Keystore e non sarà più mostrato.",
                "Configura Hermes",
                null,
            )
            StepKind.SHIZUKU -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                "Autorizza Shizuku",
                shizukuBody,
                shizukuCta,
                null,
            )
            StepKind.NOTIFICATION_ACCESS -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                "Notifiche",
                if (!health.notificationsGranted) {
                    "Prima consenti ad Argus di mostrare esiti e avvisi. Poi servirà l'accesso " +
                        "alle notifiche per leggere i messaggi WhatsApp e rispondere."
                } else {
                    "Esiti e avvisi sono attivi. Ora concedi l'accesso alle notifiche: serve a " +
                        "leggere i messaggi WhatsApp in arrivo e a rispondere dalle regole approvate."
                },
                if (!health.notificationsGranted) "Concedi" else "Consenti lettura notifiche",
                null,
            )
            StepKind.BATTERY_OEM -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                "Ottimizzazione batteria",
                "Opzionale: riduce il rischio che OxygenOS ritardi il lavoro in background. Gli allarmi pianificati restano event-driven.",
                "Apri impostazioni",
                null,
            )
            StepKind.BACKGROUND_LOCATION -> OnboardingStepState(
                kind,
                StepStatus.TODO,
                "Posizione in background",
                if (geofenceNeeded) {
                    "Una regola geofence richiede posizione precisa e «Consenti sempre» per rilevare entrate e uscite anche a schermo spento."
                } else {
                    "Non necessaria finché non crei una regola basata su entrata o uscita da un luogo."
                },
                if (geofenceNeeded) "Apri permessi" else null,
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
