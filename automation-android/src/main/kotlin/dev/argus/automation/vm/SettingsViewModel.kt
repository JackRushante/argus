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
import dev.argus.automation.budget.BudgetLimits
import dev.argus.automation.budget.BudgetSettings
import dev.argus.automation.budget.BudgetSettingsStore
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
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
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuPermissionResult
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.BudgetUi
import dev.argus.ui.model.ContactRow
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.ProviderUsageUi
import dev.argus.ui.model.SettingsState
import dev.argus.ui.model.TransportUi
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
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
    private val usage: UsageDao,
    private val budgetSettings: BudgetSettingsStore,
    private val language: RenderLanguage = RenderLanguage.system(),
) : ViewModel() {
    private val refreshSignal = MutableStateFlow(0L)
    private val budgetUsage = MutableStateFlow(BudgetUsageSnapshot())
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
        budgetUsage,
        budgetSettings.observe(),
    ) { values: Array<Any?> ->
        val settingsSources = values[0] as SettingsSources
        val bridge = values[1] as BridgeUiHealth
        val hasToken = values[2] as Boolean
        // values[3] = refreshSignal, usato solo come trigger di ricarica.
        val sentinelActive = values[4] as Boolean
        val usageSnapshot = values[5] as BudgetUsageSnapshot
        val budgetConfig = values[6] as BudgetSettings
        val health = readAndroidUiHealth(context)
        SettingsState(
            transport = transportUi(bridge, hasToken),
            providerChoices = providerChoices(),
            shizuku = settingsSources.shizukuStatus.toUiStatus(
                degradedAfterReboot = settingsSources.shizukuNeeded &&
                    settingsSources.shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ),
            batteryExempt = health.batteryExempt,
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

    fun saveProviderConfig(wireName: String, baseUrl: String?, model: String?, apiKey: String?) {
        val id = ProviderId.fromWireName(wireName) ?: run {
            mutableMessages.tryEmit(language.pick("Unknown provider.", "Provider sconosciuto."))
            return
        }
        viewModelScope.launch {
            try {
                if (!configuration.saveProviderConfig(id, baseUrl = baseUrl, model = model, apiKey = apiKey)) {
                    message(language.pick("Invalid configuration.", "Configurazione non valida."))
                    return@launch
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
            val text = when (shizuku.requestPermission(rationaleShown = shizukuRationaleShown)) {
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
                        "Tap Fix again to confirm the Shizuku request.",
                        "Premi di nuovo Correggi per confermare la richiesta Shizuku.",
                    )
                }
                ShizukuPermissionResult.UNAVAILABLE -> language.pick(
                    "Shizuku is unavailable.",
                    "Shizuku non è disponibile.",
                )
            }
            message(text)
            refresh()
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

    fun onBudgetChange(maxPerHour: Int) =
        updateGlobalLimits { it.copy(maxCallsPerHour = maxPerHour.takeIf { v -> v > 0 }) }

    fun onBudgetDayChange(maxPerDay: Int) =
        updateGlobalLimits { it.copy(maxCallsPerDay = maxPerDay.takeIf { v -> v > 0 }) }

    fun onBudgetMonthlyCostChange(maxCostMonthMicros: Long) =
        updateGlobalLimits { it.copy(maxCostPerMonthMicros = maxCostMonthMicros.takeIf { v -> v > 0 }) }

    fun onBudgetMonthlyTokensChange(maxTokensPerMonth: Long) =
        updateGlobalLimits { it.copy(maxTokensPerMonth = maxTokensPerMonth.takeIf { v -> v > 0 }) }

    private fun updateGlobalLimits(mutate: (BudgetLimits) -> BudgetLimits) {
        viewModelScope.launch {
            try {
                val current = budgetSettings.observe().value.global
                if (budgetSettings.setGlobalLimits(mutate(current))) {
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
                val suffix = result.kind.name.lowercase(Locale.ROOT)
                message(
                    if (savedMessage) {
                        language.pick(
                            "Configuration saved; $label is unreachable ($suffix).",
                            "Configurazione salvata; $label non raggiungibile ($suffix).",
                        )
                    } else {
                        language.pick(
                            "$label is unreachable ($suffix).",
                            "$label non raggiungibile ($suffix).",
                        )
                    },
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
            budget = BudgetUi(),
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
        else -> String.format(
            if (language == RenderLanguage.IT) Locale.ITALIAN else Locale.ENGLISH,
            "%.1f s",
            millis / 1_000.0,
        )
    }
}
