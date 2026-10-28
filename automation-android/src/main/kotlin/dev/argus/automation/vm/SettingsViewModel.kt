package dev.argus.automation.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AppInstallInfo
import dev.argus.automation.AppPreferences
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.BridgeHealthResult
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.connectivity.ConnectivitySentinelStatus
import dev.argus.automation.PrivacyRevocationCoordinator
import dev.argus.automation.PrivacyRevocationResult
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.manual.AutomationPauseStore
import dev.argus.automation.budget.BudgetLimits
import dev.argus.automation.budget.BudgetSettings
import dev.argus.automation.budget.BudgetSettingsStore
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.brain.ReasoningEffort
import dev.argus.data.UsageWindows
import dev.argus.data.backup.ExportOutcome
import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.ImportOutcome
import dev.argus.data.backup.RulesBackup
import dev.argus.data.backup.isBackupExportable
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
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
import dev.argus.shizuku.ShizukuAccess
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuIdentity
import dev.argus.shizuku.ShizukuIdentityProbe
import dev.argus.shizuku.ShizukuPermissionOutcome
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.BudgetUi
import dev.argus.ui.model.ContactRow
import dev.argus.ui.model.PermanentGrantUi
import dev.argus.ui.model.PrivilegedBackendUi
import dev.argus.ui.model.PrivilegedUi
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.ProviderUsageUi
import dev.argus.ui.model.RulesBackupKind
import dev.argus.ui.model.RulesBackupStatus
import dev.argus.ui.model.RulesBackupUi
import dev.argus.ui.model.SettingsState
import dev.argus.ui.model.TransportUi
import dev.argus.ui.presentation.ModelFieldHints
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import dev.argus.automation.privileged.PrivilegedAccessCenter
import dev.argus.automation.privileged.toDomain
import dev.argus.automation.privileged.toUi
import dev.argus.automation.privileged.message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.ceil

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
    val shizukuIdentity: ShizukuIdentity?,
    val observedCandidates: List<ContactRow>,
    /** Regole che finirebbero nel file di backup (0.4.0, F1). */
    val exportableRules: Int = 0,
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

/** Aggregati usage per le tre finestre (ora/giorno/mese), caricati da [UsageDao] in [SettingsViewModel.refresh]. */
internal data class BudgetUsageSnapshot(
    val hour: List<ProviderUsageAggregate> = emptyList(),
    val day: List<ProviderUsageAggregate> = emptyList(),
    val month: List<ProviderUsageAggregate> = emptyList(),
)

/** Chiamate effettive di un aggregato: i tentativi bloccati dal budget NON contano (T: no lock-out visivo). */
private fun ProviderUsageAggregate.netCalls(): Long = (calls - blockedCalls).coerceAtLeast(0L)

private fun List<ProviderUsageAggregate>.totalNetCalls(): Long = sumOf { it.netCalls() }

/** true se il wireName appartiene a un provider a costo tracciato (listino noto nel catalog). */
private fun costTrackedWire(wire: String): Boolean =
    ProviderId.fromWireName(wire)?.let { ProviderCatalog.spec(it).costTracked } ?: false

/**
 * Somma dei costi dei SOLI provider costTracked: i token-only (Hermes/OpenRouter/Custom) non hanno
 * dollari, nemmeno per eventuali costMicros legacy in tabella. Tutti null → null, non 0.
 */
private fun List<ProviderUsageAggregate>.totalCostMicros(): Long? {
    val present = filter { costTrackedWire(it.providerId) }.mapNotNull { it.costMicros }
    return if (present.isEmpty()) null else present.sum()
}

/** Somma nullable: se TUTTI i valori sono null (n/d) il totale resta null, non 0. */
private fun List<Long?>.sumOrNull(): Long? {
    val present = filterNotNull()
    return if (present.isEmpty()) null else present.sum()
}

/** Token totali in+out di un provider; null solo se entrambe le colonne sono n/d. */
private fun totalTokens(tokensIn: Long?, tokensOut: Long?): Long? =
    if (tokensIn == null && tokensOut == null) null else (tokensIn ?: 0L) + (tokensOut ?: 0L)

private fun overSoftCalls(used: Long, limit: Int?, softPct: Int): Boolean {
    if (limit == null || limit <= 0) return false
    val threshold = ceil(limit.toDouble() * softPct / 100.0).toLong()
    return used >= threshold
}

/** Soglia soft generica su quantità Long (micro-USD o token): null usato pesa 0. */
private fun overSoftAmount(used: Long?, limit: Long?, softPct: Int): Boolean {
    if (limit == null || limit <= 0L) return false
    val threshold = ceil(limit.toDouble() * softPct / 100.0).toLong()
    return (used ?: 0L) >= threshold
}

/**
 * Mappa gli aggregati usage + i limiti configurati nel contratto UI tipizzato [BudgetUi]. Puro e
 * testabile su host. Chiamate = calls - blockedCalls; costo mese = somma costMicros dei soli provider
 * costTracked (tutti null → null, non 0); token mese = somma in/out dei soli provider token-only.
 * Il breakdown per-provider è ordinato per wireName con label dal [ProviderCatalog].
 */
internal fun budgetUi(usage: BudgetUsageSnapshot, settings: BudgetSettings): BudgetUi {
    val global = settings.global
    val softPct = settings.softThresholdPct
    val usedHour = usage.hour.totalNetCalls()
    val usedDay = usage.day.totalNetCalls()
    val costMonth = usage.month.totalCostMicros()
    val tokenOnlyMonth = usage.month.filterNot { costTrackedWire(it.providerId) }
    val tokensInMonth = tokenOnlyMonth.map { it.tokensIn }.sumOrNull()
    val tokensOutMonth = tokenOnlyMonth.map { it.tokensOut }.sumOrNull()

    val wireNames = (
        usage.hour.map { it.providerId } +
            usage.day.map { it.providerId } +
            usage.month.map { it.providerId }
        ).toSortedSet()
    val perProvider = wireNames.map { wire ->
        val hourAgg = usage.hour.firstOrNull { it.providerId == wire }
        val dayAgg = usage.day.firstOrNull { it.providerId == wire }
        val monthAgg = usage.month.firstOrNull { it.providerId == wire }
        val label = ProviderId.fromWireName(wire)
            ?.let { ProviderCatalog.spec(it).displayName } ?: wire
        val tracked = costTrackedWire(wire)
        ProviderUsageUi(
            providerId = wire,
            providerLabel = label,
            callsHour = hourAgg?.netCalls() ?: 0L,
            callsDay = dayAgg?.netCalls() ?: 0L,
            // Dollari SOLO per i priced: un token-only non mostra mai costi, neanche legacy.
            costMonthMicros = if (tracked) monthAgg?.costMicros else null,
            costTracked = tracked,
            tokensInHour = hourAgg?.tokensIn,
            tokensOutHour = hourAgg?.tokensOut,
            tokensInDay = dayAgg?.tokensIn,
            tokensOutDay = dayAgg?.tokensOut,
            tokensInMonth = monthAgg?.tokensIn,
            tokensOutMonth = monthAgg?.tokensOut,
            reasoningTokensMonth = monthAgg?.reasoningTokens,
            lastFinishReason = hourAgg?.lastFinishReason
                ?: dayAgg?.lastFinishReason
                ?: monthAgg?.lastFinishReason,
        )
    }

    var softActive = overSoftCalls(usedHour, global.maxCallsPerHour, softPct) ||
        overSoftCalls(usedDay, global.maxCallsPerDay, softPct) ||
        overSoftAmount(costMonth, global.maxCostPerMonthMicros, softPct) ||
        overSoftAmount(totalTokens(tokensInMonth, tokensOutMonth), global.maxTokensPerMonth, softPct)
    if (!softActive) {
        for ((wire, limits) in settings.perProvider) {
            val hourAgg = usage.hour.firstOrNull { it.providerId == wire }
            val dayAgg = usage.day.firstOrNull { it.providerId == wire }
            val monthAgg = usage.month.firstOrNull { it.providerId == wire }
            // Tetto mensile per natura del provider: costo per i priced, token per i token-only.
            val monthlySoft = if (costTrackedWire(wire)) {
                overSoftAmount(monthAgg?.costMicros, limits.maxCostPerMonthMicros, softPct)
            } else {
                overSoftAmount(
                    totalTokens(monthAgg?.tokensIn, monthAgg?.tokensOut),
                    limits.maxTokensPerMonth,
                    softPct,
                )
            }
            if (overSoftCalls(hourAgg?.netCalls() ?: 0L, limits.maxCallsPerHour, softPct) ||
                overSoftCalls(dayAgg?.netCalls() ?: 0L, limits.maxCallsPerDay, softPct) ||
                monthlySoft
            ) {
                softActive = true
                break
            }
        }
    }

    return BudgetUi(
        usedHour = usedHour,
        limitHour = global.maxCallsPerHour,
        usedDay = usedDay,
        limitDay = global.maxCallsPerDay,
        costMonthMicros = costMonth,
        costLimitMicros = global.maxCostPerMonthMicros,
        tokensInMonth = tokensInMonth,
        tokensOutMonth = tokensOutMonth,
        tokenLimitMonth = global.maxTokensPerMonth,
        perProvider = perProvider,
        softWarningActive = softActive,
    )
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

private const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

/**
 * URL base Custom senza il suffisso `/chat/completions` (con o senza slash finale), oppure null se non
 * lo ha: Argus aggiunge da solo quel segmento, quindi lasciarlo darebbe un 404.
 */
internal fun withoutChatCompletions(baseUrl: String): String? {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (!trimmed.endsWith(CHAT_COMPLETIONS_SUFFIX, ignoreCase = true)) return null
    return trimmed.dropLast(CHAT_COMPLETIONS_SUFFIX.length)
}

/** true se l'host dell'URL è esattamente uno dei loopback ammessi per il cleartext del Custom. */
internal fun isLoopbackBaseUrl(baseUrl: String): Boolean {
    val host = runCatching { URI(baseUrl.trim()).host }.getOrNull() ?: return false
    return host.removeSurrounding("[", "]").lowercase(Locale.ROOT) in LOOPBACK_HOSTS
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
    private val shizuku: ShizukuAccess,
    private val shizukuIdentityProbe: ShizukuIdentityProbe,
    private val connectivitySentinelStatus: ConnectivitySentinelStatus,
    private val usage: UsageDao,
    private val budgetSettings: BudgetSettingsStore,
    private val languageSource: RenderLanguageSource,
    private val installInfo: AppInstallInfo,
    private val rulesBackup: RulesBackup,
    /** F21: interruttore globale «Pausa automazioni». */
    private val pause: AutomationPauseStore,
    /** 0.5.0: backend privilegiato, root `su` e permessi permanenti. `null` solo nei test che non li provano. */
    private val privileged: PrivilegedAccessCenter? = null,
) : ViewModel() {
    /** Lingua corrente al momento in cui si produce un testo (messaggi, errori); segue il telefono. */
    private val language: RenderLanguage get() = languageSource.current

    private val refreshSignal = MutableStateFlow(0L)
    private val budgetUsage = MutableStateFlow(BudgetUsageSnapshot())
    private val bridgeHealth = MutableStateFlow(BridgeUiHealth())
    private val mutableTokenConfigured = MutableStateFlow(false)

    /** Chiave realmente salvata per il provider selezionato; null = non ancora letta (niente avvisi). */
    private val mutableKeySaved = MutableStateFlow<Boolean?>(null)
    val tokenConfigured: StateFlow<Boolean> = mutableTokenConfigured
    private var shizukuRationaleShown = false
    private var identityProbeJob: Job? = null
    private val budgetWriteMutex = Mutex()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = mutableMessages.asSharedFlow()

    /** Ultima operazione del backup delle regole (0.4.0, F1): a riposo, in corso, riuscita, fallita. */
    private val mutableBackupStatus = MutableStateFlow<RulesBackupStatus>(RulesBackupStatus.Idle)
    private val backupBusy = AtomicBoolean(false)

    private val sources = combine(
        whitelist.observeAll(),
        preferences.observe(),
        automations.observeAll(),
        drafts.observeAll(),
        shizuku.observeStatus(),
        observedConversations.observeRecent(),
        shizuku.observeIdentity(),
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
        val shizukuIdentity = values[6] as ShizukuIdentity?
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
            shizukuIdentity = shizukuIdentity,
            observedCandidates = observedWhitelistCandidates(observed, contacts),
            exportableRules = rules.count(Automation::isBackupExportable),
        )
    }

    private val privilegedUi: Flow<PrivilegedUi> =
        privileged?.snapshots?.map { it.toUi() } ?: flowOf(PrivilegedUi())

    val state: StateFlow<SettingsState> = combine(
        sources,
        bridgeHealth,
        tokenConfigured,
        refreshSignal,
        connectivitySentinelStatus.active,
        budgetUsage,
        budgetSettings.observe(),
        mutableKeySaved,
        // Al cambio di lingua del telefono lo stato si rigenera (etichette e testi prodotti qui).
        languageSource.language,
        mutableBackupStatus,
        pause.paused,
        privilegedUi,
    ) { values: Array<Any?> ->
        val settingsSources = values[0] as SettingsSources
        val bridge = values[1] as BridgeUiHealth
        val hasToken = values[2] as Boolean
        // values[3] = refreshSignal, usato solo come trigger di ricarica.
        val sentinelActive = values[4] as Boolean
        val usageSnapshot = values[5] as BudgetUsageSnapshot
        val budgetConfig = values[6] as BudgetSettings
        val keySaved = values[7] as Boolean?
        // values[8] = lingua, usata solo come trigger di ricarica.
        val backupStatus = values[9] as RulesBackupStatus
        val automationsPaused = values[10] as Boolean
        val privilegedState = values[11] as PrivilegedUi
        val health = readAndroidUiHealth(context)
        SettingsState(
            transport = transportUi(bridge, hasToken, keySaved),
            providerChoices = providerChoices(),
            shizuku = settingsSources.shizukuStatus.toUiStatus(
                degradedAfterReboot = settingsSources.shizukuNeeded &&
                    settingsSources.shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ),
            // 0.5.0: con il backend root `su` (o Sui) i comandi girano come root: l'approvazione mostra
            // l'avviso «esegue comandi come root» come per Shizuku root.
            shizukuRunsAsRoot = if (
                privilegedState.backend == PrivilegedBackendUi.ROOT ||
                privilegedState.backend == PrivilegedBackendUi.SUI
            ) {
                true
            } else {
                settingsSources.shizukuIdentity?.runsAsRoot
            },
            batteryExempt = health.batteryExempt,
            exactAlarmsGranted = health.exactAlarmsGranted,
            writeSettingsGranted = health.writeSettingsGranted,
            notificationsGranted = health.notificationsGranted,
            notificationListenerGranted = health.notificationListenerGranted,
            backgroundLocation = health.backgroundLocationState(settingsSources.geofenceNeeded),
            smsTriggerGranted = health.receiveSmsGranted,
            callTriggerGranted = health.readPhoneStateGranted && health.readCallLogGranted,
            bluetoothTriggerGranted = health.bluetoothConnectGranted,
            connectivitySentinelActive = sentinelActive,
            whitelist = settingsSources.contacts.map { ContactRow(it.displayName, it.id) },
            observedCandidates = settingsSources.observedCandidates,
            budget = budgetUi(usageSnapshot, budgetConfig),
            privacyAccepted = settingsSources.privacyAccepted,
            appVersionLabel = appVersionLabel(),
            notificationListenerRestrictedHint =
                !health.notificationListenerGranted && installInfo.mayHaveRestrictedSettings,
            backup = RulesBackupUi(status = backupStatus, exportableRules = settingsSources.exportableRules),
            automationsPaused = automationsPaused,
            privileged = privilegedState,
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
        // Ritorno dalle impostazioni di sistema: i permessi si rileggono da Android.
        privileged?.refresh()
        viewModelScope.launch {
            mutableTokenConfigured.value = cancellationSafeOrNull {
                selectedProviderAuthConfigured()
            } ?: false
        }
        viewModelScope.launch {
            mutableKeySaved.value = cancellationSafeOrNull {
                configuration.hasApiKey(configuration.selectedProviderId())
            }
        }
        viewModelScope.launch {
            budgetUsage.value = cancellationSafeOrNull {
                loadBudgetUsage(System.currentTimeMillis())
            } ?: BudgetUsageSnapshot()
        }
    }

    private suspend fun loadBudgetUsage(nowMillis: Long): BudgetUsageSnapshot {
        val zone = ZoneId.systemDefault()
        val hourWin = UsageWindows.lastHour(nowMillis)
        val dayWin = UsageWindows.currentDay(nowMillis, zone)
        val monthWin = UsageWindows.currentMonth(nowMillis, zone)
        return BudgetUsageSnapshot(
            hour = usage.aggregateBetween(hourWin.startMillis, hourWin.endMillisExclusive),
            day = usage.aggregateBetween(dayWin.startMillis, dayWin.endMillisExclusive),
            month = usage.aggregateBetween(monthWin.startMillis, monthWin.endMillisExclusive),
        )
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
                mutableTokenConfigured.value = true
                bridgeHealth.value = BridgeUiHealth()
                refreshSignal.value = System.nanoTime()
                testConnectionInternal(savedMessage = true)
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

    fun testConnection() {
        viewModelScope.launch {
            try {
                testConnectionInternal(savedMessage = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                bridgeHealth.value = BridgeUiHealth(reachable = false)
                message(language.pick("Unable to check Hermes.", "Impossibile verificare Hermes."))
            }
        }
    }

    fun selectProvider(wireName: String) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableMessages.tryEmit(language.pick("Unknown provider.", "Provider sconosciuto."))
            return
        }
        viewModelScope.launch {
            if (configuration.selectProvider(id)) {
                // La reachability misurata su un provider non vale per il nuovo selezionato.
                bridgeHealth.value = BridgeUiHealth()
                refresh()
            } else {
                message(
                    language.pick("Unable to select the provider.", "Impossibile selezionare il provider."),
                )
            }
        }
    }

    fun saveProviderConfig(
        wireName: String,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
        reasoningEffort: String? = null,
    ) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableMessages.tryEmit(language.pick("Unknown provider.", "Provider sconosciuto."))
            return
        }
        val effort = reasoningEffort?.let { value ->
            ReasoningEffort.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: run {
                    mutableMessages.tryEmit(language.pick("Invalid reasoning mode.", "Modalità reasoning non valida."))
                    return
                }
        }
        // Custom: l'URL base non deve finire con /chat/completions (Argus lo aggiunge da solo).
        val strippedBaseUrl = baseUrl?.takeIf { id == ProviderId.CUSTOM_OPENAI_COMPAT }
            ?.let(::withoutChatCompletions)
        val cleanBaseUrl = strippedBaseUrl ?: baseUrl
        val strippedSuffix = strippedBaseUrl != null
        viewModelScope.launch {
            try {
                if (!configuration.saveProviderConfig(
                        id, baseUrl = cleanBaseUrl, model = model, apiKey = apiKey, reasoningEffort = effort,
                    )
                ) {
                    message(language.pick("Invalid configuration.", "Configurazione non valida."))
                    return@launch
                }
                if (strippedSuffix) {
                    message(
                        language.pick(
                            "I removed /chat/completions from the address: Argus adds it by itself.",
                            "Ho tolto /chat/completions dall'indirizzo: Argus lo aggiunge da solo.",
                        ),
                    )
                }
                // Preset OpenRouter scritto `@presets/…`: si avvisa, ma il valore non si corregge da solo.
                if (id == ProviderId.OPENROUTER && ModelFieldHints.isMistypedPreset(model)) {
                    message(language.pick("The format is @preset/<name>.", "Il formato è @preset/<nome>."))
                }
                bridgeHealth.value = BridgeUiHealth()
                refresh()
                if (id == configuration.selectedProviderId()) testConnectionInternal(savedMessage = true)
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
            val outcome = shizuku.requestPermissionOutcome(rationaleShown = shizukuRationaleShown)
            val text = when (outcome) {
                ShizukuPermissionOutcome.GRANTED -> {
                    shizukuRationaleShown = false
                    language.pick(
                        "Shizuku authorization granted.",
                        "Autorizzazione Shizuku concessa.",
                    )
                }
                ShizukuPermissionOutcome.DENIED -> language.pick(
                    "Shizuku authorization denied.",
                    "Autorizzazione Shizuku negata.",
                )
                ShizukuPermissionOutcome.RATIONALE_REQUIRED -> {
                    shizukuRationaleShown = true
                    language.pick(
                        "Tap Fix again to confirm the Shizuku request.",
                        "Premi di nuovo Correggi per confermare la richiesta Shizuku.",
                    )
                }
                ShizukuPermissionOutcome.UNAVAILABLE -> language.pick(
                    "Shizuku is unavailable.",
                    "Shizuku non è disponibile.",
                )
                // La finestra di Shizuku può essere ancora aperta: se l'utente risponde dopo, lo
                // stato arriva da solo da observeStatus(), senza un nuovo refresh().
                ShizukuPermissionOutcome.AWAITING_USER -> shizukuAwaitingAnswerNote(language)
            }
            message(text)
            refresh()
            // Appena autorizzato si può già dire con quali privilegi girano i comandi.
            if (outcome == ShizukuPermissionOutcome.GRANTED) onSystemVisible()
        }
    }

    /**
     * Sistema è visibile. Con Shizuku autorizzato e identità non ancora nota il servizio
     * privilegiato si collega solo al primo comando: la sonda lo collega ora, una volta, per poter
     * dire «Shizuku gira come root» (D10). Nessun ciclo e nessun ritentativo: se la sonda non
     * riesce l'identità resta ignota e le etichette restano quelle neutre.
     */
    fun onSystemVisible() {
        if (identityProbeJob?.isActive == true) return
        if (shizuku.status() != ShizukuGatewayStatus.AUTHORIZED) return
        if (shizuku.observeIdentity().value != null) return
        identityProbeJob = viewModelScope.launch {
            cancellationSafeOrNull { shizukuIdentityProbe.probe() }
        }
    }

    /** Interruttore «Usa root (su)» (0.5.0): acceso verifica `su -c id`; spento chiude la sessione. */
    fun setRootEnabled(enabled: Boolean) {
        val center = privileged ?: return
        viewModelScope.launch {
            val result = cancellationSafeOrNull { center.setRootEnabled(enabled) } ?: return@launch
            result.message(language)?.let { message(it) }
        }
    }

    /** «Concedi i permessi permanenti» (0.5.0): solo le voci spuntate nel dialogo. */
    fun grantPermanent(grants: Set<PermanentGrantUi>) {
        val center = privileged ?: return
        val selected = grants.mapTo(linkedSetOf()) { it.toDomain() }
        viewModelScope.launch {
            val report = cancellationSafeOrNull { center.grant(selected) } ?: return@launch
            message(report.message(language))
        }
    }

    fun addContact(displayName: String, conversationId: String) {
        viewModelScope.launch {
            try {
                whitelist.upsert(WhitelistedContact(displayName.trim(), conversationId.trim()))
                message(
                    language.pick(
                        "Contact added to the whitelist.",
                        "Contatto aggiunto alla whitelist.",
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalArgumentException) {
                message(
                    language.pick(
                        "Invalid name or conversation ID.",
                        "Nome o conversation ID non valido.",
                    ),
                )
            } catch (_: Exception) {
                message(
                    language.pick("Unable to update the whitelist.", "Impossibile aggiornare la whitelist."),
                )
            }
        }
    }

    fun removeContact(conversationId: String) {
        viewModelScope.launch {
            try {
                whitelist.remove(conversationId)
                message(
                    language.pick(
                        "Contact removed from the whitelist.",
                        "Contatto rimosso dalla whitelist.",
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(
                    language.pick("Unable to remove the contact.", "Impossibile rimuovere il contatto."),
                )
            }
        }
    }

    /**
     * Salvataggio unico dei quattro tetti globali: UNA scrittura e UN messaggio. 0 = illimitato.
     * Il dialog usa solo questo; i quattro metodi singoli restano per i chiamanti esistenti.
     */
    fun onSaveBudgetLimits(
        maxPerHour: Int,
        maxPerDay: Int,
        maxCostMonthMicros: Long,
        maxTokensMonth: Long,
    ) {
        // Un valore negativo non è «illimitato»: si rifiuta l'intero salvataggio senza toccare nulla.
        if (maxPerHour < 0 || maxPerDay < 0 || maxCostMonthMicros < 0 || maxTokensMonth < 0) {
            mutableMessages.tryEmit(language.pick("Invalid limit.", "Limite non valido."))
            return
        }
        updateGlobalLimits {
            BudgetLimits(
                maxCallsPerHour = maxPerHour.takeIf { v -> v > 0 },
                maxCallsPerDay = maxPerDay.takeIf { v -> v > 0 },
                maxCostPerMonthMicros = maxCostMonthMicros.takeIf { v -> v > 0 },
                maxTokensPerMonth = maxTokensMonth.takeIf { v -> v > 0 },
            )
        }
    }

    fun onBudgetChange(maxPerHour: Int) =
        updateGlobalLimits { it.copy(maxCallsPerHour = maxPerHour.takeIf { v -> v > 0 }) }

    fun onBudgetDayChange(maxPerDay: Int) =
        updateGlobalLimits { it.copy(maxCallsPerDay = maxPerDay.takeIf { v -> v > 0 }) }

    fun onBudgetMonthlyCostChange(maxCostMonthMicros: Long) =
        updateGlobalLimits { it.copy(maxCostPerMonthMicros = maxCostMonthMicros.takeIf { v -> v > 0 }) }

    fun onBudgetMonthlyTokensChange(maxTokensPerMonth: Long) =
        updateGlobalLimits { it.copy(maxTokensPerMonth = maxTokensPerMonth.takeIf { v -> v > 0 }) }

    /**
     * Lettura dello snapshot e scrittura sotto lo stesso mutex: due aggiornamenti ravvicinati non
     * partono più dallo stesso stato vecchio (l'ultimo non cancella più i precedenti).
     */
    private fun updateGlobalLimits(mutate: (BudgetLimits) -> BudgetLimits) {
        viewModelScope.launch {
            try {
                val saved = budgetWriteMutex.withLock {
                    val current = budgetSettings.observe().value.global
                    budgetSettings.setGlobalLimits(mutate(current))
                }
                if (saved) {
                    message(language.pick("Budget limits saved.", "Limiti budget salvati."))
                    refresh()
                } else {
                    message(language.pick("Invalid limit.", "Limite non valido."))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(
                    language.pick(
                        "Unable to save budget limits.",
                        "Impossibile salvare i limiti budget.",
                    ),
                )
            }
        }
    }

    /**
     * Esporta le regole nel documento scelto dall'utente (SAF `CreateDocument`). [destination] null =
     * selettore annullato: non succede nulla. Un'operazione alla volta.
     */
    fun exportRules(destination: Uri?, stripPersonalData: Boolean) {
        if (destination == null) return
        runBackup(RulesBackupKind.EXPORT) {
            when (val outcome = rulesBackup.export(destination, stripPersonalData)) {
                is ExportOutcome.Done -> RulesBackupStatus.Done(
                    RulesBackupKind.EXPORT,
                    RulesBackupText.exportDone(outcome, language),
                )
                is ExportOutcome.Failed -> RulesBackupStatus.Failed(
                    RulesBackupKind.EXPORT,
                    RulesBackupText.failure(outcome.failure, language),
                )
            }
        }
    }

    /**
     * Importa le regole dal documento scelto (SAF `OpenDocument`): ogni regola valida diventa una bozza
     * da approvare, mai una regola armata. [source] null = selettore annullato.
     */
    fun importRules(source: Uri?) {
        if (source == null) return
        runBackup(RulesBackupKind.IMPORT) {
            when (val outcome = rulesBackup.import(source)) {
                is ImportOutcome.Done -> {
                    val text = RulesBackupText.importSummary(outcome.summary, language)
                    // Nessuna bozza creata = l'import non ha prodotto nulla: si mostra come errore.
                    if (outcome.summary.imported > 0) {
                        RulesBackupStatus.Done(RulesBackupKind.IMPORT, text, outcome.summary.imported)
                    } else {
                        RulesBackupStatus.Failed(RulesBackupKind.IMPORT, text)
                    }
                }
                is ImportOutcome.Failed -> RulesBackupStatus.Failed(
                    RulesBackupKind.IMPORT,
                    RulesBackupText.failure(outcome.failure, language),
                )
            }
        }
    }

    /** Chiude il riepilogo o l'errore dell'ultima operazione (non interrompe una in corso). */
    fun dismissBackupResult() {
        if (backupBusy.get()) return
        mutableBackupStatus.value = RulesBackupStatus.Idle
    }

    private fun runBackup(kind: RulesBackupKind, operation: suspend () -> RulesBackupStatus) {
        if (!backupBusy.compareAndSet(false, true)) return
        mutableBackupStatus.value = RulesBackupStatus.Working(kind)
        viewModelScope.launch {
            try {
                mutableBackupStatus.value = try {
                    operation()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    val failure = if (kind == RulesBackupKind.EXPORT) {
                        BackupFailure.WRITE_FAILED
                    } else {
                        BackupFailure.READ_FAILED
                    }
                    RulesBackupStatus.Failed(kind, RulesBackupText.failure(failure, language))
                }
            } finally {
                backupBusy.set(false)
            }
        }
    }

    /** F21: accende o spegne "Pausa automazioni". */
    fun setAutomationsPaused(paused: Boolean) = pause.setPaused(paused)

    fun revokePrivacy() {
        viewModelScope.launch {
            try {
                when (privacyRevocation.revoke()) {
                    PrivacyRevocationResult.Revoked -> message(
                        language.pick(
                            "Consent revoked: observed conversations and deferred replies deleted.",
                            "Consenso revocato: conversazioni osservate e risposte differite eliminate.",
                        ),
                    )
                    PrivacyRevocationResult.RevokedWithResidualData -> message(
                        language.pick(
                            "Consent revoked, but some local data was not deleted; try again.",
                            "Consenso revocato, ma alcuni dati locali non sono stati eliminati: riprova.",
                        ),
                    )
                    PrivacyRevocationResult.Failed ->
                        message(
                            language.pick(
                                "Unable to revoke privacy consent.",
                                "Impossibile revocare il consenso privacy.",
                            ),
                        )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(
                    language.pick(
                        "Unable to revoke privacy consent.",
                        "Impossibile revocare il consenso privacy.",
                    ),
                )
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
                    if (savedMessage) {
                        language.pick(
                            "Protected configuration saved; $label is reachable.",
                            "Configurazione protetta salvata; $label è raggiungibile.",
                        )
                    } else {
                        language.pick("$label is reachable.", "$label raggiungibile.")
                    },
                )
            }
            is BridgeHealthResult.Unreachable -> {
                bridgeHealth.value = BridgeUiHealth(reachable = false)
                // La causa (rete, chiave, credito, URL, modello…) va detta: il messaggio del provider,
                // già ripulito, compare solo qui e non viene né registrato né salvato.
                val reason = connectionFailureText(
                    label = label,
                    failure = result,
                    baseUrl = runCatching { configuration.providerConfig(configuration.selectedProviderId()).baseUrl }
                        .getOrNull(),
                    language = language,
                )
                message(
                    if (savedMessage) {
                        language.pick("Configuration saved; $reason", "Configurazione salvata; $reason")
                    } else {
                        reason
                    },
                )
            }
        }
    }

    private fun transportUi(bridge: BridgeUiHealth, hasToken: Boolean, keySaved: Boolean? = null): TransportUi {
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
                authState = if (hasToken || !spec.apiKeyRequired) AuthState.OK else AuthState.NOT_CONFIGURED,
                reachable = bridge.reachable,
                lastLatencyLabel = bridge.latencyLabel,
                defaultModels = spec.defaultModels,
                baseUrlEditable = id == ProviderId.CUSTOM_OPENAI_COMPAT,
                apiKeyPrefixHint = spec.apiKeyPrefixHint,
                apiKeyRequired = spec.apiKeyRequired,
                reasoningEffort = config.reasoningEffort.name.lowercase(Locale.ROOT),
                reasoningEffortEditable = spec.reasoningEffortEditable,
                unauthenticatedLoopback = id == ProviderId.CUSTOM_OPENAI_COMPAT &&
                    keySaved == false &&
                    isLoopbackBaseUrl(config.baseUrl),
                modelPresetHint = id == ProviderId.OPENROUTER,
                localPresets = localPresetsUi(id),
            )
        }
    }

    private fun providerChoices(): List<ProviderChoiceUi> {
        val selected = configuration.selectedProviderId()
        return ProviderCatalog.specs.values.map {
            ProviderChoiceUi(it.id.wireName, it.displayName, it.id == selected)
        }
    }

    private suspend fun selectedProviderAuthConfigured(): Boolean {
        val id = configuration.selectedProviderId()
        return !ProviderCatalog.spec(id).apiKeyRequired || configuration.hasApiKey(id)
    }

    private fun initialState(): SettingsState {
        val health = readAndroidUiHealth(context)
        return SettingsState(
            transport = transportUi(BridgeUiHealth(), hasToken = false),
            providerChoices = providerChoices(),
            shizuku = shizuku.status().toUiStatus(false),
            shizukuRunsAsRoot = shizuku.observeIdentity().value?.runsAsRoot,
            batteryExempt = health.batteryExempt,
            exactAlarmsGranted = health.exactAlarmsGranted,
            writeSettingsGranted = health.writeSettingsGranted,
            notificationsGranted = health.notificationsGranted,
            notificationListenerGranted = health.notificationListenerGranted,
            backgroundLocation = health.backgroundLocationState(false),
            smsTriggerGranted = health.receiveSmsGranted,
            callTriggerGranted = health.readPhoneStateGranted && health.readCallLogGranted,
            bluetoothTriggerGranted = health.bluetoothConnectGranted,
            connectivitySentinelActive = connectivitySentinelStatus.active.value,
            whitelist = emptyList(),
            budget = BudgetUi(),
            privacyAccepted = false,
            appVersionLabel = appVersionLabel(),
            notificationListenerRestrictedHint =
                !health.notificationListenerGranted && installInfo.mayHaveRestrictedSettings,
        )
    }

    /** «Argus vX · F-Droid» se l'installer è un client F-Droid noto, altrimenti «Argus vX · APK». */
    private fun appVersionLabel(): String =
        "Argus v${installInfo.versionName ?: "?"} · ${installInfo.sourceLabel}"

    private fun latencyLabel(millis: Long): String = when {
        millis < 1_000 -> "$millis ms"
        else -> String.format(
            if (language == RenderLanguage.IT) Locale.ITALIAN else Locale.ENGLISH,
            "%.1f s",
            millis / 1_000.0,
        )
    }
}
