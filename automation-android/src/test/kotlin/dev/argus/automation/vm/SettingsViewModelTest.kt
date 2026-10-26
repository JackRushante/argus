package dev.argus.automation.vm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.AppInstallInfo
import dev.argus.automation.AppPreferences
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.PrivacyRevocationCoordinator
import dev.argus.automation.RenderLanguageSource
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
import dev.argus.brain.ReasoningEffort
import dev.argus.brain.TransportErrorKind
import dev.argus.brain.TransportException
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
import dev.argus.shizuku.ShizukuAccess
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuIdentity
import dev.argus.shizuku.ShizukuIdentityProbe
import dev.argus.shizuku.ShizukuPermissionOutcome
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.delay
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
    fun `english settings emits english runtime feedback`() = runTest(dispatcher) {
        val vm = settingsViewModel(FakeProviderStore(), language = RenderLanguage.EN)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.selectProvider("gpt9000")
        advanceUntilIdle()

        assertEquals("Unknown provider.", messages.last())
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
    fun `custom locale non richiede chiave ed espone reasoning configurabile`() = runTest(dispatcher) {
        val store = FakeProviderStore().apply {
            seed(ProviderId.CUSTOM_OPENAI_COMPAT, baseUrl = "http://127.0.0.1:8080/v1")
            select(ProviderId.CUSTOM_OPENAI_COMPAT)
        }
        val vm = settingsViewModel(store)
        observe(vm)
        vm.refresh()
        advanceUntilIdle()

        val transport = vm.state.value.transport
        assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
        transport as TransportUi.DirectProvider
        assertTrue(transport.baseUrlEditable)
        assertFalse(transport.apiKeyRequired)
        assertEquals(AuthState.OK, transport.authState)
        assertTrue(transport.reasoningEffortEditable)

        vm.saveProviderConfig(
            "custom_openai_compat",
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "qwen-local",
            apiKey = null,
            reasoningEffort = "low",
        )
        advanceUntilIdle()

        assertEquals(ReasoningEffort.LOW, store.saveCalls.last().reasoningEffort)
    }


    // ------------------------------------------------------------------ 0.3.6 F: Shizuku nell'interfaccia

    @Test
    fun `an outdated Shizuku is shown as too old, not as stopped`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(ShizukuGatewayStatus.UNSUPPORTED)
        val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku)
        observe(vm)
        advanceUntilIdle()

        assertEquals(ShizukuStatus.UNSUPPORTED, vm.state.value.shizuku)
    }

    @Test
    fun `a permission request that times out says it is waiting, then the state follows the late answer`() =
        runTest(dispatcher) {
            val shizuku = FakeShizukuAccess(
                ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                outcome = ShizukuPermissionOutcome.AWAITING_USER,
            )
            val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku)
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()
            assertEquals(ShizukuStatus.RUNNING_NOT_AUTHORIZED, vm.state.value.shizuku)

            vm.requestShizukuPermission()
            advanceUntilIdle()

            assertEquals(listOf("In attesa della tua risposta in Shizuku"), messages)
            assertEquals(ShizukuStatus.RUNNING_NOT_AUTHORIZED, vm.state.value.shizuku)

            // L'utente risponde nella finestra di Shizuku dopo il timeout: nessun refresh() manuale.
            shizuku.setStatus(ShizukuGatewayStatus.AUTHORIZED)
            advanceUntilIdle()

            assertEquals(ShizukuStatus.AUTHORIZED, vm.state.value.shizuku)
        }

    @Test
    fun `the waiting message follows the language`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
            outcome = ShizukuPermissionOutcome.AWAITING_USER,
        )
        val vm = settingsViewModel(FakeProviderStore(), language = RenderLanguage.EN, shizuku = shizuku)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.requestShizukuPermission()
        advanceUntilIdle()

        assertEquals(listOf("Waiting for your answer in Shizuku"), messages)
    }

    @Test
    fun `the other permission outcomes keep their messages`() = runTest(dispatcher) {
        val expected = mapOf(
            ShizukuPermissionOutcome.GRANTED to "Autorizzazione Shizuku concessa.",
            ShizukuPermissionOutcome.DENIED to "Autorizzazione Shizuku negata.",
            ShizukuPermissionOutcome.RATIONALE_REQUIRED to
                "Premi di nuovo Correggi per confermare la richiesta Shizuku.",
            ShizukuPermissionOutcome.UNAVAILABLE to "Shizuku non è disponibile.",
        )
        expected.forEach { (outcome, text) ->
            val shizuku = FakeShizukuAccess(ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED, outcome = outcome)
            val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku)
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()

            vm.requestShizukuPermission()
            advanceUntilIdle()

            assertEquals(listOf(text), messages, outcome.name)
        }
    }

    @Test
    fun `after a rationale the next request confirms it`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
            outcome = ShizukuPermissionOutcome.RATIONALE_REQUIRED,
        )
        val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku)
        observe(vm)
        advanceUntilIdle()

        vm.requestShizukuPermission()
        advanceUntilIdle()
        vm.requestShizukuPermission()
        advanceUntilIdle()

        assertEquals(listOf(false, true), shizuku.rationaleRequests)
    }

    @Test
    fun `the state says whether Shizuku runs as root once the identity is known`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED)
        val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku)
        observe(vm)
        advanceUntilIdle()
        assertNull(vm.state.value.shizukuRunsAsRoot)

        shizuku.setIdentity(ShizukuIdentity(uid = 0))
        advanceUntilIdle()
        assertEquals(true, vm.state.value.shizukuRunsAsRoot)

        shizuku.setIdentity(ShizukuIdentity(uid = 2000))
        advanceUntilIdle()
        assertEquals(false, vm.state.value.shizukuRunsAsRoot)

        // Servizio scollegato: l'identità torna ignota, non resta l'ultima.
        shizuku.setIdentity(null)
        advanceUntilIdle()
        assertNull(vm.state.value.shizukuRunsAsRoot)
    }

    @Test
    fun `an authorized Shizuku with an unknown identity is probed exactly once`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED)
        val probe = FakeShizukuIdentityProbe {
            ShizukuIdentity(uid = 0).also { shizuku.setIdentity(it) }
        }
        val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku, identityProbe = probe)
        observe(vm)
        advanceUntilIdle()

        vm.onSystemVisible()
        advanceUntilIdle()

        assertEquals(1, probe.calls)
        assertEquals(true, vm.state.value.shizukuRunsAsRoot)

        // Identità ormai nota: tornare su Sistema non ripete la sonda.
        vm.onSystemVisible()
        advanceUntilIdle()
        assertEquals(1, probe.calls)
    }

    @Test
    fun `a probe still running is not started a second time`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED)
        val gate = CompletableDeferred<Unit>()
        val probe = FakeShizukuIdentityProbe {
            gate.await()
            null
        }
        val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku, identityProbe = probe)
        observe(vm)
        advanceUntilIdle()

        vm.onSystemVisible()
        advanceUntilIdle()
        vm.onSystemVisible()
        vm.onSystemVisible()
        advanceUntilIdle()
        assertEquals(1, probe.calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, probe.calls)
    }

    @Test
    fun `without authorization the identity is never probed`() = runTest(dispatcher) {
        listOf(
            ShizukuGatewayStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
            ShizukuGatewayStatus.UNSUPPORTED,
        ).forEach { status ->
            val probe = FakeShizukuIdentityProbe()
            val vm = settingsViewModel(
                FakeProviderStore(),
                shizuku = FakeShizukuAccess(status),
                identityProbe = probe,
            )
            observe(vm)
            advanceUntilIdle()

            vm.onSystemVisible()
            advanceUntilIdle()

            assertEquals(0, probe.calls, status.name)
            assertNull(vm.state.value.shizukuRunsAsRoot, status.name)
        }
    }

    @Test
    fun `a probe that fails leaves the identity unknown without crashing`() = runTest(dispatcher) {
        val probe = FakeShizukuIdentityProbe { throw IllegalStateException("remote") }
        val vm = settingsViewModel(
            FakeProviderStore(),
            shizuku = FakeShizukuAccess(ShizukuGatewayStatus.AUTHORIZED),
            identityProbe = probe,
        )
        observe(vm)
        advanceUntilIdle()

        vm.onSystemVisible()
        advanceUntilIdle()

        assertEquals(1, probe.calls)
        assertNull(vm.state.value.shizukuRunsAsRoot)
    }

    @Test
    fun `a granted request probes the identity right away`() = runTest(dispatcher) {
        val shizuku = FakeShizukuAccess(
            ShizukuGatewayStatus.AUTHORIZED,
            outcome = ShizukuPermissionOutcome.GRANTED,
        )
        val probe = FakeShizukuIdentityProbe()
        val vm = settingsViewModel(FakeProviderStore(), shizuku = shizuku, identityProbe = probe)
        observe(vm)
        advanceUntilIdle()

        vm.requestShizukuPermission()
        advanceUntilIdle()

        assertEquals(1, probe.calls)
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
        language: RenderLanguage = RenderLanguage.IT,
        transportFactory: TransportFactory = FakeTransportFactory(),
        installInfo: AppInstallInfo = AppInstallInfo(versionName = "0.3.6", installerPackage = null, sdkInt = 34),
        languageSource: RenderLanguageSource = RenderLanguageSource { language },
        shizuku: ShizukuAccess = ShizukuGateway(context),
        identityProbe: ShizukuIdentityProbe = FakeShizukuIdentityProbe(),
    ): SettingsViewModel {
        val brain = ConfiguredBridgeBrain(store, privacyAccepted = { true }, factory = transportFactory)
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
            shizuku = shizuku,
            shizukuIdentityProbe = identityProbe,
            connectivitySentinelStatus = ConnectivitySentinelStatus(),
            usage = usage,
            budgetSettings = budgetSettings,
            languageSource = languageSource,
            installInfo = installInfo,
        )
    }

    // ------------------------------------------------------------------ 0.3.6 voce 1: il test spiega la causa

    private data class FailureCase(
        val error: TransportException,
        val expectedIt: String,
        val expectedEn: String,
    )

    private fun failureCases(): List<FailureCase> = listOf(
        FailureCase(
            TransportException(TransportErrorKind.NETWORK, "chiamata provider fallita"),
            "OpenRouter non raggiungibile: errore di rete o DNS.",
            "OpenRouter is unreachable: network or DNS error.",
        ),
        FailureCase(
            TransportException(TransportErrorKind.TIMEOUT, "chiamata provider fallita"),
            "OpenRouter non ha risposto in tempo (timeout).",
            "OpenRouter did not answer in time (timeout).",
        ),
        FailureCase(
            TransportException(TransportErrorKind.AUTH, "Provider HTTP 401", statusCode = 401),
            "OpenRouter ha rifiutato la chiave API (HTTP 401): controllala.",
            "OpenRouter rejected the API key (HTTP 401): check it.",
        ),
        FailureCase(
            TransportException(TransportErrorKind.AUTH, "Provider HTTP 403", statusCode = 403),
            "OpenRouter ha rifiutato la chiave API (HTTP 403): controllala.",
            "OpenRouter rejected the API key (HTTP 403): check it.",
        ),
        FailureCase(
            TransportException(TransportErrorKind.BUDGET, "Provider HTTP 402", statusCode = 402),
            "OpenRouter segnala credito o budget esaurito (HTTP 402).",
            "OpenRouter reports no credit or budget left (HTTP 402).",
        ),
        FailureCase(
            TransportException(TransportErrorKind.HTTP, "Provider HTTP 404", statusCode = 404),
            "OpenRouter: endpoint non trovato (HTTP 404): controlla l'URL.",
            "OpenRouter: endpoint not found (HTTP 404): check the URL.",
        ),
        FailureCase(
            TransportException(TransportErrorKind.RATE_LIMIT, "Provider HTTP 429", statusCode = 429),
            "OpenRouter sta limitando le richieste (HTTP 429): riprova tra poco.",
            "OpenRouter is limiting requests (HTTP 429): try again shortly.",
        ),
        FailureCase(
            TransportException(
                TransportErrorKind.HTTP,
                "Provider HTTP 400",
                statusCode = 400,
                providerMessage = "No endpoints found matching your data policy",
            ),
            "OpenRouter ha rifiutato la richiesta (HTTP 400). Messaggio del provider: «No endpoints found matching your data policy»",
            "OpenRouter rejected the request (HTTP 400). Provider message: “No endpoints found matching your data policy”",
        ),
        FailureCase(
            TransportException(TransportErrorKind.HTTP, "Provider HTTP 503", statusCode = 503),
            "OpenRouter: servizio non disponibile (HTTP 503). Riprova più tardi.",
            "OpenRouter: service not available (HTTP 503). Try again later.",
        ),
    )

    private fun TestScope.connectionMessage(
        error: TransportException,
        language: RenderLanguage,
        provider: ProviderId = ProviderId.OPENROUTER,
        baseUrl: String? = null,
    ): String {
        val store = FakeProviderStore().apply {
            seed(provider, baseUrl = baseUrl, key = "sk-or-test-1234567890")
            select(provider)
        }
        val vm = settingsViewModel(
            store,
            language = language,
            transportFactory = TransportFactory { FailingHealthTransport(provider, error) },
        )
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()
        return messages.last()
    }

    @Test
    fun `il test di connessione spiega la causa in italiano`() = runTest(dispatcher) {
        failureCases().forEach { case ->
            assertEquals(case.expectedIt, connectionMessage(case.error, RenderLanguage.IT), case.error.toString())
        }
    }

    @Test
    fun `il test di connessione spiega la causa in inglese`() = runTest(dispatcher) {
        failureCases().forEach { case ->
            assertEquals(case.expectedEn, connectionMessage(case.error, RenderLanguage.EN), case.error.toString())
        }
    }

    @Test
    fun `un 404 su un endpoint custom che finisce con chat completions lo dice esplicitamente`() =
        runTest(dispatcher) {
            val error = TransportException(TransportErrorKind.HTTP, "Provider HTTP 404", statusCode = 404)
            listOf("http://192.168.1.20:8080/v1/chat/completions", "http://192.168.1.20:8080/v1/chat/completions/")
                .forEach { url ->
                    val italian = connectionMessage(error, RenderLanguage.IT, ProviderId.CUSTOM_OPENAI_COMPAT, url)
                    assertTrue("endpoint non trovato" in italian && "/chat/completions" in italian, italian)
                    assertTrue("Argus lo aggiunge da solo" in italian, italian)
                    val en = connectionMessage(error, RenderLanguage.EN, ProviderId.CUSTOM_OPENAI_COMPAT, url)
                    assertTrue("endpoint not found" in en && "/chat/completions" in en, en)
                    assertTrue("Argus adds it by itself" in en, en)
                }
            val plain = connectionMessage(
                error, RenderLanguage.IT, ProviderId.CUSTOM_OPENAI_COMPAT, "http://192.168.1.20:8080/v1",
            )
            assertFalse("/chat/completions" in plain, plain)
        }

    @Test
    fun `il messaggio del test di connessione non contiene la chiave anche se il provider la ripete`() =
        runTest(dispatcher) {
            val key = "sk-or-test-1234567890"
            // Il transport vero maschera la chiave prima di valorizzare providerMessage: qui il fake simula
            // un messaggio già ripulito e verifica che l'unico testo remoto mostrato sia quello.
            val error = TransportException(
                TransportErrorKind.HTTP,
                "Provider HTTP 400",
                statusCode = 400,
                providerMessage = "Invalid key ***",
            )
            val text = connectionMessage(error, RenderLanguage.IT)
            assertFalse(key in text, text)
            assertTrue("Invalid key ***" in text, text)
        }

    @Test
    fun `dopo il salvataggio un errore del test porta il prefisso configurazione salvata`() =
        runTest(dispatcher) {
            val store = FakeProviderStore().apply { select(ProviderId.OPENROUTER) }
            val vm = settingsViewModel(
                store,
                transportFactory = TransportFactory {
                    FailingHealthTransport(
                        ProviderId.OPENROUTER,
                        TransportException(TransportErrorKind.AUTH, "Provider HTTP 401", statusCode = 401),
                    )
                },
            )
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()

            vm.saveProviderConfig("openrouter", baseUrl = null, model = "deepseek/deepseek-v4.1-flash", apiKey = "sk-or-test-1234567890")
            advanceUntilIdle()

            assertEquals(
                "Configurazione salvata; OpenRouter ha rifiutato la chiave API (HTTP 401): controllala.",
                messages.last(),
            )
        }

    // ------------------------------------------------------------------ 0.3.6 voce 2: /chat/completions

    @Test
    fun `withoutChatCompletions toglie il suffisso con e senza slash e ignora il resto`() {
        assertEquals("http://h:8080/v1", withoutChatCompletions("http://h:8080/v1/chat/completions"))
        assertEquals("http://h:8080/v1", withoutChatCompletions("http://h:8080/v1/chat/completions/"))
        assertEquals("http://h:8080/v1", withoutChatCompletions("  http://h:8080/v1/Chat/Completions  "))
        assertEquals("https://h", withoutChatCompletions("https://h/chat/completions"))
        assertNull(withoutChatCompletions("http://h:8080/v1"))
        assertNull(withoutChatCompletions("http://h:8080/v1/"))
        assertNull(withoutChatCompletions("http://h:8080/chat/completions/extra"))
    }

    @Test
    fun `salvando un custom con chat completions l'url viene salvato senza il suffisso e l'utente lo sa`() =
        runTest(dispatcher) {
            val store = FakeProviderStore()
            val vm = settingsViewModel(store)
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()

            vm.saveProviderConfig(
                "custom_openai_compat",
                baseUrl = "http://192.168.1.20:8080/v1/chat/completions/",
                model = "qwen-local",
                apiKey = null,
            )
            advanceUntilIdle()

            assertEquals("http://192.168.1.20:8080/v1", store.saveCalls.last().baseUrl)
            assertTrue(
                "Ho tolto /chat/completions dall'indirizzo: Argus lo aggiunge da solo." in messages,
                messages.toString(),
            )
        }

    @Test
    fun `l'avviso sul suffisso e' in inglese con la lingua inglese`() = runTest(dispatcher) {
        val vm = settingsViewModel(FakeProviderStore(), language = RenderLanguage.EN)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.saveProviderConfig(
            "custom_openai_compat",
            baseUrl = "http://192.168.1.20:8080/v1/chat/completions",
            model = "qwen-local",
            apiKey = null,
        )
        advanceUntilIdle()

        assertTrue(
            "I removed /chat/completions from the address: Argus adds it by itself." in messages,
            messages.toString(),
        )
    }

    @Test
    fun `un url custom senza il suffisso viene salvato com'e' e senza avviso`() = runTest(dispatcher) {
        val store = FakeProviderStore()
        val vm = settingsViewModel(store)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.saveProviderConfig(
            "custom_openai_compat",
            baseUrl = "http://192.168.1.20:8080/v1",
            model = "qwen-local",
            apiKey = null,
        )
        advanceUntilIdle()

        assertEquals("http://192.168.1.20:8080/v1", store.saveCalls.last().baseUrl)
        assertTrue(messages.none { "chat/completions" in it }, messages.toString())
    }

    @Test
    fun `il suffisso non viene toccato per i provider con url fisso`() = runTest(dispatcher) {
        val store = FakeProviderStore()
        val vm = settingsViewModel(store)
        observe(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openai", baseUrl = "https://x.example/v1/chat/completions", model = "gpt-5.5", apiKey = null)
        advanceUntilIdle()

        assertEquals("https://x.example/v1/chat/completions", store.saveCalls.last().baseUrl)
    }

    // ------------------------------------------------------------------ 0.3.6 voce 3: preset OpenRouter

    @Test
    fun `solo openrouter espone il suggerimento sui preset`() = runTest(dispatcher) {
        for (provider in listOf(ProviderId.OPENROUTER, ProviderId.OPENAI, ProviderId.ANTHROPIC, ProviderId.GEMINI)) {
            val store = FakeProviderStore().apply { select(provider) }
            val vm = settingsViewModel(store)
            observe(vm)
            vm.refresh()
            advanceUntilIdle()

            val transport = vm.state.value.transport as TransportUi.DirectProvider
            assertEquals(provider == ProviderId.OPENROUTER, transport.modelPresetHint, provider.name)
        }
    }

    @Test
    fun `un preset scritto con la s avvisa senza correggere il modello`() = runTest(dispatcher) {
        val store = FakeProviderStore()
        val vm = settingsViewModel(store)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openrouter", baseUrl = null, model = "@presets/mio", apiKey = "sk-or-test-1234567890")
        advanceUntilIdle()

        assertEquals("@presets/mio", store.saveCalls.last().model, "il valore non si corregge da solo")
        assertTrue("Il formato è @preset/<nome>." in messages, messages.toString())
    }

    @Test
    fun `l'avviso sui preset e' in inglese con la lingua inglese`() = runTest(dispatcher) {
        val vm = settingsViewModel(FakeProviderStore(), language = RenderLanguage.EN)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openrouter", baseUrl = null, model = "@presets/mio", apiKey = "sk-or-test-1234567890")
        advanceUntilIdle()

        assertTrue("The format is @preset/<name>." in messages, messages.toString())
    }

    @Test
    fun `un preset corretto o un modello normale non avvisano`() = runTest(dispatcher) {
        val vm = settingsViewModel(FakeProviderStore())
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openrouter", baseUrl = null, model = "@preset/mio", apiKey = "sk-or-test-1234567890")
        vm.saveProviderConfig("openrouter", baseUrl = null, model = "deepseek/deepseek-v4.1-flash", apiKey = null)
        advanceUntilIdle()

        assertTrue(messages.none { "@preset/<" in it }, messages.toString())
    }

    @Test
    fun `l'avviso sui preset vale solo per openrouter`() = runTest(dispatcher) {
        val vm = settingsViewModel(FakeProviderStore())
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.saveProviderConfig("openai", baseUrl = null, model = "@presets/mio", apiKey = "sk-test-1234567890ab")
        advanceUntilIdle()

        assertTrue(messages.none { "@preset/<" in it }, messages.toString())
    }

    // ------------------------------------------------------------------ 0.3.6 voce 7: etichetta di installazione

    @Test
    fun `l'etichetta versione dice F-Droid per i client F-Droid noti e APK per tutto il resto`() =
        runTest(dispatcher) {
            val expectations = listOf(
                "org.fdroid.fdroid" to "F-Droid",
                "org.fdroid.basic" to "F-Droid",
                "com.looker.droidify" to "F-Droid",
                "com.machiav3lli.fdroid" to "F-Droid",
                "eu.bubu1.fdroidclassic" to "F-Droid",
                "com.android.vending" to "APK",
                "com.google.android.packageinstaller" to "APK",
                "com.example.unknown" to "APK",
                null to "APK",
            )
            expectations.forEach { (installer, label) ->
                val vm = settingsViewModel(
                    FakeProviderStore(),
                    installInfo = AppInstallInfo("0.3.6", installer, 34),
                )
                observe(vm)
                vm.refresh()
                advanceUntilIdle()
                assertEquals("Argus v0.3.6 · $label", vm.state.value.appVersionLabel, "installer=$installer")
            }
        }

    @Test
    fun `l'etichetta versione non dice mai sideload`() = runTest(dispatcher) {
        val vm = settingsViewModel(FakeProviderStore(), installInfo = AppInstallInfo(null, null, 34))
        observe(vm)
        vm.refresh()
        advanceUntilIdle()

        assertEquals("Argus v? · APK", vm.state.value.appVersionLabel)
        assertFalse("sideload" in vm.state.value.appVersionLabel.lowercase())
    }

    // ------------------------------------------------------------------ 0.3.6 voce 8: impostazioni con restrizioni

    @Test
    fun `la guida alle impostazioni con restrizioni compare solo per APK su Android 13 con accesso non concesso`() =
        runTest(dispatcher) {
            suspend fun hint(installer: String?, sdk: Int): Boolean {
                val vm = settingsViewModel(
                    FakeProviderStore(),
                    installInfo = AppInstallInfo("0.3.6", installer, sdk),
                )
                observe(vm)
                vm.refresh()
                advanceUntilIdle()
                // In Robolectric l'accesso alle notifiche non è concesso.
                assertFalse(vm.state.value.notificationListenerGranted)
                return vm.state.value.notificationListenerRestrictedHint
            }

            assertTrue(hint(installer = null, sdk = 33))
            assertTrue(hint(installer = "com.google.android.packageinstaller", sdk = 34))
            assertFalse(hint(installer = "org.fdroid.fdroid", sdk = 34), "F-Droid non ha restrizioni")
            assertFalse(hint(installer = "com.android.vending", sdk = 34), "lo store di sistema non ha restrizioni")
            assertFalse(hint(installer = null, sdk = 32), "prima di Android 13 non esiste")
        }

    // ------------------------------------------------------------------ 0.3.6 voce 5c: lingua dinamica

    @Test
    fun `i messaggi seguono il cambio di lingua del telefono senza ricreare il view model`() =
        runTest(dispatcher) {
            var system = RenderLanguage.IT
            val source = RenderLanguageSource { system }
            val vm = settingsViewModel(FakeProviderStore(), languageSource = source)
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()

            vm.selectProvider("gpt9000")
            advanceUntilIdle()
            assertEquals("Provider sconosciuto.", messages.last())

            system = RenderLanguage.EN
            source.refresh()
            vm.selectProvider("gpt9000")
            advanceUntilIdle()
            assertEquals("Unknown provider.", messages.last())
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
    fun `onSaveBudgetLimits salva ora giorno costo e token con una sola scrittura`() = runTest(dispatcher) {
        val settings = SlowBudgetSettingsStore()
        val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
        observe(vm)
        val messages = collectMessages(vm)
        advanceUntilIdle()

        vm.onSaveBudgetLimits(
            maxPerHour = 12,
            maxPerDay = 90,
            maxCostMonthMicros = 4_500_000L,
            maxTokensMonth = 2_000_000L,
        )
        advanceUntilIdle()

        val global = settings.observe().value.global
        assertEquals(12, global.maxCallsPerHour)
        assertEquals(90, global.maxCallsPerDay)
        assertEquals(4_500_000L, global.maxCostPerMonthMicros)
        assertEquals(2_000_000L, global.maxTokensPerMonth)
        assertEquals(1, settings.setGlobalCalls)
        assertEquals(listOf("Limiti budget salvati."), messages.toList())
    }

    @Test
    fun `onSaveBudgetLimits con zero azzera il limite e con un valore negativo non tocca nulla`() =
        runTest(dispatcher) {
            val settings = SlowBudgetSettingsStore(
                BudgetSettings(global = BudgetLimits(5, 50, 1_000_000L, 9_000L)),
            )
            val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
            observe(vm)
            val messages = collectMessages(vm)
            advanceUntilIdle()

            vm.onSaveBudgetLimits(0, 50, 0L, 9_000L)
            advanceUntilIdle()
            assertEquals(BudgetLimits(null, 50, null, 9_000L), settings.observe().value.global)

            vm.onSaveBudgetLimits(-1, 1, 1L, 1L)
            advanceUntilIdle()
            assertEquals(BudgetLimits(null, 50, null, 9_000L), settings.observe().value.global)
            assertEquals(listOf("Limiti budget salvati.", "Limite non valido."), messages.toList())
        }

    @Test
    fun `i quattro metodi singoli in fila non si sovrascrivono a vicenda`() = runTest(dispatcher) {
        val settings = SlowBudgetSettingsStore()
        val vm = settingsViewModel(FakeProviderStore(), budgetSettings = settings)
        observe(vm)
        advanceUntilIdle()

        vm.onBudgetChange(7)
        vm.onBudgetDayChange(70)
        vm.onBudgetMonthlyCostChange(3_000_000L)
        vm.onBudgetMonthlyTokensChange(800_000L)
        advanceUntilIdle()

        assertEquals(
            BudgetLimits(7, 70, 3_000_000L, 800_000L),
            settings.observe().value.global,
        )
    }

    // ------------------------------------------------------------------ D3: server locale senza chiave

    private fun TestScope.customTransport(
        baseUrl: String,
        key: String? = null,
        provider: ProviderId = ProviderId.CUSTOM_OPENAI_COMPAT,
    ): TransportUi.DirectProvider {
        val store = FakeProviderStore().apply {
            seed(provider, baseUrl = baseUrl, key = key)
            select(provider)
        }
        val vm = settingsViewModel(store)
        observe(vm)
        vm.refresh()
        advanceUntilIdle()
        val transport = vm.state.value.transport
        assertTrue(transport is TransportUi.DirectProvider, "atteso DirectProvider, era $transport")
        return transport as TransportUi.DirectProvider
    }

    @Test
    fun `custom su host loopback senza chiave segnala il server locale non autenticato`() =
        runTest(dispatcher) {
            for (url in listOf("http://127.0.0.1:8080/v1", "http://localhost:11434/v1", "http://[::1]:8080/v1")) {
                assertTrue(customTransport(url).unauthenticatedLoopback, "atteso avviso per $url")
            }
        }

    @Test
    fun `custom loopback con una chiave salvata non segnala nulla`() = runTest(dispatcher) {
        assertFalse(customTransport("http://127.0.0.1:8080/v1", key = "local-key-0123456789").unauthenticatedLoopback)
    }

    @Test
    fun `custom su host non loopback non segnala il server locale`() = runTest(dispatcher) {
        for (url in listOf(
            "https://llm.example.com/v1",
            "http://localhost.evil.example/v1",
            "https://127.0.0.1.nip.io/v1",
            "http://192.168.1.10:8080/v1",
        )) {
            assertFalse(customTransport(url).unauthenticatedLoopback, "nessun avviso atteso per $url")
        }
    }

    @Test
    fun `un provider diverso da custom non segnala il server locale anche senza chiave`() =
        runTest(dispatcher) {
            val transport = customTransport(
                baseUrl = "http://127.0.0.1:8080/v1",
                provider = ProviderId.OPENROUTER,
            )
            assertFalse(transport.unauthenticatedLoopback)
        }

    @Test
    fun `isLoopbackBaseUrl accetta solo gli host loopback esatti`() {
        assertTrue(isLoopbackBaseUrl("http://localhost/v1"))
        assertTrue(isLoopbackBaseUrl("http://127.0.0.1:1/v1"))
        assertTrue(isLoopbackBaseUrl("http://[::1]/v1"))
        assertFalse(isLoopbackBaseUrl("http://127.0.0.2/v1"))
        assertFalse(isLoopbackBaseUrl("http://localhost.evil.example/v1"))
        assertFalse(isLoopbackBaseUrl("not a url"))
        assertFalse(isLoopbackBaseUrl(""))
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

/**
 * Store con scrittura sospesa (come il commit() su Dispatchers.IO del vero store): lo snapshot letto
 * prima di scrivere diventa stantio se due scritture si accavallano.
 */
private class SlowBudgetSettingsStore(
    initial: BudgetSettings = BudgetSettings(),
) : BudgetSettingsStore {
    private val flow = MutableStateFlow(initial)
    var setGlobalCalls = 0
        private set

    override fun observe(): StateFlow<BudgetSettings> = flow.asStateFlow()

    override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean {
        setGlobalCalls++
        delay(10)
        if ((limits.maxCallsPerHour ?: 0) < 0 || (limits.maxCallsPerDay ?: 0) < 0 ||
            (limits.maxCostPerMonthMicros ?: 0L) < 0L || (limits.maxTokensPerMonth ?: 0L) < 0L
        ) {
            return false
        }
        flow.value = flow.value.copy(
            global = BudgetLimits(
                maxCallsPerHour = limits.maxCallsPerHour?.takeIf { it > 0 },
                maxCallsPerDay = limits.maxCallsPerDay?.takeIf { it > 0 },
                maxCostPerMonthMicros = limits.maxCostPerMonthMicros?.takeIf { it > 0L },
                maxTokensPerMonth = limits.maxTokensPerMonth?.takeIf { it > 0L },
            ),
        )
        return true
    }

    override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean = false

    override suspend fun setSoftThresholdPct(pct: Int): Boolean = false
}

// ----------------------------------------------------------------------- fakes

private class FakeProviderStore(
    initialSelected: ProviderId = ProviderId.HERMES,
) : ProviderConfigStore {
    class Slot(
        var baseUrl: String? = null,
        var model: String? = null,
        var key: String? = null,
        var reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    )
    data class SaveCall(
        val id: ProviderId,
        val baseUrl: String?,
        val model: String?,
        val apiKey: String?,
        val reasoningEffort: ReasoningEffort?,
    )

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
        return ProviderConfig(
            providerId = id,
            baseUrl = baseUrl,
            model = s?.model,
            reasoningEffort = s?.reasoningEffort ?: ReasoningEffort.DEFAULT,
        )
    }

    override suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
        reasoningEffort: ReasoningEffort?,
    ): Boolean {
        saveCalls += SaveCall(id, baseUrl, model, apiKey, reasoningEffort)
        val s = slot(id)
        baseUrl?.let { s.baseUrl = it }
        model?.let { s.model = it }
        apiKey?.let { s.key = it }
        reasoningEffort?.let { s.reasoningEffort = it }
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

/** Transport il cui test di connessione fallisce con l'eccezione data (voce «il test spiega la causa»). */
private class FailingHealthTransport(
    override val providerId: ProviderId,
    private val failure: TransportException,
) : AgentTransport {
    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult = error("non usato")

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = error("non usato")

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult = error("non usato")

    override suspend fun health(): TransportHealth = throw failure
}
