package dev.argus.automation.directboot

import dev.argus.automation.CoordinatorTimeAlarmRuntime
import dev.argus.automation.ReconcileReason
import dev.argus.automation.TimeAlarmCoordinator
import dev.argus.automation.TimeEventDispatcher
import dev.argus.engine.directboot.BeforeUnlockCodes
import dev.argus.engine.directboot.DirectBootActionRecord
import dev.argus.engine.directboot.DirectBootCopy
import dev.argus.engine.directboot.DirectBootRecord
import dev.argus.engine.directboot.DirectBootRecordKind
import dev.argus.engine.directboot.DirectBootRecords
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.StableExecutionIdFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** After unlock: the normal runtime takes over, imports the Log and never runs an occurrence a second time. */
class BeforeUnlockHandoverTest {
    @TempDir
    lateinit var dir: File

    private val seven = Instant.parse("2026-11-16T07:00:00Z").toEpochMilli()
    private val rule = qualifyingRule("a")
    private val fp = rule.approvalFingerprint!!.value
    private val storage by lazy { DirectBootStorage(dir) }
    private val store = ClaimingStore(rule)
    private val journal = RecordingJournal()
    private val audit = RecordingAudit()
    private val alarms = RecordingAlarms()

    private fun handover(state: MemoryTimeAlarmState) =
        BeforeUnlockHandover(storage, store, journal, audit, state, alarms, bootCount = { 3 })

    private suspend fun queue(vararg records: DirectBootRecord) = storage.locked { records.forEach { append(it) } }

    private fun ranAtSeven(results: List<DirectBootActionRecord>? = listOf(DirectBootActionRecord("set_dnd", ok = true))) =
        listOfNotNull(
            DirectBootRecord(DirectBootRecordKind.CLAIMED, "a", fp, seven, seven + 1_000),
            results?.let { DirectBootRecord(DirectBootRecordKind.DONE, "a", fp, seven, seven + 2_000, results = it) },
        ).toTypedArray()

    @Test
    fun `a fire before unlock is imported with the engine's claim, journal and audit`() = runTest {
        queue(*ranAtSeven())
        handover(MemoryTimeAlarmState()).ensureImported()

        val eventId = DirectBootRecords.eventId(fp, seven)
        val executionId = StableExecutionIdFactory.create(rule.id, eventId)
        assertEquals(mapOf((rule.id to eventId) to executionId), store.claims)
        assertEquals(seven + 1_000, store.lastFired[rule.id])
        assertEquals(listOf("set_dnd" to ActionJournalOutcome.SUCCEEDED), journal.actions.map { it.actionType to it.outcome })
        assertEquals(ExecutionStatus.SUCCEEDED, journal.finished.single().status)
        val fired = audit.events.single()
        assertEquals(AuditKind.FIRED, fired.kind)
        assertEquals(BeforeUnlockCodes.FIRED, fired.detail)
        assertEquals(executionId, fired.executionId)
        // The locked path stands down for this boot and its queue is emptied.
        storage.locked {
            assertEquals(3, handedOverBoot())
            assertEquals(emptyList(), records())
        }
        assertEquals(1, alarms.cancelled)
    }

    @Test
    fun `the normal runtime does not run again an occurrence the locked path ran`() = runTest {
        // Before the restart the normal runtime had planned 07:00; the phone restarted, the locked path ran it.
        val state = MemoryTimeAlarmState(dueRecord(rule, seven))
        queue(*ranAtSeven())
        val dispatched = mutableListOf<String>()
        val backend = MemoryTimeAlarmBackend()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = backend,
            dispatcher = TimeEventDispatcher { envelope -> dispatched += envelope.id.value },
            now = { Instant.ofEpochMilli(seven + 30 * 60_000) },
        )
        val runtime = BeforeUnlockAwareTimeAlarmRuntime(CoordinatorTimeAlarmRuntime(coordinator), handover(state))

        // Unlock at 07:30: BOOT_COMPLETED reconciles the time alarms.
        runtime.reconcile(ReconcileReason.BOOT)

        assertEquals(emptyList(), dispatched)
        assertEquals(Instant.parse("2026-11-17T07:00:00Z").toEpochMilli(), backend.scheduled.single().eventAtMillis)
        // And had the due record been recovered anyway, the engine's claim would refuse it as a duplicate.
        val again = store.claimFire(
            FireClaimRequest(rule.id, DirectBootRecords.eventId(fp, seven), StableExecutionIdFactory.create(rule.id, DirectBootRecords.eventId(fp, seven)), seven + 1, 0),
        )
        assertIs<FireClaimResult.Duplicate>(again)
    }

    @Test
    fun `a due alarm the locked path did not handle is still recovered after unlock`() = runTest {
        // The locked path ran 07:00 of another rule only: rule "a" keeps its catch-up.
        val other = qualifyingRule("b")
        store.put(other)
        val state = MemoryTimeAlarmState(dueRecord(rule, seven))
        val otherFp = other.approvalFingerprint!!.value
        queue(
            DirectBootRecord(DirectBootRecordKind.CLAIMED, "b", otherFp, seven, seven + 1_000),
            DirectBootRecord(DirectBootRecordKind.DONE, "b", otherFp, seven, seven + 1_500, results = listOf(DirectBootActionRecord("set_dnd", ok = true))),
        )
        val dispatched = mutableListOf<String>()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = MemoryTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { envelope -> dispatched += envelope.id.value },
            now = { Instant.ofEpochMilli(seven + 30 * 60_000) },
        )
        BeforeUnlockAwareTimeAlarmRuntime(CoordinatorTimeAlarmRuntime(coordinator), handover(state))
            .reconcile(ReconcileReason.BOOT)
        assertEquals(listOf("time:$fp:$seven"), dispatched)
    }

    @Test
    fun `importing twice logs once`() = runTest {
        queue(*ranAtSeven())
        handover(MemoryTimeAlarmState()).ensureImported()
        // The same records again (a retry after a failure, a copy restored): the claim deduplicates.
        queue(*ranAtSeven())
        handover(MemoryTimeAlarmState()).ensureImported()
        assertEquals(1, audit.events.size)
        assertEquals(1, journal.finished.size)
    }

    @Test
    fun `a claim whose results never arrived is logged as interrupted`() = runTest {
        queue(*ranAtSeven(results = null))
        handover(MemoryTimeAlarmState()).ensureImported()
        assertEquals(ExecutionStatus.INTERRUPTED, journal.finished.single().status)
        assertEquals(emptyList(), journal.actions)
    }

    @Test
    fun `failed actions, refusals and unmet conditions reach the Log with closed codes`() = runTest {
        queue(
            *ranAtSeven(
                listOf(
                    DirectBootActionRecord("set_dnd", ok = false, code = "dnd_policy_unavailable"),
                    DirectBootActionRecord("vibrate", ok = true),
                ),
            ),
            DirectBootRecord(DirectBootRecordKind.NOT_MET, "a", fp, seven + 3_600_000, seven + 3_600_500),
            DirectBootRecord(DirectBootRecordKind.REJECTED, "a", at = seven + 10, code = DirectBootCopy.CORRUPT),
        )
        handover(MemoryTimeAlarmState()).ensureImported()
        assertEquals(ExecutionStatus.PARTIAL, journal.finished.single().status)
        assertEquals("dnd_policy_unavailable", journal.actions.first().errorCode)
        // In time order: the refusal at locked boot, the fire, the occurrence whose conditions did not hold.
        assertEquals(
            listOf(AuditKind.BLOCKED_POLICY, AuditKind.FIRED, AuditKind.CONDITIONS_NOT_MET),
            audit.events.map { it.kind },
        )
        assertEquals(DirectBootCopy.CORRUPT, audit.events[0].detail)
    }

    @Test
    fun `records of a rule deleted since are dropped`() = runTest {
        store.remove(rule.id)
        queue(*ranAtSeven())
        handover(MemoryTimeAlarmState()).ensureImported()
        assertEquals(emptyList(), audit.events)
        assertEquals(emptyMap(), store.claims)
        storage.locked { assertEquals(emptyList(), records()) }
    }

    // --- the copy writer -------------------------------------------------------------------------

    private class MemoryOptIns(initial: Map<AutomationId, ApprovalFingerprint> = emptyMap()) : BeforeUnlockOptInStore {
        private val state = MutableStateFlow(initial)
        override val optIns: StateFlow<Map<AutomationId, ApprovalFingerprint>> = state
        override fun set(id: AutomationId, fingerprint: ApprovalFingerprint) {
            state.value = state.value + (id to fingerprint)
        }
        override fun remove(id: AutomationId) {
            state.value = state.value - id
        }
    }

    @Test
    fun `the copy holds exactly the opted-in rules that are armed and qualify`() = runTest {
        val notOpted = qualifyingRule("n")
        val optIns = MemoryOptIns(mapOf(rule.id to rule.approvalFingerprint!!))
        val writer = BeforeUnlockCopyWriter(store, optIns, storage, audit, { 1L }, this)

        writer.sync(listOf(rule, notOpted), optIns.optIns.value)
        val copy = DirectBootCopy.decode(
            requireNotNull(storage.locked { readCopy() }),
            dev.argus.engine.safety.DraftValidator(emptySet()),
        )
        assertEquals(listOf(rule.id), copy.rules.map { it.id })
        assertTrue("Morning" !in requireNotNull(storage.locked { readCopy() }))

        // Disabled: out of the copy, the option stays for when it is armed again.
        val disabled = rule.copy(status = AutomationStatus.DISABLED, enabled = false)
        writer.sync(listOf(disabled), optIns.optIns.value)
        assertNull(storage.locked { readCopy() })
        assertEquals(setOf(rule.id), optIns.optIns.value.keys)
        assertEquals(emptyList(), audit.events)
    }

    @Test
    fun `a re-approved rule switches the option off with a note and leaves the copy`() = runTest {
        val optIns = MemoryOptIns(mapOf(rule.id to rule.approvalFingerprint!!))
        val writer = BeforeUnlockCopyWriter(store, optIns, storage, audit, { 1L }, this)
        writer.sync(listOf(rule), optIns.optIns.value)

        val revised = qualifyingRule("a", actions = listOf(Action.Vibrate(100)))
        writer.sync(listOf(revised), optIns.optIns.value)

        assertNull(storage.locked { readCopy() })
        assertEquals(emptyMap(), optIns.optIns.value)
        val note = audit.events.single()
        assertEquals(AuditKind.BEFORE_UNLOCK_OFF, note.kind)
        assertEquals(BeforeUnlockCodes.OFF_RULE_CHANGED, note.detail)
    }

    @Test
    fun `a deleted rule loses the option silently`() = runTest {
        val optIns = MemoryOptIns(mapOf(rule.id to rule.approvalFingerprint!!))
        val writer = BeforeUnlockCopyWriter(store, optIns, storage, audit, { 1L }, this)
        writer.sync(emptyList(), optIns.optIns.value)
        assertEquals(emptyMap(), optIns.optIns.value)
        assertEquals(emptyList(), audit.events)
        assertNull(storage.locked { readCopy() })
    }
}
