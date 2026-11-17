package dev.argus.automation.directboot

import android.util.Log
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ReconcileReport
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.TimeAlarmStateStore
import dev.argus.engine.directboot.BeforeUnlockCodes
import dev.argus.engine.directboot.DirectBootActionRecord
import dev.argus.engine.directboot.DirectBootCopy
import dev.argus.engine.directboot.DirectBootCopyEntry
import dev.argus.engine.directboot.DirectBootImport
import dev.argus.engine.directboot.DirectBootImportItem
import dev.argus.engine.directboot.DirectBootPolicy
import dev.argus.engine.directboot.DirectBootRecords
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionIdFactory
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.StableExecutionIdFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * 0.8, rules before the first unlock: the normal runtime's side (main process, after unlock). It writes the copy the
 * locked path reads, and when it starts it takes over from the locked path and imports what it did into the Log.
 */

/**
 * Keeps the device-encrypted copy equal to the opted-in rules that qualify and are armed, rewriting it whenever a rule
 * or an option changes. An option bound to another revision (the rule was re-approved) or to a rule that no longer
 * qualifies switches itself off, with a note in the Log; a deleted rule loses it silently. A disarmed rule keeps the
 * option but leaves the copy until it is armed again.
 */
class BeforeUnlockCopyWriter(
    private val store: AutomationStore,
    private val optIns: BeforeUnlockOptInStore,
    private val storage: DirectBootStorage,
    private val audit: AuditSink,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var written = false
    private var lastWritten: String? = null

    /** Main process only, once: the copy then follows the rules and the options for the life of the process. */
    fun start() {
        scope.launch {
            combine(store.observeAll(), optIns.optIns) { rules, opted -> rules to opted }
                .catch { error -> if (error is CancellationException) throw error }
                .collect { (rules, opted) ->
                    try {
                        sync(rules, opted)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "copy not written: ${error::class.java.simpleName}")
                    }
                }
        }
    }

    internal suspend fun sync(rules: List<Automation>, opted: Map<AutomationId, ApprovalFingerprint>) = mutex.withLock {
        val byId = rules.associateBy { it.id }
        val entries = mutableListOf<DirectBootCopyEntry>()
        opted.entries.sortedBy { it.key.value }.forEach { (id, fingerprint) ->
            val rule = byId[id]
            when {
                rule == null -> optIns.remove(id)
                rule.approvalFingerprint != fingerprint -> switchOff(id, BeforeUnlockCodes.OFF_RULE_CHANGED)
                !DirectBootPolicy.qualifies(rule) -> switchOff(id, BeforeUnlockCodes.OFF_NOT_QUALIFYING)
                entries.size < DirectBootCopy.MAX_RULES -> DirectBootCopy.entryFor(rule)?.let(entries::add)
            }
        }
        val text = if (entries.isEmpty()) null else DirectBootCopy.encode(entries)
        if (written && text == lastWritten) return@withLock
        withContext(Dispatchers.IO) { storage.locked { writeCopy(text) } }
        written = true
        lastWritten = text
    }

    private suspend fun switchOff(id: AutomationId, code: String) {
        optIns.remove(id)
        try {
            audit.record(AuditEvent(id, AuditKind.BEFORE_UNLOCK_OFF, now(), detail = code))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The note must not keep the option on.
        }
    }
}

/**
 * Takes over from the locked path, once per process, before the time alarms are reconciled: marks this boot as handed
 * over (the locked path stands down under the same file lock, so nothing can run in between), cancels its alarm, then
 * imports its records into the Log with the same claim, journal and audit the engine writes. An occurrence it ran gets
 * the event id the normal runtime gives the same time alarm, so the journal deduplicates it; a due alarm kept from
 * before the restart and already handled by the locked path is dropped, so it is not recovered after unlock.
 */
class BeforeUnlockHandover(
    private val storage: DirectBootStorage,
    private val store: AutomationStore,
    private val journal: ExecutionJournal,
    private val audit: AuditSink,
    private val timeAlarms: TimeAlarmStateStore,
    private val alarms: LockedAlarms,
    private val bootCount: () -> Int?,
    private val executionIds: ExecutionIdFactory = StableExecutionIdFactory,
) {
    private val mutex = Mutex()

    @Volatile
    private var imported = false

    suspend fun ensureImported() {
        if (imported) return
        mutex.withLock {
            if (imported) return
            try {
                withContext(Dispatchers.IO) { importAll() }
                imported = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The queue stays: the next start (or reconcile) retries; claims make the retry idempotent.
                Log.w(TAG, "import failed: ${error::class.java.simpleName}")
            }
        }
    }

    private suspend fun importAll() {
        val records = storage.locked {
            bootCount()?.let { boot -> markHandedOver(boot) }
            records()
        }
        alarms.cancel()
        val items = DirectBootImport.plan(records)
        items.forEach { item -> importItem(item) }
        DirectBootImport.handledUpTo(items).forEach { (key, handledEventAt) ->
            val id = AutomationId(key.first)
            val persisted = timeAlarms.get(id) ?: return@forEach
            if (persisted.approvalFingerprint.value == key.second && persisted.eventAtMillis <= handledEventAt) {
                timeAlarms.delete(id)
            }
        }
        storage.locked {
            clearRecords()
            writeSession(null)
        }
    }

    private suspend fun importItem(item: DirectBootImportItem) {
        val id = AutomationId(item.id)
        // A rule deleted since: nothing to attach the record to.
        if (store.get(id) == null) return
        when (item) {
            is DirectBootImportItem.Fired -> importFired(id, item)
            is DirectBootImportItem.NotMet -> audit.record(
                AuditEvent(
                    id,
                    AuditKind.CONDITIONS_NOT_MET,
                    item.at,
                    detail = item.code.orEmpty(),
                    eventId = DirectBootRecords.eventId(item.fingerprint, item.eventAt),
                ),
            )
            is DirectBootImportItem.Rejected -> audit.record(
                AuditEvent(id, AuditKind.BLOCKED_POLICY, item.at, detail = item.code),
            )
        }
    }

    private suspend fun importFired(id: AutomationId, item: DirectBootImportItem.Fired) {
        val eventId = DirectBootRecords.eventId(item.fingerprint, item.eventAt)
        val executionId = executionIds.create(id, eventId)
        val claim = store.claimFire(
            FireClaimRequest(id, eventId, executionId, item.at, cooldownMs = 0, phase = SpanPhase.START),
        )
        when (claim) {
            is FireClaimResult.Duplicate -> return
            FireClaimResult.Claimed -> {
                val results = item.results
                val at = item.completedAt ?: item.at
                results?.forEachIndexed { index, result -> journal.recordAction(entry(executionId, index, result, at)) }
                journal.finish(completion(executionId, results, at))
                audit.record(AuditEvent(id, AuditKind.FIRED, item.at, BeforeUnlockCodes.FIRED, eventId, executionId))
            }
            // The actions did run: the Log says so even if the rule is not eligible any more.
            is FireClaimResult.Cooldown, FireClaimResult.NotEligible ->
                audit.record(AuditEvent(id, AuditKind.FIRED, item.at, BeforeUnlockCodes.FIRED, eventId))
        }
    }

    private fun entry(
        executionId: dev.argus.engine.runtime.ExecutionId,
        index: Int,
        result: DirectBootActionRecord,
        at: Long,
    ) = ActionJournalEntry(
        executionId = executionId,
        actionIndex = index,
        actionType = result.type,
        outcome = if (result.ok) ActionJournalOutcome.SUCCEEDED else ActionJournalOutcome.FAILED,
        atMillis = at,
        errorCode = result.code.takeIf { !result.ok },
    )

    private fun completion(
        executionId: dev.argus.engine.runtime.ExecutionId,
        results: List<DirectBootActionRecord>?,
        at: Long,
    ): ExecutionCompletion {
        val succeeded = results?.count { it.ok } ?: 0
        val failed = results?.count { !it.ok } ?: 0
        val status = when {
            // Claimed, but the results never arrived: the locked process died while acting.
            results == null -> ExecutionStatus.INTERRUPTED
            failed == 0 -> ExecutionStatus.SUCCEEDED
            succeeded == 0 -> ExecutionStatus.FAILED
            else -> ExecutionStatus.PARTIAL
        }
        return ExecutionCompletion(executionId, status, at, succeeded, failed, submittedCount = 0)
    }
}

/** The time alarms of the normal runtime, used only after the locked path's records are in the Log. */
class BeforeUnlockAwareTimeAlarmRuntime(
    private val delegate: TimeAlarmRuntime,
    private val handover: BeforeUnlockHandover,
) : TimeAlarmRuntime {
    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult {
        handover.ensureImported()
        return delegate.onAlarm(automationId, approvalFingerprint, eventAtMillis)
    }

    override suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
        phase: SpanPhase,
    ): AlarmDeliveryResult {
        handover.ensureImported()
        return delegate.onAlarm(automationId, approvalFingerprint, eventAtMillis, phase)
    }

    override suspend fun reconcile(reason: ReconcileReason): ReconcileReport {
        handover.ensureImported()
        return delegate.reconcile(reason)
    }
}

private const val TAG = "ArgusBeforeUnlock"
