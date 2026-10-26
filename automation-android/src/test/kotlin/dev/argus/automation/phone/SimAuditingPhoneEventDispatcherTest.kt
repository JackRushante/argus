package dev.argus.automation.phone

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimAuditingPhoneEventDispatcherTest {
    private val parser = PhoneEventParser()
    private val delivered = mutableListOf<TriggerEnvelope>()
    private val audit = RecordingAudit()
    private val store = RulesStore()

    private fun dispatcher() = SimAuditingPhoneEventDispatcher(
        delegate = { delivered += it },
        store = store,
        audit = audit,
        nowMillis = { 7_000L },
    )

    private fun armed(id: String, trigger: Trigger) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = trigger,
        actions = listOf(Action.SetWifi(false)),
        approvalFingerprint = ApprovalFingerprint("f".repeat(64)),
    )

    @Test
    fun `an event with an unknown slot leaves a reason for each rule filtered by SIM that would have fired`() =
        runTest {
            store.rules += (armed("sim2", Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 2)))
            store.rules += (armed("sim1-other-text", Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "zzz", sim = 1)))
            store.rules += (armed("no-filter", Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)))
            store.rules += (armed("call", Trigger.PhoneState(PhoneEvent.INCOMING_CALL, sim = 1)))
            val envelope = parser.parse(PhoneEvent.SMS_RECEIVED, "+39", "ciao", 1_000L, sim = null)!!

            dispatcher().dispatch(envelope)

            assertEquals(listOf(envelope), delivered)
            // Solo la regola che sarebbe scattata se lo slot fosse noto: il filtro SIM è la causa.
            assertEquals(
                listOf(
                    AuditEvent(
                        AutomationId("sim2"),
                        AuditKind.BLOCKED_POLICY,
                        7_000L,
                        detail = SIM_UNKNOWN_REASON,
                        eventId = envelope.id,
                    ),
                ),
                audit.events,
            )
        }

    @Test
    fun `an event with a known slot is passed on without any audit`() = runTest {
        store.rules += (armed("sim2", Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 2)))
        val envelope = parser.parse(PhoneEvent.SMS_RECEIVED, "+39", "ciao", 1_000L, sim = 1)!!

        dispatcher().dispatch(envelope)

        assertEquals(listOf(envelope), delivered)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun `a failing audit never stops the event`() = runTest {
        store.rules += (armed("sim2", Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 2)))
        audit.failing = true
        val envelope = parser.parse(PhoneEvent.SMS_RECEIVED, "+39", "ciao", 1_000L)!!

        dispatcher().dispatch(envelope)

        assertEquals(listOf(envelope), delivered)
    }

    private class RecordingAudit : AuditSink {
        val events = mutableListOf<AuditEvent>()
        var failing = false
        override suspend fun record(e: AuditEvent) {
            if (failing) error("audit down")
            events += e
        }
    }

    private class RulesStore : AutomationStore {
        var rules: List<Automation> = emptyList()
        override suspend fun get(id: AutomationId): Automation? = rules.singleOrNull { it.id == id }
        override suspend fun all(): List<Automation> = rules
        override fun observeAll(): Flow<List<Automation>> = flowOf(rules)
        override suspend fun armed(): List<Automation> = rules.filter {
            it.status == AutomationStatus.ARMED && it.enabled
        }
        override suspend fun delete(id: AutomationId) = Unit
        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun markNeedsReview(id: AutomationId) = Unit
        override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.NotEligible
        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = null
    }
}
