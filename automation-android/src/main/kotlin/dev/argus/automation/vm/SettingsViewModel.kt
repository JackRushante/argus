package dev.argus.automation.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AppPreferences
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.BridgeHealthResult
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.connectivity.ConnectivitySentinelStatus
import dev.argus.automation.PrivacyRevocationCoordinator
import dev.argus.automation.PrivacyRevocationResult
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Automation
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.Trigger
import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuPermissionResult
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.BudgetUi
import dev.argus.ui.model.ContactRow
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.SettingsState
import dev.argus.ui.model.TransportUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private data class BridgeUiHealth(
    val reachable: Boolean? = null,
    val latencyLabel: String? = null,
)

private data class SettingsSources(
    val contacts: List<WhitelistedContact>,
    val privacyAccepted: Boolean,
    val geofenceNeeded: Boolean,
    val shizukuNeeded: Boolean,
    val shizukuStatus: ShizukuGatewayStatus,
    val observedCandidates: List<ContactRow>,
)

/**
 * Candidate del picker whitelist: solo conversazioni WhatsApp confermate 1:1, non ancora in
 * whitelist, più recenti prima. L'inserimento manuale dell'hash resta diagnostica avanzata.
 */
internal fun observedWhitelistCandidates(
    observed: List<ObservedConversation>,
    whitelisted: List<WhitelistedContact>,
    limit: Int = 20,
): List<ContactRow> {
    val taken = whitelisted.mapTo(hashSetOf()) { it.id }
    return observed.asSequence()
        .filter { it.packageName in DraftValidator.WHATSAPP_PACKAGES }
        .filter { it.isGroup == false }
        .filterNot { it.id in taken }
        .take(limit)
        .map { ContactRow(displayName = it.displayName, conversationId = it.id) }
        .toList()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configuration: ProviderConfigStore,
    private val brain: ConfiguredBridgeBrain,
    private val whitelist: ContactWhitelistStore,
    private val preferences: AppPreferencesStore,
    private val privacyRevocation: PrivacyRevocationCoordinator,
    observedConversations: ObservedConversationStore,
    automations: AutomationStore,
    drafts: DraftRepository,
    private val shizuku: ShizukuGateway,
    private val connectivitySentinelStatus: ConnectivitySentinelStatus,
) : ViewModel() {
    private val refreshSignal = MutableStateFlow(0L)
    private val bridgeHealth = MutableStateFlow(BridgeUiHealth())
    private val mutableTokenConfigured = MutableStateFlow(false)
    val tokenConfigured: StateFlow<Boolean> = mutableTokenConfigured
    private var shizukuRationaleShown = false
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()

    private val sources = combine(
        whitelist.observeAll(),
        preferences.observe(),
        automations.observeAll(),
        drafts.observeAll(),
        shizuku.observeStatus(),
        observedConversations.observeRecent(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val contacts = values[0] as List<WhitelistedContact>
        val appPreferences = values[1] as AppPreferences

        @Suppress("UNCHECKED_CAST")
        val rules = values[2] as List<Automation>

        @Suppress("UNCHECKED_CAST")
        val pending = values[3] as List<PendingDraft>
        val shizukuStatus = values[4] as ShizukuGatewayStatus

        @Suppress("UNCHECKED_CAST")
        val observed = values[5] as List<ObservedConversation>
        val geofenceNeeded = rules.any { it.trigger is Trigger.Geofence } ||
            pending.any { it.draft.trigger is Trigger.Geofence }
        val shizukuNeeded = rules.any { rule ->
            rule.requiredCapabilities.any(AndroidCapabilityProbe.SHIZUKU_CAPABILITIES::contains)
        } || pending.any { snapshot ->
            CapabilityRequirements.derive(
                snapshot.draft.trigger,
                snapshot.draft.actions,
                snapshot.draft.conditions,
            ).any(AndroidCapabilityProbe.SHIZUKU_CAPABILITIES::contains)
        }
        SettingsSources(
            contacts = contacts,
            privacyAccepted = appPreferences.privacyAccepted,
            geofenceNeeded = geofenceNeeded,
            shizukuNeeded = shizukuNeeded,
            shizukuStatus = shizukuStatus,
            observedCandidates = observedWhitelistCandidates(observed, contacts),
        )
    }

    val state: StateFlow<SettingsState> = combine(
        sources,
        bridgeHealth,
        tokenConfigured,
        refreshSignal,
        connectivitySentinelStatus.active,
    ) { values, bridge, hasToken, _, sentinelActive ->
        val health = readAndroidUiHealth(context)
        SettingsState(
            transport = transportUi(bridge, hasToken),
            providerChoices = providerChoices(),
            shizuku = values.shizukuStatus.toUiStatus(
                degradedAfterReboot = values.shizukuNeeded &&
                    values.shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ),
            batteryExempt = health.batteryExempt,
            notificationsGranted = health.notificationsGranted,
            notificationListenerGranted = health.notificationListenerGranted,
            backgroundLocation = health.backgroundLocationState(values.geofenceNeeded),
            smsTriggerGranted = health.receiveSmsGranted,
            callTriggerGranted = health.readPhoneStateGranted && health.readCallLogGranted,
            bluetoothTriggerGranted = health.bluetoothConnectGranted,
            connectivitySentinelActive = sentinelActive,
            whitelist = values.contacts.map { ContactRow(it.displayName, it.id) },
            observedCandidates = values.observedCandidates,
            budget = BudgetUi(
                maxCallsPerHour = 0,
                usedThisHourLabel = "Ogni regola con risposta AI può scattare al massimo " +
                    "una volta ogni 60 secondi. Un limite orario complessivo di chiamate " +
                    "arriverà in una versione futura.",
            ),
            privacyAccepted = values.privacyAccepted,
            appVersionLabel = appVersionLabel(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        initialState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        refreshSignal.value = System.nanoTime()
        viewModelScope.launch {
            mutableTokenConfigured.value = cancellationSafeOrNull {
                configuration.bearerToken() != null
            } ?: false
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
                mutableTokenConfigured.value = true
                bridgeHealth.value = BridgeUiHealth()
                refreshSignal.value = System.nanoTime()
                testConnectionInternal(savedMessage = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile salvare la configurazione Hermes.")
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            try {
                testConnectionInternal(savedMessage = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                bridgeHealth.value = BridgeUiHealth(reachable = false)
                message("Impossibile verificare Hermes.")
            }
        }
    }

    fun selectProvider(wireName: String) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableMessages.tryEmit("Provider sconosciuto.")
            return
        }
        viewModelScope.launch {
            if (configuration.selectProvider(id)) {
                // La reachability misurata su un provider non vale per il nuovo selezionato.
                bridgeHealth.value = BridgeUiHealth()
                refresh()
            } else {
                message("Impossibile selezionare il provider.")
            }
        }
    }

    fun saveProviderConfig(wireName: String, baseUrl: String?, model: String?, apiKey: String?) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableMessages.tryEmit("Provider sconosciuto.")
            return
        }
        viewModelScope.launch {
            try {
                if (!configuration.saveProviderConfig(id, baseUrl = baseUrl, model = model, apiKey = apiKey)) {
                    message("Configurazione non valida.")
                    return@launch
                }
                bridgeHealth.value = BridgeUiHealth()
                refresh()
                if (id == configuration.selectedProviderId()) testConnectionInternal(savedMessage = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile salvare la configurazione del provider.")
            }
        }
    }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            val text = when (shizuku.requestPermission(rationaleShown = shizukuRationaleShown)) {
                ShizukuPermissionResult.GRANTED -> {
                    shizukuRationaleShown = false
                    "Autorizzazione Shizuku concessa."
                }
                ShizukuPermissionResult.DENIED -> "Autorizzazione Shizuku negata."
                ShizukuPermissionResult.RATIONALE_REQUIRED -> {
                    shizukuRationaleShown = true
                    "Premi di nuovo Correggi per confermare la richiesta Shizuku."
                }
                ShizukuPermissionResult.UNAVAILABLE -> "Shizuku non è disponibile."
            }
            message(text)
            refresh()
        }
    }

    fun addContact(displayName: String, conversationId: String) {
        viewModelScope.launch {
            try {
                whitelist.upsert(WhitelistedContact(displayName.trim(), conversationId.trim()))
                message("Contatto aggiunto alla whitelist.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalArgumentException) {
                message("Nome o conversation ID non valido.")
            } catch (_: Exception) {
                message("Impossibile aggiornare la whitelist.")
            }
        }
    }

    fun removeContact(conversationId: String) {
        viewModelScope.launch {
            try {
                whitelist.remove(conversationId)
                message("Contatto rimosso dalla whitelist.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile rimuovere il contatto.")
            }
        }
    }

    fun onBudgetChange(maxPerHour: Int) {
        mutableMessages.tryEmit(
            "Il budget globale arriverà in una fase successiva (P3); " +
                "in P1 ogni regola generativa ha un cooldown minimo di 60 secondi.",
        )
    }

    fun revokePrivacy() {
        viewModelScope.launch {
            try {
                when (privacyRevocation.revoke()) {
                    PrivacyRevocationResult.Revoked -> message(
                        "Consenso revocato: conversazioni osservate e risposte differite eliminate.",
                    )
                    PrivacyRevocationResult.RevokedWithResidualData -> message(
                        "Consenso revocato, ma alcuni dati locali non sono stati eliminati: riprova.",
                    )
                    PrivacyRevocationResult.Failed ->
                        message("Impossibile revocare il consenso privacy.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message("Impossibile revocare il consenso privacy.")
            }
        }
    }

    private suspend fun message(text: String) {
        mutableMessages.emit(text)
    }

    private suspend fun testConnectionInternal(savedMessage: Boolean) {
        bridgeHealth.value = BridgeUiHealth()
        val label = ProviderCatalog.spec(configuration.selectedProviderId()).displayName
        when (val result = brain.health()) {
            is BridgeHealthResult.Reachable -> {
                bridgeHealth.value = BridgeUiHealth(
                    reachable = true,
                    latencyLabel = latencyLabel(result.latencyMillis),
                )
                message(
                    if (savedMessage) "Configurazione protetta salvata; $label è raggiungibile."
                    else "$label raggiungibile.",
                )
            }
            is BridgeHealthResult.Unreachable -> {
                bridgeHealth.value = BridgeUiHealth(reachable = false)
                val suffix = result.kind.name.lowercase(Locale.ROOT)
                message(
                    if (savedMessage) "Configurazione salvata; $label non raggiungibile ($suffix)."
                    else "$label non raggiungibile ($suffix).",
                )
            }
        }
    }

    private fun transportUi(bridge: BridgeUiHealth, hasToken: Boolean): TransportUi {
        val id = configuration.selectedProviderId()
        val config = configuration.providerConfig(id)
        return if (id == ProviderId.HERMES) {
            TransportUi.CliBridge(
                url = config.baseUrl,
                reachable = bridge.reachable,
                lastLatencyLabel = bridge.latencyLabel,
                tokenConfigured = hasToken,
            )
        } else {
            val spec = ProviderCatalog.spec(id)
            TransportUi.DirectProvider(
                providerId = id.wireName,
                providerLabel = spec.displayName,
                baseUrl = config.baseUrl,
                model = config.model,
                authState = if (hasToken) AuthState.OK else AuthState.NOT_CONFIGURED,
                reachable = bridge.reachable,
                lastLatencyLabel = bridge.latencyLabel,
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

    private fun initialState(): SettingsState {
        val health = readAndroidUiHealth(context)
        return SettingsState(
            transport = transportUi(BridgeUiHealth(), hasToken = false),
            providerChoices = providerChoices(),
            shizuku = shizuku.status().toUiStatus(false),
            batteryExempt = health.batteryExempt,
            notificationsGranted = health.notificationsGranted,
            notificationListenerGranted = health.notificationListenerGranted,
            backgroundLocation = health.backgroundLocationState(false),
            smsTriggerGranted = health.receiveSmsGranted,
            callTriggerGranted = health.readPhoneStateGranted && health.readCallLogGranted,
            bluetoothTriggerGranted = health.bluetoothConnectGranted,
            connectivitySentinelActive = connectivitySentinelStatus.active.value,
            whitelist = emptyList(),
            budget = BudgetUi(
                0,
                "Ogni regola con risposta AI può scattare al massimo una volta ogni " +
                    "60 secondi. Un limite orario complessivo di chiamate arriverà in " +
                    "una versione futura.",
            ),
            privacyAccepted = false,
            appVersionLabel = appVersionLabel(),
        )
    }

    private fun appVersionLabel(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Argus v${info.versionName ?: "?"} · sideload"
    }.getOrDefault("Argus · sideload")

    private fun latencyLabel(millis: Long): String = when {
        millis < 1_000 -> "$millis ms"
        else -> String.format(Locale.ITALIAN, "%.1f s", millis / 1_000.0)
    }
}
