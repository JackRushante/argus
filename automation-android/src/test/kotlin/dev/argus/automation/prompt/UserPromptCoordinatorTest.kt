package dev.argus.automation.prompt

import dev.argus.automation.DeferredReplyCipher
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.ConfirmationClosed
import dev.argus.engine.runtime.ConfirmationRequest
import dev.argus.engine.runtime.ConfirmationRequestResult
import dev.argus.engine.runtime.ConfirmedFire
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ProgramQuestion
import dev.argus.engine.runtime.QuestionContext
import dev.argus.engine.runtime.QuestionOutcome
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.8: «chiedimi prima» e le domande `ask`. Il pulsante vale solo col token della domanda, una volta,
 * prima della scadenza; la domanda è cifrata a riposo e una conferma sopravvive al riavvio.
 */
class UserPromptCoordinatorTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = DeferredReplyCipher { key }
    private val blobs = MapBlobs()
    private var now = 1_000L
    private val store = PendingPromptStore(blobs, cipher)
    private val surface = FakeSurface()
    private val runs = FakeRuns()
    private val fingerprint = ApprovalFingerprint("c".repeat(64))
    private val message = TriggerEvent.NotificationPosted("com.whatsapp", "jid", "Anna", "Anna", "il codice è 4242", false, "k")

    private fun coordinator(pause: suspend (Long) -> Unit = { awaitCancellation() }) =
        UserPromptCoordinator(store, surface, runs, pause, nowMillis = { now })

    private fun request(id: String = "r1", timeoutMs: Long = 60_000) = ConfirmationRequest(
        automationId = AutomationId(id),
        ruleName = "Rispondi ad Anna",
        approvalFingerprint = fingerprint,
        envelope = TriggerEnvelope(TriggerEventId("n:1"), message),
        executionId = ExecutionId("x1"),
        timeoutMs = timeoutMs,
        requestedAtMillis = now,
    )

    @Test
    fun `a confirmation is stored encrypted, shown and scheduled to expire`() = runTest {
        val result = coordinator().request(request())

        assertEquals(ConfirmationRequestResult.REQUESTED, result)
        val prompt = surface.shown.single()
        assertEquals(PromptKind.CONFIRM, prompt.kind)
        assertEquals("Rispondi ad Anna", prompt.ruleName)
        assertEquals(61_000L, surface.expiries.getValue(prompt.id))
        assertTrue(blobs.values.values.none { "4242" in it || "Anna" in it || prompt.token in it })
    }

    @Test
    fun `only one confirmation per rule is pending`() = runTest {
        val coordinator = coordinator()
        coordinator.request(request())
        assertEquals(ConfirmationRequestResult.ALREADY_PENDING, coordinator.request(request()))
        assertEquals(ConfirmationRequestResult.REQUESTED, coordinator.request(request(id = "r2")))
    }

    @Test
    fun `a confirmation that cannot be shown is not kept`() = runTest {
        surface.canShow = false
        assertEquals(ConfirmationRequestResult.UNAVAILABLE, coordinator().request(request()))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `run needs the prompt token, works once and replays the original event`() = runTest {
        val coordinator = coordinator()
        coordinator.request(request())
        val prompt = surface.shown.single()

        assertFalse(coordinator.handle(prompt.id, "0".repeat(64), PromptAnswer.Run))
        assertFalse(coordinator.handle(prompt.id, null, PromptAnswer.Run))
        assertFalse(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Choice(0)))
        assertTrue(runs.confirmed.isEmpty())

        assertTrue(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Run))
        val confirmed = runs.confirmed.single()
        assertEquals(prompt.id, confirmed.confirmationId)
        assertEquals(AutomationId("r1"), confirmed.automationId)
        assertEquals(fingerprint, confirmed.approvalFingerprint)
        assertEquals(message, confirmed.envelope.event)
        assertEquals(listOf(prompt.id), surface.dismissed)

        assertFalse(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Run))
        assertEquals(1, runs.confirmed.size)
    }

    @Test
    fun `cancel closes the confirmation with a closed code`() = runTest {
        val coordinator = coordinator()
        coordinator.request(request())
        val prompt = surface.shown.single()

        assertTrue(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Cancel))
        assertEquals(listOf(AutomationId("r1") to ConfirmationClosed.CANCELLED), runs.closed)
        assertTrue(runs.confirmed.isEmpty())
    }

    @Test
    fun `an expired confirmation cannot run and is closed once by the sweep`() = runTest {
        val coordinator = coordinator()
        coordinator.request(request())
        val prompt = surface.shown.single()
        now += 60_000

        assertFalse(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Run))
        coordinator.sweep(repost = false)
        coordinator.sweep(repost = false)
        assertEquals(listOf(AutomationId("r1") to ConfirmationClosed.EXPIRED), runs.closed)
        assertTrue(runs.confirmed.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `after a restart a valid confirmation is shown again and still runs`() = runTest {
        coordinator().request(request())
        val prompt = surface.shown.single()

        // Processo nuovo: stesso archivio cifrato, nuovo coordinatore.
        val restarted = UserPromptCoordinator(PendingPromptStore(blobs, cipher), surface, runs, { awaitCancellation() }) { now }
        now += 30_000
        restarted.sweep(repost = true)
        assertEquals(2, surface.shown.size)
        assertTrue(restarted.handle(prompt.id, prompt.token, PromptAnswer.Run))
        assertEquals(message, runs.confirmed.single().envelope.event)
    }

    private fun question(path: String = "1") = ProgramQuestion(ActionPath(path), "Esco?", listOf("Sì", "No"), 60_000)

    private val questionContext = QuestionContext(
        AutomationId("r1"),
        "Uscita",
        fingerprint,
        ExecutionId("x1"),
        TriggerEventId("e1"),
    )

    @Test
    fun `a tapped choice answers the waiting question with the approved label`() = runTest {
        val coordinator = coordinator()
        val outcome = async { coordinator.ask(questionContext, question()) }
        while (surface.shown.isEmpty()) yield()
        val prompt = surface.shown.single()
        assertEquals("Esco?", prompt.prompt)

        assertFalse(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Choice(2)))
        assertFalse(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Run))
        assertTrue(coordinator.handle(prompt.id, prompt.token, PromptAnswer.Choice(1)))

        assertEquals(QuestionOutcome.Answered("No"), outcome.await())
        assertTrue(store.all().isEmpty())
        assertTrue(prompt.id in surface.dismissed)
    }

    @Test
    fun `no answer before the wait ends is a timeout`() = runTest {
        val outcome = coordinator(pause = {}).ask(questionContext, question())

        assertEquals(QuestionOutcome.TimedOut, outcome)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a question that cannot be shown is unavailable`() = runTest {
        surface.canShow = false
        val outcome = coordinator().ask(questionContext, question())
        assertIs<QuestionOutcome.Unavailable>(outcome)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a question left by a dead process is removed at start`() = runTest {
        val created = store.create(PromptKind.ASK, "r1", "Uscita", fingerprint.value, "x1", now, now + 60_000, prompt = "Esco?", choices = listOf("a", "b"))
        val orphan = assertIs<PendingPromptStore.Creation.Created>(created).prompt

        coordinator().sweep(repost = true)

        assertNull(store.get(orphan.id))
        assertTrue(orphan.id in surface.dismissed)
        assertTrue(runs.closed.isEmpty())
    }

    @Test
    fun `a tampered blob is dropped, never half read`() = runTest {
        coordinator().request(request())
        val key = blobs.values.keys.single()
        blobs.values[key] = blobs.values.getValue(key).reversed()
        assertTrue(store.all().isEmpty())
        assertTrue(blobs.values.isEmpty())
    }

    private class MapBlobs : PromptBlobs {
        val values = linkedMapOf<String, String>()
        override fun all(): Map<String, String> = values.toMap()
        override fun put(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FakeSurface : UserPromptSurface {
        var canShow = true
        val shown = mutableListOf<PendingPrompt>()
        val dismissed = mutableListOf<String>()
        val expiries = mutableMapOf<String, Long>()

        override fun show(prompt: PendingPrompt, nowMillis: Long): Boolean {
            if (!canShow) return false
            shown += prompt
            return true
        }

        override fun dismiss(promptId: String) {
            dismissed += promptId
        }

        override fun scheduleExpiry(promptId: String, atMillis: Long) {
            expiries[promptId] = atMillis
        }

        override fun cancelExpiry(promptId: String) {
            expiries.remove(promptId)
        }
    }

    private class FakeRuns : ConfirmedRuns {
        val confirmed = mutableListOf<ConfirmedFire>()
        val closed = mutableListOf<Pair<AutomationId, ConfirmationClosed>>()
        val done = CompletableDeferred<Unit>()

        override suspend fun run(confirmed: ConfirmedFire) {
            this.confirmed += confirmed
        }

        override suspend fun closed(automationId: AutomationId, reason: ConfirmationClosed) {
            closed += automationId to reason
        }
    }
}
