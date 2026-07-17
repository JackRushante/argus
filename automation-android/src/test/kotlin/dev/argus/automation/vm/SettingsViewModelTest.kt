package dev.argus.automation.vm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AppPreferences
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.PrivacyRevocationCoordinator
import dev.argus.automation.budget.BudgetLimits
import dev.argus.automation.budget.BudgetSettings
import dev.argus.automation.budget.BudgetSettingsStore
import dev.argus.automation.connectivity.ConnectivitySentinelStatus
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.brain.AgentTransport
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.brain.TransportFactory
import dev.argus.brain.TransportHealth
import dev.argus.data.DeferredReplyStore
import dev.argus.data.UsageWindows
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.TransportUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S9 — Settings multi-provider. Verifica che [SettingsViewModel] emetta il ramo transport corretto
 * per il provider selezionato, che la selezione/salvataggio persistano su [ProviderConfigStore] e,
 * soprattutto, che la chiave API non compaia MAI nello stato UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `hermes selezionato di default emette CliBridge con l'url dello store`() = runTest(dispatcher) {
        val store = FakeProviderStore().apply {
            seed(ProviderId.HERMES, baseUrl = "https://hermes.example")
        }
        val vm = settingsViewModel(store)
        observe(vm)
        advanceUntilIdle()

        val transport = vm.state.value.transport
        assertTrue(transport is TransportUi.CliBridge, "atteso CliBridge, era $transport")
        assertEquals("https://hermes.example", (transport as TransportUi.CliBridge).url)

        val choices = vm.state.value.providerChoices
        assertEquals(6, choices.size)
        assertEquals(setOf("hermes"), choices.filter { it.selected }.map { it.id }.toSet())
    }

    @Test
    fun `provider diretto selezionato emette DirectProvider con label e modelli dal catalog`() =
        runTest(dispatcher) {
            val store = FakeProviderStore().apply {
                seed(ProviderId.OPENAI, key = "sk-existing-1234567890")
                select(ProviderId.OPENAI)
            }
            val vm = settingsViewModel(store)
            observe(vm)
            vm.refresh()
            advanceUntilIdle()

            val transport = vm.state.value.transport
            assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
            transport as TransportUi.DirectProvider
            assertEquals("openai", transport.providerId)
            assertEquals("OpenAI", transport.providerLabel)
            assertEquals(AuthState.OK, transport.authState)
            assertFalse(transport.baseUrlEditable)
            assertEquals(listOf("gpt-5.5", "gpt-5-mini"), transport.defaultModels)
        }

    @Test
    fun `selectProvider persiste sullo store e cambia ramo transport`() = runTest(dispatcher) {
        val store = FakeProviderStore()
        val vm = settingsViewModel(store)
        observe(vm)
        advanceUntilIdle()

        vm.selectProvider("anthropic")
        advanceUntilIdle()

        assertEquals(ProviderId.ANTHROPIC, store.selectedProviderId())
        val transport = vm.state.value.transport
        assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
        transport as TransportUi.DirectProvider
        assertEquals("anthropic", transport.providerId)
        assertNull(transport.reachable)
    }

    @Test
    fun `selectProvider con wire name sconosciuto non tocca lo store ed emette messaggio`() =
        runTest(dispatcher) {
            val store = FakeProviderStore()
            val vm = settingsViewModel(store)
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()

            vm.selectProvider("gpt9000")
            advanceUntilIdle()

            assertEquals(ProviderId.HERMES, store.selectedProviderId())
            assertTrue(messages.isNotEmpty(), "atteso un messaggio d'errore per il provider sconosciuto")
        }

    @Test
    fun `saveProviderConfig persiste baseUrl modello e chiave su ProviderConfigStore`() =
        runTest(dispatcher) {
            val store = FakeProviderStore()
            val vm = settingsViewModel(store)
            observe(vm)
            advanceUntilIdle()

            vm.saveProviderConfig("openai", baseUrl = null, model = "gpt-5-mini", apiKey = "sk-test-1234567890ab")
            advanceUntilIdle()

            val call = store.saveCalls.last { it.id == ProviderId.OPENAI }
            assertEquals("gpt-5-mini", call.model)
            assertEquals("sk-test-1234567890ab", call.apiKey)
            assertTrue(store.hasApiKeyBlocking(ProviderId.OPENAI))
        }

    @Test
    fun `saveProviderConfig con apiKey null conserva la chiave esistente`() = runTest(dispatcher) {
        val store = FakeProviderStore().apply {
            seed(ProviderId.OPENAI, key = "sk-existing-1234567890")
        }
        val vm = settingsViewModel(store)
        observe(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openai", baseUrl = null, model = "gpt-5-mini", apiKey = null)
        advanceUntilIdle()

        assertTrue(store.hasApiKeyBlocking(ProviderId.OPENAI))
        val call = store.saveCalls.last { it.id == ProviderId.OPENAI }
        assertNull(call.apiKey, "una save con apiKey null non deve inviare una scrittura di chiave")
    }

    @Test
    fun `la chiave API non compare mai nello stato UI`() = runTest(dispatcher) {
        val store = FakeProviderStore()
        val vm = settingsViewModel(store)
        observe(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openai", baseUrl = null, model = "gpt-5-mini", apiKey = "sk-test-1234567890ab")
        advanceUntilIdle()

        assertFalse(vm.state.value.toString().contains("sk-test-1234567890ab"))
    }

    @Test
    fun `saveBridge continua a salvare hermes e a sondare la salute`() = runTest(dispatcher) {
        val store = FakeProviderStore().apply {
            seed(ProviderId.HERMES, baseUrl = "https://hermes.old", key = "bearer-existing-0123456789")
        }
        val vm = settingsViewModel(store)
        observe(vm)
        advanceUntilIdle()

        vm.saveBridge("https://hermes.example", "bearer-0123456789abcdef")
        advanceUntilIdle()

        assertEquals("https://hermes.example", store.providerConfig(ProviderId.HERMES).baseUrl)
        assertTrue(vm.tokenConfigured.value)
        val transport = vm.state.value.transport
        assertTrue(transport is TransportUi.CliBridge, "atteso CliBridge, era $transport")
        assertEquals(true, (transport as TransportUi.CliBridge).reachable)
    }

    @Test
    fun `custom openai compat espone baseUrlEditable`() = runTest(dispatcher) {
        val store = FakeProviderStore().apply {
            seed(ProviderId.CUSTOM_OPENAI_COMPAT, baseUrl = "https://ollama.local/v1", key = "k-1234567890")
            select(ProviderId.CUSTOM_OPENAI_COMPAT)
        }
        val vm = settingsViewModel(store)
        observe(vm)
        vm.refresh()
        advanceUntilIdle()

        val transport = vm.state.value.transport
        assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
        assertTrue((transport as TransportUi.DirectProvider).baseUrlEditable)
    }

    // ------------------------------------------------------------------ helper

    private fun TestScope.observe(vm: SettingsViewModel) {
        backgroundScope.launch { vm.state.collect {} }
    }

    private fun TestScope.collectMessages(vm: SettingsViewModel): List<String> {
        val out = mutableListOf<String>()
        // Unconfined: la sottoscrizione al SharedFlow parte subito, prima di ogni tryEmit sotto test.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { out += it } }
        return out
    }

    private fun settingsViewModel(
        store: FakeProviderStore,
        usage: UsageDao = FakeUsageDao(),
        budgetSettings: BudgetSettingsStore = FakeBudgetSettingsStore(),
    ): SettingsViewModel {
        val brain = ConfiguredBridgeBrain(store, privacyAccepted = { true }, factory = FakeTransportFactory())
        val preferences = FakePreferences(privacyAccepted = true)
        val observed = FakeObservedConversationStore()
        val privacy = PrivacyRevocationCoordinator(
            preferences,
            ActiveNotificationReplyRegistry(),
            observed,
            FakeDeferredReplyStore(),
        )
        return SettingsViewModel(
            context = context,
            configuration = store,
            brain = brain,
            whitelist = FakeWhitelistStore(),
            preferences = preferences,
            privacyRevocation = privacy,
            observedConversations = observed,
            automations = FakeAutomationStore(),
            drafts = FakeDraftRepository(),
            shizuku = ShizukuGateway(context),
            connectivitySentinelStatus = ConnectivitySentinelStatus(),
            usage = usage,
            budgetSettings = budgetSettings,
        )
    }

    // ------------------------------------------------------------------ budget (S14)

    @Test
    fun `budget emette aggregati tipizzati escludendo i blocked`() = runTest(dispatcher) {
        val usage = FakeUsageDao(
            all = listOf(
                aggregate("openai", calls = 5, blocked = 2, cost = 1_870_000),
                aggregate("hermes", calls = 3, blocked = 0, cost = null),
            ),
        )
        val vm = settingsViewModel(FakeProviderStore(), usage = usage)
        observe(vm)
        vm.refresh()
        advanceUntilIdle()

        val budget = vm.state.value.budget
        assertEquals(6L, budget.usedHour)
        val labels = budget.perProvider.associate { it.providerId to it.providerLabel }
        assertEquals("OpenAI", labels["openai"])
        assertEquals("Hermes (self-hosted)", labels["hermes"])
        val hermes = budget.perProvider.first { it.providerId == "hermes" }
        assertNull(hermes.costMonthMicros)
    }

    @Test
    fun `budget espone i limiti dallo store`() = runTest(dispatcher) {
        val settings = FakeBudgetSettingsStore(
            BudgetSettings(
                global = BudgetLimits(maxCallsPerHour = 20, maxCallsPerDay = null, maxCostPerMonthMicros = 5_000_000),
            ),
        )
        val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
        observe(vm)
        vm.refresh()
        advanceUntilIdle()

        val budget = vm.state.value.budget
        assertEquals(20, budget.limitHour)
        assertNull(budget.limitDay)
        assertEquals(5_000_000L, budget.costLimitMicros)
    }

    @Test
    fun `soft warning attivo oltre la soglia`() = runTest(dispatcher) {
        val over = settingsViewModel(
            FakeProviderStore(),
            usage = FakeUsageDao(all = listOf(aggregate("openai", calls = 17, blocked = 0, cost = null))),
            budgetSettings = FakeBudgetSettingsStore(
                BudgetSettings(global = BudgetLimits(maxCallsPerHour = 20), softThresholdPct = 80),
            ),
        )
        observe(over)
        over.refresh()
        advanceUntilIdle()
        assertTrue(over.state.value.budget.softWarningActive)

        val under = settingsViewModel(
            FakeProviderStore(),
            usage = FakeUsageDao(all = listOf(aggregate("openai", calls = 10, blocked = 0, cost = null))),
            budgetSettings = FakeBudgetSettingsStore(
                BudgetSettings(global = BudgetLimits(maxCallsPerHour = 20), softThresholdPct = 80),
            ),
        )
        observe(under)
        under.refresh()
        advanceUntilIdle()
        assertFalse(under.state.value.budget.softWarningActive)
    }

    @Test
    fun `onBudgetChange persiste il limite orario globale`() = runTest(dispatcher) {
        val settings = FakeBudgetSettingsStore()
        val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
        observe(vm)
        advanceUntilIdle()

        vm.onBudgetChange(25)
        advanceUntilIdle()
        assertEquals(25, settings.observe().value.global.maxCallsPerHour)

        vm.onBudgetChange(0)
        advanceUntilIdle()
        assertNull(settings.observe().value.global.maxCallsPerHour)
    }

    @Test
    fun `onBudgetMonthlyCostChange persiste il tetto mensile`() = runTest(dispatcher) {
        val settings = FakeBudgetSettingsStore()
        val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
        observe(vm)
        advanceUntilIdle()

        vm.onBudgetMonthlyCostChange(5_000_000)
        advanceUntilIdle()
        assertEquals(5_000_000L, settings.observe().value.global.maxCostPerMonthMicros)

        vm.onBudgetMonthlyCostChange(0)
        advanceUntilIdle()
        assertNull(settings.observe().value.global.maxCostPerMonthMicros)
    }

    @Test
    fun `onBudgetMonthlyTokensChange persiste il tetto token mensile`() = runTest(dispatcher) {
        val settings = FakeBudgetSettingsStore()
        val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
        observe(vm)
        advanceUntilIdle()

        vm.onBudgetMonthlyTokensChange(1_000_000)
        advanceUntilIdle()
        assertEquals(1_000_000L, settings.observe().value.global.maxTokensPerMonth)

        vm.onBudgetMonthlyTokensChange(0)
        advanceUntilIdle()
        assertNull(settings.observe().value.global.maxTokensPerMonth)
    }

    @Test
    fun `budget espone token per provider e nessun dollaro per i token-only`() = runTest(dispatcher) {
        val usage = FakeUsageDao(
            all = listOf(
                aggregate("hermes", calls = 3, blocked = 0, cost = null, tokensIn = 150, tokensOut = 15),
                aggregate("openai", calls = 5, blocked = 0, cost = 1_870_000, tokensIn = 900, tokensOut = 90),
            ),
        )
        val vm = settingsViewModel(FakeProviderStore(), usage = usage)
        observe(vm)
        vm.refresh()
        advanceUntilIdle()

        val budget = vm.state.value.budget
        val hermes = budget.perProvider.first { it.providerId == "hermes" }
        assertFalse(hermes.costTracked)
        assertNull(hermes.costMonthMicros)
        assertEquals(150L, hermes.tokensInMonth)
        assertEquals(15L, hermes.tokensOutMonth)

        val openai = budget.perProvider.first { it.providerId == "openai" }
        assertTrue(openai.costTracked)
        assertEquals(1_870_000L, openai.costMonthMicros)

        // Il totale token mese (token-only) esclude i 990 token di openai.
        assertEquals(150L, budget.tokensInMonth)
        assertEquals(15L, budget.tokensOutMonth)
    }
}

private fun aggregate(
    providerId: String,
    calls: Long,
    blocked: Long,
    cost: Long?,
    tokensIn: Long? = null,
    tokensOut: Long? = null,
): ProviderUsageAggregate =
    ProviderUsageAggregate(
        providerId = providerId,
        calls = calls,
        okCalls = (calls - blocked).coerceAtLeast(0),
        errorCalls = 0,
        blockedCalls = blocked,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costMicros = cost,
    )

/**
 * Ritorna aggregati canned instradati per finestra: ricostruisce `now = end - 1` e confronta lo start
 * con i confini calcolati da [UsageWindows], così ogni finestra riceve la sua lista (default: la stessa).
 */
private class FakeUsageDao(
    private val all: List<ProviderUsageAggregate> = emptyList(),
    private val hour: List<ProviderUsageAggregate> = all,
    private val day: List<ProviderUsageAggregate> = all,
    private val month: List<ProviderUsageAggregate> = all,
) : UsageDao {
    override suspend fun insert(event: dev.argus.data.entities.UsageEventEntity): Long = 1L

    override suspend fun aggregateBetween(
        startMillis: Long,
        endMillisExclusive: Long,
    ): List<ProviderUsageAggregate> {
        val now = endMillisExclusive - 1
        val zone = ZoneId.systemDefault()
        return when (startMillis) {
            UsageWindows.lastHour(now).startMillis -> hour
            UsageWindows.currentDay(now, zone).startMillis -> day
            UsageWindows.currentMonth(now, zone).startMillis -> month
            else -> emptyList()
        }
    }

    override suspend fun tokensBetween(
        startMillis: Long,
        endMillisExclusive: Long,
    ): List<ProviderTokensAggregate> =
        aggregateBetween(startMillis, endMillisExclusive)
            .map { ProviderTokensAggregate(it.providerId, it.tokensIn, it.tokensOut) }

    override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
}

private class FakeBudgetSettingsStore(
    initial: BudgetSettings = BudgetSettings(),
) : BudgetSettingsStore {
    private val flow = MutableStateFlow(initial)
    override fun observe(): StateFlow<BudgetSettings> = flow.asStateFlow()

    override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean {
        if (negative(limits)) return false
        flow.value = flow.value.copy(global = normalize(limits))
        return true
    }

    override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean {
        if (negative(limits)) return false
        flow.value = flow.value.copy(
            perProvider = flow.value.perProvider + (id.wireName to normalize(limits)),
        )
        return true
    }

    override suspend fun setSoftThresholdPct(pct: Int): Boolean {
        if (pct !in 1..100) return false
        flow.value = flow.value.copy(softThresholdPct = pct)
        return true
    }

    private fun negative(l: BudgetLimits): Boolean =
        (l.maxCallsPerHour ?: 0) < 0 || (l.maxCallsPerDay ?: 0) < 0 ||
            (l.maxCostPerMonthMicros ?: 0L) < 0L || (l.maxTokensPerMonth ?: 0L) < 0L

    private fun normalize(l: BudgetLimits): BudgetLimits = BudgetLimits(
        maxCallsPerHour = l.maxCallsPerHour?.takeIf { it > 0 },
        maxCallsPerDay = l.maxCallsPerDay?.takeIf { it > 0 },
        maxCostPerMonthMicros = l.maxCostPerMonthMicros?.takeIf { it > 0L },
        maxTokensPerMonth = l.maxTokensPerMonth?.takeIf { it > 0L },
    )
}

// ----------------------------------------------------------------------- fakes

private class FakeProviderStore(
    initialSelected: ProviderId = ProviderId.HERMES,
) : ProviderConfigStore {
    class Slot(var baseUrl: String? = null, var model: String? = null, var key: String? = null)
    data class SaveCall(val id: ProviderId, val baseUrl: String?, val model: String?, val apiKey: String?)

    private var selected = initialSelected
    private val slots = mutableMapOf<ProviderId, Slot>()
    val saveCalls = mutableListOf<SaveCall>()

    private fun slot(id: ProviderId) = slots.getOrPut(id) { Slot() }

    fun seed(id: ProviderId, baseUrl: String? = null, model: String? = null, key: String? = null) {
        val s = slot(id)
        baseUrl?.let { s.baseUrl = it }
        model?.let { s.model = it }
        key?.let { s.key = it }
    }

    fun select(id: ProviderId) {
        selected = id
    }

    fun hasApiKeyBlocking(id: ProviderId): Boolean = slots[id]?.key != null

    override fun selectedProviderId(): ProviderId = selected

    override suspend fun selectProvider(id: ProviderId): Boolean {
        selected = id
        return true
    }

    override fun providerConfig(id: ProviderId): ProviderConfig {
        val s = slots[id]
        val baseUrl = s?.baseUrl ?: ProviderCatalog.spec(id).defaultBaseUrl ?: ""
        return ProviderConfig(providerId = id, baseUrl = baseUrl, model = s?.model)
    }

    override suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
    ): Boolean {
        saveCalls += SaveCall(id, baseUrl, model, apiKey)
        val s = slot(id)
        baseUrl?.let { s.baseUrl = it }
        model?.let { s.model = it }
        apiKey?.let { s.key = it }
        return true
    }

    override suspend fun apiKey(id: ProviderId): String? = slots[id]?.key
    override suspend fun hasApiKey(id: ProviderId): Boolean = slots[id]?.key != null
    override suspend fun clearApiKey(id: ProviderId): Boolean {
        slots[id]?.key = null
        return true
    }
}

private class FakeTransportFactory : TransportFactory {
    override fun create(config: ProviderConfig): AgentTransport = FakeAgentTransport(config.providerId)
}

private class FakeAgentTransport(
    override val providerId: ProviderId,
) : AgentTransport {
    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult = CompileResult(reply = "ok", draft = null, metaError = null)

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = ActResult(text = "ok", metaError = null)

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        ActResult(text = "ok", metaError = null)

    override suspend fun health(): TransportHealth = object : TransportHealth {
        override val model: String = "fake-model"
    }
}

private class FakePreferences(privacyAccepted: Boolean) : AppPreferencesStore {
    private val state = MutableStateFlow(
        AppPreferences(privacyAccepted = privacyAccepted, onboardingCompleted = false),
    )

    override fun observe() = state.asStateFlow()
    override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean {
        state.value = state.value.copy(privacyAccepted = accepted)
        return true
    }

    override suspend fun setOnboardingCompleted(completed: Boolean): Boolean {
        state.value = state.value.copy(onboardingCompleted = completed)
        return true
    }
}

private class FakeWhitelistStore : ContactWhitelistStore {
    private val state = MutableStateFlow<List<WhitelistedContact>>(emptyList())
    override suspend fun all(): List<WhitelistedContact> = state.value
    override fun observeAll(): Flow<List<WhitelistedContact>> = state.asStateFlow()
    override suspend fun upsert(contact: WhitelistedContact) {
        state.value = state.value.filterNot { it.id == contact.id } + contact
    }

    override suspend fun remove(conversationId: String) {
        state.value = state.value.filterNot { it.id == conversationId }
    }
}

private class FakeObservedConversationStore : ObservedConversationStore {
    private val state = MutableStateFlow<List<ObservedConversation>>(emptyList())
    override suspend fun recent(limit: Int): List<ObservedConversation> = state.value
    override fun observeRecent(limit: Int): Flow<List<ObservedConversation>> = state.asStateFlow()
    override suspend fun record(conversation: ObservedConversation) = Unit
    override suspend fun clear() {
        state.value = emptyList()
    }
}

private class FakeAutomationStore : AutomationStore {
    private val state = MutableStateFlow<List<Automation>>(emptyList())
    override suspend fun get(id: AutomationId): Automation? = null
    override suspend fun all(): List<Automation> = state.value
    override fun observeAll(): Flow<List<Automation>> = state.asStateFlow()
    override suspend fun armed(): List<Automation> = emptyList()
    override suspend fun delete(id: AutomationId) = Unit
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ) = false

    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible

    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}

private class FakeDraftRepository : DraftRepository {
    private val state = MutableStateFlow<List<PendingDraft>>(emptyList())
    override suspend fun create(newDraft: NewDraft): DraftWriteResult = error("non usato")
    override suspend fun revise(
        id: DraftId,
        expectedRevision: Long,
        draft: AutomationDraft,
        priority: Int,
        atMillis: Long,
    ): DraftWriteResult = error("non usato")

    override suspend fun get(id: DraftId): PendingDraft? = null
    override fun observeAll(): Flow<List<PendingDraft>> = state.asStateFlow()
    override suspend fun delete(id: DraftId, expectedRevision: Long): DraftDeleteResult =
        error("non usato")

    override suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult = error("non usato")
}

private class FakeDeferredReplyStore : DeferredReplyStore {
    override suspend fun save(entity: DeferredReplyEntity): Boolean = true
    override suspend fun firstActionable(executionId: String, nowMillis: Long): DeferredReplyEntity? =
        null

    override suspend fun markConsumed(executionId: String, actionIndex: Int, atMillis: Long): Boolean =
        false

    override suspend fun purgeExpired(nowMillis: Long): Int = 0
    override suspend fun clear(): Int = 0
}
