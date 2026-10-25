package dev.argus.automation.vm

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AppInstallInfo
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.ClipboardCopier
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.apps.EmptyInstalledAppResolver
import dev.argus.automation.apps.InstalledAppCandidate
import dev.argus.automation.apps.InstalledAppResolver
import dev.argus.brain.AgentTransport
import dev.argus.brain.BridgeErrorKind
import dev.argus.brain.BridgeException
import dev.argus.brain.TransportBackedBrain
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.model.Transition
import dev.argus.ui.model.NoticeKind
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.ProviderId
import dev.argus.brain.TransportFactory
import dev.argus.brain.TransportHealth
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.AUTOMATION_SCHEMA_VERSION_V1
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.toDraft
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.ApprovalWhitelistProvider
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftDeleteResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.ui.model.ChatItem
import dev.argus.ui.model.ChatError
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cold start does not contact the configured brain until health is requested`() =
        runTest(dispatcher) {
            val transport = CountingHealthTransport()
            val configuration = ViewModelBridgeConfiguration()
            val configuredBridge = ConfiguredBridgeBrain(
                configuration = configuration,
                privacyAccepted = { true },
                factory = TransportFactory { transport },
                elapsedRealtimeMillis = { 0L },
            )
            val viewModel = chatViewModel(
                brain = QueuedBrain(),
                configuredBridge = configuredBridge,
            )

            runCurrent()

            assertEquals(0, transport.healthCalls)
            assertEquals(null, viewModel.state.value.brainReachable)

            viewModel.refreshHealth()
            runCurrent()

            assertEquals(1, transport.healthCalls)
            assertEquals(true, viewModel.state.value.brainReachable)
        }

    @Test
    fun `compile timeout is shown as timeout instead of unreachable`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)

        viewModel.onInputChange("slow local model")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst()

        advanceTimeBy(65_001L)
        runCurrent()

        assertEquals(ChatError.Timeout, viewModel.state.value.error)
        assertEquals(null, viewModel.state.value.brainReachable)
        assertFalse(viewModel.state.value.sending)
    }

    @Test
    fun `a cancelled request cannot clear or overwrite the next request`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)

        viewModel.prefillEdit("prima", automationId = "edit-obsoleto")
        viewModel.onSend()
        runCurrent()
        val first = brain.removeFirst()
        assertEquals("prima", first.prompt)
        assertTrue(viewModel.state.value.sending)

        viewModel.onCancelPending()
        viewModel.onInputChange("seconda")
        viewModel.onSend()
        runCurrent()
        val second = brain.removeFirst()

        assertEquals("seconda", second.prompt)
        assertTrue(viewModel.state.value.sending)
        assertFalse(first.response.isCompleted)

        second.response.complete(
            CompileResult(
                reply = "Regola pronta",
                draft = AutomationDraft(
                    name = "DND sera",
                    trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                    actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
                ),
                metaError = null,
            ),
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.sending)
        assertEquals(true, state.brainReachable)
        assertTrue(state.items.any { it is ChatItem.UserMessage && it.text == "prima" })
        assertTrue(state.items.any { it is ChatItem.UserMessage && it.text == "seconda" })
        assertTrue(
            state.items.any {
                it is ChatItem.DraftCard &&
                    it.rule.triggerLine.contains("23:00") &&
                    it.rule.actions.any { action -> action.label.contains("Non disturbare") }
            },
        )
    }

    @Test
    fun `clarification is conversational and the next answer retains its context`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(
            brain,
            installedApps = InstalledAppResolver {
                listOf(InstalledAppCandidate("Messaggi", "com.google.android.apps.messaging"))
            },
        )
        val original = "copia dagli SMS e dagli RCS i codici OTP e mettili negli appunti"
        val question = "Vuoi una regola per SMS e una separata per RCS?"

        viewModel.onInputChange(original)
        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(
            CompileResult(
                reply = question,
                draft = null,
                metaError = "clarification_required",
            ),
        )
        advanceUntilIdle()

        val clarified = viewModel.state.value
        assertEquals(null, clarified.error)
        assertEquals(true, clarified.brainReachable)
        assertTrue(
            clarified.items.any {
                it is ChatItem.AssistantMessage && it.text == question
            },
        )
        assertFalse(clarified.items.any { it is ChatItem.SystemNotice })

        val answer = "Due regole separate."
        viewModel.onInputChange(answer)
        viewModel.onSend()
        runCurrent()
        val continuation = brain.removeFirst()

        assertTrue(continuation.prompt.contains(original), continuation.prompt)
        assertTrue(continuation.prompt.contains(question), continuation.prompt)
        assertTrue(continuation.prompt.contains(answer), continuation.prompt)
        assertTrue(
            continuation.prompt.contains("com.google.android.apps.messaging"),
            continuation.prompt,
        )

        continuation.response.complete(
            CompileResult(
                reply = "Regola SMS pronta",
                draft = AutomationDraft(
                    name = "OTP da SMS",
                    trigger = Trigger.PhoneState(
                        event = PhoneEvent.SMS_RECEIVED,
                        textMatch = "codice",
                    ),
                    actions = listOf(Action.CopyText("OTP ricevuto")),
                ),
                metaError = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.error)
        assertTrue(viewModel.state.value.items.any { it is ChatItem.DraftCard })
    }

    @Test
    fun `draft cards show the trusted whitelist name instead of the conversation hash`() = runTest(dispatcher) {
        val hash = "shortcut:com.whatsapp:" + "62be4c2af7a1d9e3".repeat(4)
        val brain = QueuedBrain()
        val whitelist = ViewModelWhitelistStore(WhitelistedContact("Ottica Marci", hash))
        val viewModel = chatViewModel(brain, whitelist)

        viewModel.onInputChange("rispondi a ottica quando sono occupato")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(
            CompileResult(
                reply = "Regola pronta",
                draft = AutomationDraft(
                    name = "Rispondi a Ottica Marci",
                    trigger = Trigger.Notification(
                        "com.whatsapp",
                        conversationId = hash,
                        isGroup = false,
                    ),
                    actions = listOf(
                        Action.InvokeLlm(
                            "rispondi che sono occupato",
                            listOf("notification"),
                            listOf("whatsapp_reply"),
                            true,
                        ),
                    ),
                ),
                metaError = null,
            ),
        )
        advanceUntilIdle()

        // La review è un security control: il criterio di match reale (id whitelistato) va
        // descritto col nome fidato dallo store, mai con la "sbobba" dell'hash.
        val card = viewModel.state.value.items.filterIsInstance<ChatItem.DraftCard>().single()
        assertTrue(
            card.rule.triggerLine.contains("da Ottica Marci (identità verificata, chat 1:1)"),
            card.rule.triggerLine,
        )
        assertFalse(card.rule.triggerLine.contains(hash), card.rule.triggerLine)
    }

    @Test
    fun `clear conversation removes messages but keeps pending draft cards`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)

        viewModel.onInputChange("dopo le 23 metti non disturbare")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(
            CompileResult(
                reply = "Regola pronta",
                draft = AutomationDraft(
                    name = "DND sera",
                    trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                    actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
                ),
                metaError = null,
            ),
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.any { it is ChatItem.UserMessage })
        assertTrue(viewModel.state.value.items.any { it is ChatItem.AssistantMessage })
        assertTrue(viewModel.state.value.items.any { it is ChatItem.DraftCard })

        viewModel.onClearConversation()

        // La chat si svuota ma le proposte in sospeso restano: sono il canale di approvazione,
        // non cronologia (§3 — l'arm passa dalla review, mai dai messaggi).
        val items = viewModel.state.value.items
        assertTrue(items.all { it is ChatItem.DraftCard }, "restano solo le card: $items")
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun `english chat emits english system notices`() = runTest(dispatcher) {
        val viewModel = chatViewModel(QueuedBrain(), language = RenderLanguage.EN)
        viewModel.prefillEdit(
            text = "change it",
            baseDraft = AutomationDraft(
                name = "Rule",
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                actions = listOf(Action.SetDnd(DndMode.TOTAL)),
            ),
        )
        assertEquals(
            "To preserve every field, Argus will send the configured AI service the full context " +
                "of the displayed rule.",
            viewModel.state.value.items.filterIsInstance<ChatItem.SystemNotice>().last().text,
        )

        viewModel.onInputChange("test")
        viewModel.onSend()
        runCurrent()
        viewModel.onCancelPending()

        val notice = viewModel.state.value.items.filterIsInstance<ChatItem.SystemNotice>().last()
        assertEquals("Request cancelled.", notice.text)
    }

    @Test
    fun `edit prompt carries the complete current draft without persisting it`() {
        val base = AutomationDraft(
            name = "DND sera",
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.TOTAL)),
            cooldownMs = 60_000,
        )

        val prompt = composeEditPrompt("spostala alle 22", base)

        assertTrue(prompt.contains("ARGUS_CURRENT_RULE_JSON"))
        assertTrue(prompt.startsWith("Edit the Argus rule shown below."))
        assertTrue(prompt.contains("DND sera"))
        assertTrue(prompt.contains("TOTAL"))
        assertTrue(prompt.contains("60000"))
        assertTrue(prompt.endsWith("spostala alle 22"))
    }

    @Test
    fun `edit prompt never carries the real conversation id and the placeholder is restored`() {
        val realId = "shortcut:com.whatsapp:abc123def456"
        val base = AutomationDraft(
            name = "Rispondi a Moglie",
            trigger = Trigger.Notification("com.whatsapp", conversationId = realId, isGroup = false),
            actions = listOf(Action.ShowNotification("Argus", "Messaggio")),
        )

        val prompt = composeEditPrompt("solo la sera", base)

        assertFalse(prompt.contains(realId))
        assertTrue(prompt.contains(CURRENT_CONTACT_PLACEHOLDER))
        val fromModel = redactConversationId(base).copy(name = "Rispondi a Moglie la sera")
        assertEquals(realId, (restoreConversationId(fromModel, base).trigger as Trigger.Notification).conversationId)
        val changedContact = base.copy(trigger = Trigger.Notification("com.whatsapp", conversationId = "other"))
        assertEquals("other", (restoreConversationId(changedContact, base).trigger as Trigger.Notification).conversationId)
    }

    // --- B2: un provider diretto restituisce error_code nel meta ---

    @Test
    fun `a clarification from a direct provider starts the dialogue and the answer is compiled with its context`() =
        runTest(dispatcher) {
            val transport = ScriptedDirectTransport(
                """Quale contatto devo avvisare?
                |@@META@@ {"draft":null,"error_code":"clarification_required"}""".trimMargin(),
                """Ok, regola pronta.
                |@@META@@ {"draft":{"name":"DND sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]}}""".trimMargin(),
            )
            val viewModel = chatViewModel(TransportBackedBrain(transport))
            val original = "avvisami quando arrivo a casa"

            viewModel.onInputChange(original)
            viewModel.onSend()
            advanceUntilIdle()

            val clarified = viewModel.state.value
            assertEquals(null, clarified.error)
            assertEquals(true, clarified.brainReachable)
            assertTrue(clarified.items.any { it is ChatItem.AssistantMessage && it.text == "Quale contatto devo avvisare?" })
            assertFalse(clarified.items.any { it is ChatItem.SystemNotice }, "nessun avviso d'errore per un chiarimento")

            viewModel.onInputChange("Moglie")
            viewModel.onSend()
            advanceUntilIdle()

            val continuation = transport.messages[1]
            assertTrue(continuation.contains(original), continuation)
            assertTrue(continuation.contains("Quale contatto devo avvisare?"), continuation)
            assertTrue(continuation.contains("Moglie"), continuation)
            assertEquals(null, viewModel.state.value.error)
            assertTrue(viewModel.state.value.items.any { it is ChatItem.DraftCard })
        }

    @Test
    fun `unsupported capability and limit exceeded show the model prose and an info notice, no error banner`() =
        runTest(dispatcher) {
            for ((language, notice) in listOf(
                RenderLanguage.IT to "Nessuna regola creata.",
                RenderLanguage.EN to "No rule was created.",
            )) {
                for (code in listOf("unsupported_capability", "limit_exceeded", "contact_not_whitelisted")) {
                    val brain = QueuedBrain()
                    val viewModel = chatViewModel(brain, language = language)

                    viewModel.onInputChange("fai una cosa impossibile")
                    viewModel.onSend()
                    runCurrent()
                    brain.removeFirst().response.complete(CompileResult("Spiegazione del modello.", null, code))
                    advanceUntilIdle()

                    val state = viewModel.state.value
                    assertEquals(null, state.error, code)
                    assertEquals(true, state.brainReachable, code)
                    assertFalse(state.sending)
                    assertTrue(
                        state.items.any { it is ChatItem.AssistantMessage && it.text == "Spiegazione del modello." },
                        code,
                    )
                    val infos = state.items.filterIsInstance<ChatItem.SystemNotice>()
                    assertEquals(listOf(notice), infos.map { it.text }, code)
                    assertEquals(NoticeKind.INFO, infos.single().kind)
                }
            }
        }

    // --- B10: richieste lunghe e errori di configurazione ---

    @Test
    fun `the text field accepts at most 8000 characters`() = runTest(dispatcher) {
        val viewModel = chatViewModel(QueuedBrain())

        viewModel.onInputChange("a".repeat(9_000))

        assertEquals(8_000, viewModel.state.value.input.length)
    }

    @Test
    fun `a composed prompt over 32768 characters is refused locally without calling the brain`() =
        runTest(dispatcher) {
            val brain = QueuedBrain()
            val viewModel = chatViewModel(brain)
            val huge = AutomationDraft(
                name = "x".repeat(40_000),
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
            )

            viewModel.prefillEdit(text = "cambiala", baseDraft = huge)
            viewModel.onSend()
            advanceUntilIdle()

            val state = viewModel.state.value
            val error = assertIs<ChatError.TooLong>(state.error)
            assertEquals(32_768, error.max)
            assertTrue(error.chars > 32_768)
            assertFalse(state.sending)
            assertEquals(null, state.brainReachable, "un errore locale non dice nulla sulla raggiungibilità")
            assertFailsWith<NoSuchElementException> { brain.removeFirst() }
        }

    @Test
    fun `configuration errors are never shown as unreachable`() = runTest(dispatcher) {
        val failures = listOf<suspend () -> CompileResult>(
            { throw BridgeException(BridgeErrorKind.CONFIGURATION, "consenso privacy non accettato") },
            { CompileResult("", null, "bridge_configuration") },
        )
        for (failure in failures) {
            val viewModel = chatViewModel(FailingBrain(failure))

            viewModel.onInputChange("qualcosa")
            viewModel.onSend()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(ChatError.NotConfigured, state.error)
            assertEquals(null, state.brainReachable)
            assertFalse(state.sending)
        }
    }

    @Test
    fun `brain reachable is false only for the network and the chat is never blocked`() = runTest(dispatcher) {
        val expectations = mapOf(
            "bridge_network" to false,
            "bridge_auth" to null,
            "bridge_timeout" to null,
            "bridge_http_503" to true,
            "bridge_http_422" to true,
            "bridge_rate_limit" to true,
            "bridge_budget" to true,
            "output_truncated" to true,
            "context_truncated" to true,
            "meta_parse_error" to true,
        )
        for ((code, reachable) in expectations) {
            val viewModel = chatViewModel(FailingBrain { CompileResult("", null, code) })

            viewModel.onInputChange("qualcosa")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(reachable, viewModel.state.value.brainReachable, code)
            assertFalse(viewModel.state.value.sending, code)
        }
    }

    @Test
    fun `meta codes map to their explained error variant`() {
        val it = RenderLanguage.IT
        assertEquals(ChatError.Timeout, chatErrorForCode("bridge_timeout", null, it))
        assertEquals(ChatError.BridgeUnreachable, chatErrorForCode("bridge_network", null, it))
        assertEquals(ChatError.Auth, chatErrorForCode("bridge_auth", null, it))
        assertEquals(ChatError.Budget, chatErrorForCode("bridge_budget", null, it))
        assertEquals(ChatError.Budget, chatErrorForCode("budget_exceeded", null, it))
        assertEquals(ChatError.Budget, chatErrorForCode("budget_unpriced_model", null, it))
        assertEquals(ChatError.RateLimit, chatErrorForCode("bridge_rate_limit", null, it))
        assertEquals(ChatError.OutputTruncated, chatErrorForCode("output_truncated", null, it))
        assertEquals(ChatError.ContextTruncated, chatErrorForCode("context_truncated", null, it))
        assertEquals(ChatError.NotConfigured, chatErrorForCode("bridge_configuration", null, it))
        assertEquals(ChatError.ServerRejected(400), chatErrorForCode("bridge_http_400", null, it))
        assertEquals(ChatError.ServerRejected(413), chatErrorForCode("bridge_http_413", null, it))
        assertEquals(ChatError.ServiceUnavailable(500), chatErrorForCode("bridge_http_500", null, it))
        assertEquals(ChatError.ServiceUnavailable(503), chatErrorForCode("bridge_http_503", null, it))
        // 401/402/403/429 arrivati come HTTP restano comunque le varianti dedicate.
        assertEquals(ChatError.Auth, chatErrorForCode("bridge_http_401", null, it))
        assertEquals(ChatError.Budget, chatErrorForCode("bridge_http_402", null, it))
        assertEquals(ChatError.RateLimit, chatErrorForCode("bridge_http_429", null, it))
        // Il resto è una risposta senza regola valida: il dettaglio del parser, se c'è, altrimenti il codice.
        assertEquals(ChatError.MalformedReply("draft_missing"), chatErrorForCode("draft_missing", null, it))
        assertEquals(
            ChatError.MalformedReply("Field 'tz' is required at path: \$.draft.trigger"),
            chatErrorForCode("meta_parse_error", "Field 'tz' is required at path: \$.draft.trigger", it),
        )
    }

    @Test
    fun `a transport failure shows only the banner, not the model-side notice`() = runTest(dispatcher) {
        val viewModel = chatViewModel(FailingBrain { CompileResult("", null, "bridge_http_503") })

        viewModel.onInputChange("qualcosa")
        viewModel.onSend()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ChatError.ServiceUnavailable(503), state.error)
        assertFalse(state.items.any { it is ChatItem.SystemNotice })
        assertFalse(state.items.any { it is ChatItem.AssistantMessage }, "il testo lo dà la UI localizzata")
    }

    @Test
    fun `the parser detail is shown for a format failure`() = runTest(dispatcher) {
        val viewModel = chatViewModel(
            FailingBrain { CompileResult("boh", null, "meta_parse_error", detail = "Field 'actions' is required") },
        )

        viewModel.onInputChange("qualcosa")
        viewModel.onSend()
        advanceUntilIdle()

        assertEquals(ChatError.MalformedReply("Field 'actions' is required"), viewModel.state.value.error)
        assertTrue(viewModel.state.value.items.any { it is ChatItem.SystemNotice })
    }

    // --- B12: timeout del compile per provider ---

    @Test
    fun `a local custom endpoint waits 180 seconds for the compile`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val configuredBridge = ConfiguredBridgeBrain(
            configuration = ViewModelBridgeConfiguration(ProviderId.CUSTOM_OPENAI_COMPAT, "http://192.168.1.20:11434/v1"),
            privacyAccepted = { true },
            elapsedRealtimeMillis = { 0L },
        )
        val viewModel = chatViewModel(brain, configuredBridge = configuredBridge)

        viewModel.onInputChange("modello locale lento")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst()

        advanceTimeBy(65_001L)
        runCurrent()
        assertTrue(viewModel.state.value.sending, "il modello locale ha ancora tempo")
        assertEquals(null, viewModel.state.value.error)

        advanceTimeBy(115_000L)
        runCurrent()
        assertEquals(ChatError.Timeout, viewModel.state.value.error)
        assertFalse(viewModel.state.value.sending)
    }

    // --- S4: le coordinate non partono mai verso il modello ---

    @Test
    fun `an edit never sends the original coordinates and gets them back in the draft`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)
        val base = AutomationDraft(
            name = "Casa",
            trigger = Trigger.Geofence(
                lat = 45.4642, lng = 9.1912, radiusM = 150.0, transition = Transition.EXIT,
            ),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )

        viewModel.prefillEdit(text = "metti non disturbare totale", baseDraft = base)
        viewModel.onSend()
        runCurrent()
        val request = brain.removeFirst()

        assertFalse("45.4642" in request.prompt, request.prompt)
        assertFalse("9.1912" in request.prompt, request.prompt)
        assertTrue(
            "Coordinates were replaced with 0,0 for privacy: keep exactly 0,0 unless the user asks to change the place." in
                request.prompt,
        )

        // Il modello restituisce 0,0 come richiesto: l'app rimette il luogo vero PRIMA dell'approvazione.
        request.response.complete(
            CompileResult(
                "Fatto.",
                base.copy(
                    trigger = (base.trigger as Trigger.Geofence).copy(lat = 0.0, lng = 0.0),
                    actions = listOf(Action.SetDnd(DndMode.TOTAL)),
                ),
                null,
            ),
        )
        advanceUntilIdle()

        val card = viewModel.state.value.items.filterIsInstance<ChatItem.DraftCard>().single()
        assertTrue("45.4642,9.1912" in card.rule.triggerLine, card.rule.triggerLine)
    }

    // ------------------------------------------------------------------ 0.3.6 · B34a: dopo un conflitto si riparte dalla versione corrente

    private fun pendingDraft(name: String) = AutomationDraft(
        name = name,
        trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
        actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
    )

    private fun compiled(draft: AutomationDraft, raw: String? = null) =
        CompileResult(reply = "Fatto.", draft = draft, metaError = null, raw = raw)

    private fun armedAutomation(id: String, name: String): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = name,
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private suspend fun ViewModelDraftRepository.seed(id: String, automation: String, draft: AutomationDraft): PendingDraft =
        (create(NewDraft(id = DraftId(id), automationId = AutomationId(automation), draft = draft, atMillis = 1L))
            as DraftWriteResult.Saved).snapshot

    @Test
    fun `after a conflict the next send starts from the current revision and succeeds`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val drafts = ViewModelDraftRepository()
        val viewModel = chatViewModel(brain, drafts = drafts)
        val original = drafts.seed("d1", "a1", pendingDraft("DND sera"))
        viewModel.prefillEdit(
            "spostala alle 22",
            draftId = "d1",
            draftRevision = original.revision,
            baseDraft = original.draft,
        )
        // Un'altra modifica passa prima: la bozza è ora alla revisione 2, con un altro nome.
        drafts.revise(DraftId("d1"), original.revision, pendingDraft("DND sera v2"), 0, 2L)

        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(compiled(pendingDraft("DND 22")))
        advanceUntilIdle()
        assertTrue(
            viewModel.state.value.items.any { it is ChatItem.SystemNotice && "La bozza è cambiata" in it.text },
        )
        assertEquals(2L, drafts.get(DraftId("d1"))?.revision, "nulla è stato sovrascritto")

        viewModel.onInputChange("riprova")
        viewModel.onSend()
        runCurrent()
        val second = brain.removeFirst()
        assertTrue("DND sera v2" in second.prompt, "il secondo invio parte dalla versione corrente: ${second.prompt}")
        second.response.complete(compiled(pendingDraft("DND 22")))
        advanceUntilIdle()

        assertEquals(3L, drafts.get(DraftId("d1"))?.revision, "il secondo invio ha avuto successo")
        assertEquals("DND 22", drafts.get(DraftId("d1"))?.draft?.name)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun `a conflict on a draft that no longer exists closes edit mode with a notice`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)
        viewModel.prefillEdit(
            "cambiala",
            draftId = "sparita",
            draftRevision = 4L,
            baseDraft = pendingDraft("Vecchia"),
        )

        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(compiled(pendingDraft("Nuova")))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(
            state.items.any {
                it is ChatItem.SystemNotice && it.kind == NoticeKind.ERROR && "non esiste più" in it.text
            },
            state.items.toString(),
        )
        assertEquals(null, state.error)

        // Fuori dalla modifica il prossimo invio crea una bozza nuova, senza il contesto della regola.
        viewModel.onInputChange("crea una regola nuova")
        viewModel.onSend()
        runCurrent()
        val next = brain.removeFirst()
        assertFalse("ARGUS_CURRENT_RULE_JSON" in next.prompt, next.prompt)
        next.response.complete(compiled(pendingDraft("Nuova")))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.any { it is ChatItem.DraftCard })
    }

    @Test
    fun `a stale rule edit is realigned to the current fingerprint and the next send succeeds`() =
        runTest(dispatcher) {
            val automations = ViewModelAutomationStore()
            val drafts = ViewModelDraftRepository()
            val current = armedAutomation("a1", "DND sera")
            automations.seed(current)
            val brain = QueuedBrain()
            val viewModel = chatViewModel(brain, drafts = drafts, automations = automations)
            viewModel.prefillEdit(
                "spostala",
                automationId = "a1",
                automationFingerprint = ApprovalFingerprint("f".repeat(64)),
                baseDraft = pendingDraft("Versione vecchia"),
            )

            viewModel.onSend()
            runCurrent()
            brain.removeFirst().response.complete(compiled(pendingDraft("DND 22")))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.items.any { it is ChatItem.SystemNotice && "automation_stale" in it.text })

            viewModel.onInputChange("riprova")
            viewModel.onSend()
            runCurrent()
            val second = brain.removeFirst()
            assertTrue("DND sera" in second.prompt && "Versione vecchia" !in second.prompt, second.prompt)
            second.response.complete(compiled(pendingDraft("DND 22")))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.items.any { it is ChatItem.DraftCard })
            assertEquals(1, drafts.observeAll().first().size)
        }

    @Test
    fun `a rule edit whose rule was deleted closes edit mode`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)
        viewModel.prefillEdit(
            "spostala",
            automationId = "cancellata",
            automationFingerprint = ApprovalFingerprint("a".repeat(64)),
            baseDraft = pendingDraft("Vecchia"),
        )

        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(compiled(pendingDraft("Nuova")))
        advanceUntilIdle()

        assertTrue(
            viewModel.state.value.items.any {
                it is ChatItem.SystemNotice && it.kind == NoticeKind.ERROR && "non esiste più" in it.text
            },
        )
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun `a conflict on a rule edit continues on the pending draft of that rule`() = runTest(dispatcher) {
        val automations = ViewModelAutomationStore()
        val drafts = ViewModelDraftRepository()
        val current = armedAutomation("a1", "DND sera")
        automations.seed(current)
        drafts.seed("d9", "a1", pendingDraft("DND in attesa"))
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain, drafts = drafts, automations = automations)
        viewModel.prefillEdit(
            "spostala",
            automationId = "a1",
            automationFingerprint = current.approvalFingerprint,
            baseDraft = current.toDraft(),
        )

        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(compiled(pendingDraft("DND 22")))
        advanceUntilIdle()
        assertEquals(1L, drafts.get(DraftId("d9"))?.revision, "il conflitto non ha toccato la bozza")

        viewModel.onInputChange("riprova")
        viewModel.onSend()
        runCurrent()
        val second = brain.removeFirst()
        assertTrue("DND in attesa" in second.prompt, second.prompt)
        second.response.complete(compiled(pendingDraft("DND 22")))
        advanceUntilIdle()

        assertEquals(2L, drafts.get(DraftId("d9"))?.revision)
        assertEquals("DND 22", drafts.get(DraftId("d9"))?.draft?.name)
    }

    // ------------------------------------------------------------------ 0.3.6 · B34b: errori interni dell'app

    @Test
    fun `a local exception is an internal error named after its class and never the model or network banner`() =
        runTest(dispatcher) {
            val cases = listOf(
                IllegalStateException("dato personale: Mario Rossi") to "illegal_state_exception",
                IllegalArgumentException("require fallita") to "illegal_argument_exception",
                NullPointerException() to "null_pointer_exception",
                UnsupportedOperationException("x") to "unsupported_operation_exception",
            )
            for ((failure, code) in cases) {
                val viewModel = chatViewModel(FailingBrain { throw failure })

                viewModel.onInputChange("qualcosa")
                viewModel.onSend()
                advanceUntilIdle()

                val state = viewModel.state.value
                assertEquals(ChatError.Internal(code), state.error, failure.toString())
                assertFalse(state.sending)
                assertEquals(null, state.brainReachable, "un errore locale non dice nulla sulla raggiungibilità")
                assertFalse("Mario" in state.toString(), "mai il messaggio dell'eccezione")
            }
        }

    // ------------------------------------------------------------------ 0.3.6 · F5: risposta grezza

    @Test
    fun `the raw response reaches the state on success and on failure`() = runTest(dispatcher) {
        val ok = chatViewModel(FailingBrain { compiled(pendingDraft("DND sera"), raw = "RAW-OK-123") })
        ok.onInputChange("qualcosa")
        ok.onSend()
        advanceUntilIdle()
        assertEquals("RAW-OK-123", ok.state.value.rawResponse)

        val failed = chatViewModel(
            FailingBrain { CompileResult("boh", null, "draft_missing", raw = "RAW-KO-456") },
        )
        failed.onInputChange("qualcosa")
        failed.onSend()
        advanceUntilIdle()
        assertEquals("RAW-KO-456", failed.state.value.rawResponse)
        assertEquals(ChatError.MalformedReply("draft_missing"), failed.state.value.error)
    }

    @Test
    fun `the raw response is cleared by a new send and shows only the last turn`() = runTest(dispatcher) {
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain)

        viewModel.onInputChange("uno")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst().response.complete(CompileResult("boh", null, "draft_missing", raw = "RAW-UNO"))
        advanceUntilIdle()
        assertEquals("RAW-UNO", viewModel.state.value.rawResponse)

        viewModel.onInputChange("due")
        viewModel.onSend()
        runCurrent()
        assertEquals(null, viewModel.state.value.rawResponse, "azzerata all'invio")
        brain.removeFirst().response.complete(CompileResult("boh", null, "draft_missing", raw = "RAW-DUE"))
        advanceUntilIdle()
        assertEquals("RAW-DUE", viewModel.state.value.rawResponse)
    }

    @Test
    fun `a transport failure leaves no raw response`() = runTest(dispatcher) {
        val viewModel = chatViewModel(FailingBrain { CompileResult("", null, "bridge_http_503") })

        viewModel.onInputChange("qualcosa")
        viewModel.onSend()
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.rawResponse)
    }

    @Test
    fun `the raw response is cleared by a new conversation and by leaving edit mode`() = runTest(dispatcher) {
        val clearing = chatViewModel(FailingBrain { CompileResult("boh", null, "draft_missing", raw = "RAW-A") })
        clearing.onInputChange("uno")
        clearing.onSend()
        advanceUntilIdle()
        assertEquals("RAW-A", clearing.state.value.rawResponse)
        clearing.onClearConversation()
        assertEquals(null, clearing.state.value.rawResponse)

        val editing = chatViewModel(FailingBrain { CompileResult("boh", null, "draft_missing", raw = "RAW-B") })
        editing.prefillEdit("cambia", baseDraft = pendingDraft("Regola"))
        editing.onSend()
        advanceUntilIdle()
        assertEquals("RAW-B", editing.state.value.rawResponse)
        editing.onInputChange("")
        assertEquals(null, editing.state.value.rawResponse, "svuotare il campo in modifica esce dalla modifica")

        // Fuori dalla modifica svuotare il campo non fa sparire la risposta dell'ultimo turno.
        val plain = chatViewModel(FailingBrain { CompileResult("boh", null, "draft_missing", raw = "RAW-C") })
        plain.onInputChange("uno")
        plain.onSend()
        advanceUntilIdle()
        plain.onInputChange("")
        assertEquals("RAW-C", plain.state.value.rawResponse)
    }

    @Test
    fun `the raw response never reaches the draft repository or the chat items`() = runTest(dispatcher) {
        val marker = "RAW-MARKER-DO-NOT-STORE"
        val drafts = ViewModelDraftRepository()
        val viewModel = chatViewModel(
            FailingBrain { compiled(pendingDraft("DND sera"), raw = marker) },
            drafts = drafts,
        )

        viewModel.onInputChange("qualcosa")
        viewModel.onSend()
        advanceUntilIdle()

        assertEquals(marker, viewModel.state.value.rawResponse)
        assertFalse(marker in drafts.observeAll().first().toString(), "mai nella bozza")
        assertFalse(marker in viewModel.state.value.items.toString(), "mai tra i messaggi della chat")
    }

    @Test
    fun `copying for the report writes version provider model error code and the raw text in a fenced block`() =
        runTest(dispatcher) {
            val clipboard = RecordingClipboard()
            val viewModel = chatViewModel(
                FailingBrain { CompileResult("boh", null, "draft_missing", raw = "prima riga\nseconda riga") },
                clipboard = clipboard,
                installInfo = AppInstallInfo("0.3.6", "org.fdroid.fdroid", 34),
            )
            viewModel.onInputChange("qualcosa")
            viewModel.onSend()
            advanceUntilIdle()

            viewModel.onCopyRawResponse()

            assertEquals(
                listOf(
                    "App: Argus 0.3.6 (F-Droid)\n" +
                        "Provider: hermes\n" +
                        "Model: -\n" +
                        "Error code: draft_missing\n" +
                        "Raw response:\n" +
                        "```\n" +
                        "prima riga\nseconda riga\n" +
                        "```",
                ),
                clipboard.copied,
            )
            assertTrue(
                viewModel.state.value.items.any {
                    it is ChatItem.SystemNotice && it.kind == NoticeKind.INFO && "copiata" in it.text
                },
            )
        }

    @Test
    fun `copying a successful turn omits the error code and copying without a raw response does nothing`() =
        runTest(dispatcher) {
            val clipboard = RecordingClipboard()
            val viewModel = chatViewModel(
                FailingBrain { compiled(pendingDraft("DND sera"), raw = "ok") },
                clipboard = clipboard,
            )
            viewModel.onCopyRawResponse()
            assertEquals(emptyList(), clipboard.copied, "niente da copiare prima di un turno")

            viewModel.onInputChange("qualcosa")
            viewModel.onSend()
            advanceUntilIdle()
            viewModel.onCopyRawResponse()

            val text = clipboard.copied.single()
            assertFalse("Error code" in text, text)
            assertTrue(text.startsWith("App: Argus 0.3.6 (APK)\nProvider: hermes\n"), text)
        }

    @Test
    fun `a clipboard failure is reported and nothing is claimed as copied`() = runTest(dispatcher) {
        val viewModel = chatViewModel(
            FailingBrain { compiled(pendingDraft("DND sera"), raw = "ok") },
            clipboard = RecordingClipboard(succeeds = false),
        )
        viewModel.onInputChange("qualcosa")
        viewModel.onSend()
        advanceUntilIdle()

        viewModel.onCopyRawResponse()

        val notices = viewModel.state.value.items.filterIsInstance<ChatItem.SystemNotice>()
        assertTrue(notices.any { it.kind == NoticeKind.ERROR && "Impossibile copiare" in it.text }, notices.toString())
        assertFalse(notices.any { "copiata" in it.text })
    }

    // ------------------------------------------------------------------ 0.3.6 · B34c: lingua dinamica

    @Test
    fun `texts follow the phone language without recreating the view model`() = runTest(dispatcher) {
        var system = RenderLanguage.IT
        val source = RenderLanguageSource { system }
        val brain = QueuedBrain()
        val viewModel = chatViewModel(brain, languageSource = source)

        viewModel.onInputChange("uno")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst()
        viewModel.onCancelPending()
        assertTrue(viewModel.state.value.items.any { it is ChatItem.SystemNotice && it.text == "Richiesta annullata." })

        system = RenderLanguage.EN
        source.refresh()
        viewModel.onInputChange("due")
        viewModel.onSend()
        runCurrent()
        brain.removeFirst()
        viewModel.onCancelPending()
        assertTrue(viewModel.state.value.items.any { it is ChatItem.SystemNotice && it.text == "Request cancelled." })
    }

    @Test
    fun `draft cards are regenerated when the phone language changes`() = runTest(dispatcher) {
        var system = RenderLanguage.IT
        val source = RenderLanguageSource { system }
        val drafts = ViewModelDraftRepository()
        val viewModel = chatViewModel(QueuedBrain(), drafts = drafts, languageSource = source)
        drafts.seed("d1", "a1", pendingDraft("DND sera"))
        runCurrent()
        val italian = viewModel.state.value.items.filterIsInstance<ChatItem.DraftCard>().single()

        system = RenderLanguage.EN
        source.refresh()
        runCurrent()
        val english = viewModel.state.value.items.filterIsInstance<ChatItem.DraftCard>().single()

        assertEquals(italian.draftId, english.draftId)
        assertTrue(italian.rule != english.rule, "la card si rigenera nella nuova lingua")
    }

    private fun chatViewModel(
        brain: Brain,
        whitelist: ContactWhitelistStore = ViewModelWhitelistStore(),
        language: RenderLanguage = RenderLanguage.IT,
        installedApps: InstalledAppResolver = EmptyInstalledAppResolver,
        configuredBridge: ConfiguredBridgeBrain? = null,
        drafts: ViewModelDraftRepository = ViewModelDraftRepository(),
        automations: ViewModelAutomationStore = ViewModelAutomationStore(),
        languageSource: RenderLanguageSource = RenderLanguageSource { language },
        clipboard: RecordingClipboard = RecordingClipboard(),
        installInfo: AppInstallInfo = AppInstallInfo(versionName = "0.3.6", installerPackage = null, sdkInt = 34),
    ): ChatViewModel {
        val policy = FirePolicySnapshot(
            knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
            availableCapabilities = setOf(
                CapabilityIds.TRIGGER_TIME,
                ActionCapabilities.SET_DND,
            ),
            whitelistedConversationIds = emptySet(),
        )
        val approvals = ApprovalService(
            drafts,
            DraftValidator(policy.knownTools),
            ApprovalWhitelistProvider { whitelist.all().mapTo(hashSetOf()) { it.id } },
        )
        var nextId = 0
        val flow = ApprovalFlow(
            drafts = drafts,
            approvals = approvals,
            automations = automations,
            capabilities = FirePolicySnapshotProvider { policy },
            location = CurrentLocationProvider { null },
            registrar = ArmedAutomationRegistrar { true },
            nowMillis = { 1_000L },
            newDraftId = { DraftId("draft-${++nextId}") },
            newAutomationId = { AutomationId("automation-$nextId") },
        )
        val configuration = ViewModelBridgeConfiguration()
        return ChatViewModel(
            brain = brain,
            configuredBridge = configuredBridge ?: ConfiguredBridgeBrain(
                configuration = configuration,
                privacyAccepted = { true },
                elapsedRealtimeMillis = { 0L },
            ),
            capabilityProbe = object : CapabilityProbe {
                override suspend fun probe(currentState: DeviceState): CapabilityManifest =
                    TEST_MANIFEST
            },
            deviceState = DeviceStateSnapshotProvider { DeviceState() },
            approvalFlow = flow,
            installedApps = installedApps,
            drafts = drafts,
            whitelist = whitelist,
            automations = automations,
            languageSource = languageSource,
            clipboard = clipboard,
            installInfo = installInfo,
        )
    }

    private companion object {
        val TEST_MANIFEST = CapabilityManifest(
            deviceModel = "test",
            androidVersion = 16,
            androidApi = 36,
            shizukuAvailable = false,
            grantedPermissions = emptyList(),
            availableTools = emptyList(),
            unavailableTools = emptyMap(),
            whitelistedContacts = emptyList(),
        )
    }
}

private class CountingHealthTransport : AgentTransport {
    override val providerId: ProviderId = ProviderId.HERMES
    var healthCalls: Int = 0
        private set

    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult = error("compile must not run during a health-only test")

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = error("act must not run during a health-only test")

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        error("actV2 must not run during a health-only test")

    override suspend fun health(): TransportHealth {
        healthCalls += 1
        return object : TransportHealth {
            override val model: String = "test-model"
        }
    }
}

/** Brain che usa il parser come un provider diretto: risposte testuali con la riga @@META@@. */
private class ScriptedDirectTransport(vararg replies: String) : AgentTransport {
    private val queue = ArrayDeque(replies.toList())
    val messages = mutableListOf<String>()
    override val providerId: ProviderId = ProviderId.OPENAI

    override suspend fun compile(
        message: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        messages += message
        val parsed = CliBridgeParser().parseCompile(queue.removeFirst())
        // Come i transport diretti: nessun sentinel = draft_missing.
        return if (parsed.draft == null && parsed.metaError == null) parsed.copy(metaError = "draft_missing") else parsed
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = error("act non usato dai test chat")

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        error("actV2 non usato dai test chat")

    override suspend fun health(): TransportHealth = error("health non usato dai test chat")
}

private class FailingBrain(private val compile: suspend () -> CompileResult) : Brain {
    override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult =
        compile()

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = error("act non usato dai test chat")
}

private class QueuedBrain : Brain {
    data class Request(
        val prompt: String,
        val response: CompletableDeferred<CompileResult>,
    )

    private val requests = ArrayDeque<Request>()

    override suspend fun compile(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult {
        val request = Request(nl, CompletableDeferred())
        requests.addLast(request)
        return request.response.await()
    }

    override suspend fun act(
        context: dev.argus.engine.runtime.FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): dev.argus.engine.brain.ActResult = error("act non usato dai test chat")

    fun removeFirst(): Request = requests.removeFirst()
}

private class ViewModelWhitelistStore(vararg contacts: WhitelistedContact) : ContactWhitelistStore {
    private val values = MutableStateFlow(contacts.toList())

    override suspend fun all(): List<WhitelistedContact> = values.value
    override fun observeAll(): Flow<List<WhitelistedContact>> = values
    override suspend fun upsert(contact: WhitelistedContact) {
        values.value = values.value.filterNot { it.id == contact.id } + contact
    }
    override suspend fun remove(conversationId: String) {
        values.value = values.value.filterNot { it.id == conversationId }
    }
}

private class ViewModelBridgeConfiguration(
    private val selected: ProviderId = ProviderId.HERMES,
    private val baseUrl: String = "https://bridge.invalid",
) : ProviderConfigStore {
    override fun selectedProviderId(): ProviderId = selected
    override suspend fun selectProvider(id: ProviderId): Boolean = false
    override fun providerConfig(id: ProviderId): ProviderConfig =
        ProviderConfig(providerId = id, baseUrl = baseUrl, model = null)
    override suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
        reasoningEffort: dev.argus.brain.ReasoningEffort?,
    ): Boolean = false
    override suspend fun apiKey(id: ProviderId): String? = null
    override suspend fun hasApiKey(id: ProviderId): Boolean = false
    override suspend fun clearApiKey(id: ProviderId): Boolean = true
}

private class ViewModelDraftRepository : DraftRepository {
    private val values = MutableStateFlow<Map<DraftId, PendingDraft>>(emptyMap())

    override suspend fun create(newDraft: NewDraft): DraftWriteResult {
        if (newDraft.id in values.value) {
            return DraftWriteResult.Conflict(values.value[newDraft.id]?.revision)
        }
        // Come l'indice unico del DB: una sola bozza in attesa per regola.
        values.value.values.firstOrNull { it.automationId == newDraft.automationId }?.let {
            return DraftWriteResult.Conflict(it.revision)
        }
        val unsigned = PendingDraft(
            id = newDraft.id,
            automationId = newDraft.automationId,
            revision = 1,
            fingerprint = ApprovalFingerprint("0".repeat(64)),
            draft = newDraft.draft,
            createdBy = newDraft.createdBy,
            priority = newDraft.priority,
            schemaVersion = AUTOMATION_SCHEMA_VERSION_V1,
            createdAtMillis = newDraft.atMillis,
            updatedAtMillis = newDraft.atMillis,
            baseAutomationFingerprint = newDraft.expectedAutomationFingerprint,
        )
        val snapshot = unsigned.copy(
            fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()),
        )
        values.value += snapshot.id to snapshot
        return DraftWriteResult.Saved(snapshot)
    }

    override suspend fun revise(
        id: DraftId,
        expectedRevision: Long,
        draft: AutomationDraft,
        priority: Int,
        atMillis: Long,
    ): DraftWriteResult {
        val current = values.value[id] ?: return DraftWriteResult.Conflict(null)
        if (current.revision != expectedRevision) return DraftWriteResult.Conflict(current.revision)
        val unsigned = current.copy(
            revision = current.revision + 1,
            draft = draft,
            priority = priority,
            updatedAtMillis = atMillis,
        )
        val snapshot = unsigned.copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))
        values.value += id to snapshot
        return DraftWriteResult.Saved(snapshot)
    }

    override suspend fun get(id: DraftId): PendingDraft? = values.value[id]
    override fun observeAll(): Flow<List<PendingDraft>> = values.map { it.values.toList() }
    override suspend fun delete(id: DraftId, expectedRevision: Long): DraftDeleteResult =
        DraftDeleteResult.Missing
    override suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftArmResult = DraftArmResult.Missing
}

private class ViewModelAutomationStore : AutomationStore {
    private val values = MutableStateFlow<Map<AutomationId, Automation>>(emptyMap())

    fun seed(automation: Automation) {
        values.value += automation.id to automation
    }

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = values.map { it.values.toList() }
    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) {
        values.value -= id
    }
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun enableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}

/** Copiatore appunti di prova: registra i testi scritti e può simulare un errore di scrittura. */
private class RecordingClipboard(private val succeeds: Boolean = true) : ClipboardCopier {
    val copied = mutableListOf<String>()

    override fun copy(event: dev.argus.engine.runtime.TriggerEvent, extractionRegex: String?): ActionResult =
        error("non usato dai test chat")

    override fun copyLiteral(text: String): ActionResult {
        if (!succeeds) return ActionResult.Failure("clipboard_write_failed")
        copied += text
        return ActionResult.Success
    }
}
