package dev.argus.automation.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AppPreferences
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.brain.AgentTransport
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.brain.TransportFactory
import dev.argus.brain.TransportHealth
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuGateway
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.ShizukuRequirement
import dev.argus.ui.model.StepKind
import dev.argus.ui.model.StepStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S10 — Onboarding wizard multi-provider. Verifica che [OnboardingViewModel] esponga i provider dal
 * catalog, che selezione/salvataggio config avanzino il wizard e persistano su [ProviderConfigStore],
 * che lo step BRAIN_CONFIG resti obbligatorio e che la chiave API non compaia MAI nello stato UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingViewModelTest {
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
    fun `lo stato espone i provider dal catalog con hermes selezionato di default`() =
        runTest(dispatcher) {
            val store = OnbFakeProviderStore().apply { seed(ProviderId.HERMES, baseUrl = "https://hermes.example") }
            val vm = onboardingViewModel(store)
            observe(vm)
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals("hermes", state.providerChoices.single { it.selected }.id)
            assertEquals(6, state.providerChoices.size)
            assertTrue(state.transport is TransportUi.CliBridge, "atteso CliBridge, era ${state.transport}")
            assertEquals("Scegli il cervello", state.steps[1].title)
        }

    @Test
    fun `saveBridge marca il passo cervello fatto e avanza`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore().apply {
            seed(ProviderId.HERMES, baseUrl = "https://hermes.old", key = "bearer-existing-0123456789")
        }
        val vm = onboardingViewModel(store, index = 1)
        observe(vm)
        advanceUntilIdle()

        vm.saveBridge("https://hermes.example", "bearer-0123456789abcdef")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(StepStatus.DONE, state.steps.first { it.kind == StepKind.BRAIN_CONFIG }.status)
        assertEquals(2, state.currentIndex)
    }

    @Test
    fun `saveProviderConfig su provider selezionato marca il passo fatto e avanza`() =
        runTest(dispatcher) {
            val store = OnbFakeProviderStore()
            val vm = onboardingViewModel(store, index = 1)
            observe(vm)
            advanceUntilIdle()

            vm.selectProvider("openai")
            advanceUntilIdle()
            vm.saveProviderConfig("openai", baseUrl = null, model = "gpt-5-mini", apiKey = "sk-test-1234567890ab")
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(StepStatus.DONE, state.steps.first { it.kind == StepKind.BRAIN_CONFIG }.status)
            assertEquals(2, state.currentIndex)
            assertTrue(state.canFinish, "con privacy accettata e chiave configurata canFinish deve essere true")
        }

    @Test
    fun `selezionare un provider senza chiave non marca il passo fatto`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore()
        val vm = onboardingViewModel(store, index = 1)
        observe(vm)
        advanceUntilIdle()

        vm.selectProvider("anthropic")
        advanceUntilIdle()

        val state = vm.state.value
        assertNotEquals(StepStatus.DONE, state.steps.first { it.kind == StepKind.BRAIN_CONFIG }.status)
        assertFalse(state.canFinish)
        val transport = state.transport
        assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
        assertEquals(AuthState.NOT_CONFIGURED, (transport as TransportUi.DirectProvider).authState)
    }

    @Test
    fun `selectProvider cambia il ramo transport esposto allo schermo`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore()
        val vm = onboardingViewModel(store)
        observe(vm)
        advanceUntilIdle()

        vm.selectProvider("openrouter")
        advanceUntilIdle()

        val state = vm.state.value
        val transport = state.transport
        assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
        assertEquals("openrouter", (transport as TransportUi.DirectProvider).providerId)
        assertEquals("openrouter", state.providerChoices.single { it.selected }.id)
    }

    @Test
    fun `wire name sconosciuto non tocca selezione ne indice`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore()
        val vm = onboardingViewModel(store, index = 1)
        observe(vm)
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.selectProvider("skynet")
        advanceUntilIdle()

        assertEquals(ProviderId.HERMES, store.selectedProviderId())
        assertEquals(1, vm.state.value.currentIndex)
        assertTrue(events.any { it is OnboardingEvent.Message }, "atteso un messaggio per il provider sconosciuto")
    }

    @Test
    fun `english onboarding localizes generated steps and events`() = runTest(dispatcher) {
        val vm = onboardingViewModel(OnbFakeProviderStore(), language = RenderLanguage.EN)
        observe(vm)
        val events = collectEvents(vm)
        advanceUntilIdle()

        assertEquals("Privacy and consent", vm.state.value.steps.first().title)
        assertTrue(
            vm.state.value.steps.first().body.contains("configured AI service"),
        )
        assertEquals("Choose the brain", vm.state.value.steps[1].title)

        vm.selectProvider("skynet")
        advanceUntilIdle()
        assertEquals("Unknown provider.", (events.last() as OnboardingEvent.Message).text)
    }

    @Test
    fun `la chiave API non compare mai nello stato onboarding`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore()
        val vm = onboardingViewModel(store, index = 1)
        observe(vm)
        advanceUntilIdle()

        vm.selectProvider("openai")
        advanceUntilIdle()
        vm.saveProviderConfig("openai", baseUrl = null, model = "gpt-5-mini", apiKey = "sk-test-1234567890ab")
        advanceUntilIdle()

        assertFalse(vm.state.value.toString().contains("sk-test-1234567890ab"))
    }

    @Test
    fun `onSkip continua a rifiutare il salto di BRAIN_CONFIG`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore()
        val vm = onboardingViewModel(store, index = 1)
        observe(vm)
        advanceUntilIdle()

        vm.onSkip(StepKind.BRAIN_CONFIG)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.currentIndex)
        assertNotEquals(
            StepStatus.SKIPPED,
            vm.state.value.steps.first { it.kind == StepKind.BRAIN_CONFIG }.status,
        )
    }

    @Test
    fun `lo stato espone la lista onesta di cosa richiede Shizuku`() = runTest(dispatcher) {
        val store = OnbFakeProviderStore()
        val vm = onboardingViewModel(store, index = 2)
        observe(vm)
        advanceUntilIdle()

        val caps = vm.state.value.shizukuCapabilities
        assertTrue(caps.isNotEmpty(), "la lista Shizuku non deve essere vuota")

        fun requirementOf(id: String): ShizukuRequirement? =
            caps.firstOrNull { id in it.actionTypeIds }?.requirement

        assertEquals(ShizukuRequirement.REQUIRED, requirementOf(ActionTypeIds.SET_WIFI))
        assertEquals(ShizukuRequirement.NOT_REQUIRED, requirementOf(ActionTypeIds.SET_VOLUME))
        assertEquals(ShizukuRequirement.RECOMMENDED, requirementOf(ActionTypeIds.SET_ALARM))
        // Tutte e tre le categorie presenti: l'onboarding dice cosa NON puoi fare, cosa degrada, cosa resta.
        assertEquals(ShizukuRequirement.entries.toSet(), caps.map { it.requirement }.toSet())
    }

    // ------------------------------------------------------------------ helper

    private fun TestScope.observe(vm: OnboardingViewModel) {
        backgroundScope.launch { vm.state.collect {} }
    }

    private fun TestScope.collectEvents(vm: OnboardingViewModel): List<OnboardingEvent> {
        val out = mutableListOf<OnboardingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { out += it } }
        return out
    }

    private fun onboardingViewModel(
        store: OnbFakeProviderStore,
        index: Int = 0,
        language: RenderLanguage = RenderLanguage.IT,
    ): OnboardingViewModel {
        val brain = ConfiguredBridgeBrain(store, privacyAccepted = { true }, factory = OnbFakeTransportFactory())
        val handle = SavedStateHandle(mapOf("currentIndex" to index))
        return OnboardingViewModel(
            savedStateHandle = handle,
            context = context,
            preferences = OnbFakePreferences(privacyAccepted = true),
            configuration = store,
            brain = brain,
            shizuku = ShizukuGateway(context),
            automations = OnbFakeAutomationStore(),
            drafts = OnbFakeDraftRepository(),
            language = language,
        )
    }
}

// ----------------------------------------------------------------------- fakes

private class OnbFakeProviderStore(
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

private class OnbFakeTransportFactory : TransportFactory {
    override fun create(config: ProviderConfig): AgentTransport = OnbFakeAgentTransport(config.providerId)
}

private class OnbFakeAgentTransport(
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

private class OnbFakePreferences(privacyAccepted: Boolean) : AppPreferencesStore {
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

private class OnbFakeAutomationStore : AutomationStore {
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

private class OnbFakeDraftRepository : DraftRepository {
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
