package dev.argus.automation.directboot

import dev.argus.automation.CoordinatorTimeAlarmRuntime
import dev.argus.automation.ReconcileReason
import dev.argus.automation.TimeAlarmCoordinator
import dev.argus.automation.TimeEventDispatcher
import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.engine.directboot.DirectBootActionRunner
import dev.argus.engine.directboot.DirectBootCopy
import dev.argus.engine.directboot.DirectBootRunner
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The takeover after unlock with a rule that runs every minute. Whatever the order of the unlock, the locked alarm
 * and the start of the normal runtime, each occurrence runs exactly once: in the locked path if it claimed it,
 * otherwise in the normal runtime with the same event id.
 */
class BeforeUnlockTakeoverTest {
    @TempDir
    lateinit var dir: File

    private fun at(time: String): Instant = Instant.parse("2026-11-16T$time:00Z")
    private val rule = qualifyingRule("a", cron = "* * * * *")
    private val fp = rule.approvalFingerprint!!.value
    private val storage by lazy { DirectBootStorage(dir) }
    private val store = ClaimingStore(rule)
    private val journal = RecordingJournal()
    private val audit = RecordingAudit()
    private val surface = RecordingSurface()
    private val lockedAlarms = RecordingAlarms()
    private val backend = MemoryTimeAlarmBackend()
    private val dispatched = mutableListOf<String>()

    // Before the restart (10:15:50) the normal runtime had saved the 10:16 occurrence.
    private val state = MemoryTimeAlarmState(dueRecord(rule, at("10:16").toEpochMilli()))

    private var now = at("10:17").minusSeconds(30)
    private var locked = true

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun session(actions: DirectBootActionRunner = LockedActionRunner(AndroidBaseActionExecutor(surface))) =
        LockedSession(
            storage = storage,
            validator = DraftValidator(knownTools = emptySet(), clock = clock),
            runner = DirectBootRunner(ConditionEvaluator(clock), actions) { now.toEpochMilli() },
            alarms = lockedAlarms,
            isUserLocked = { locked },
            bootCount = { 3 },
            now = { now },
        )

    /** The main process starting: the handover, then the BOOT_COMPLETED reconcile of the time alarms. */
    private suspend fun mainRuntimeStarts() {
        val handover = BeforeUnlockHandover(storage, store, journal, audit, state, lockedAlarms, bootCount = { 3 }) { now.toEpochMilli() }
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = backend,
            dispatcher = TimeEventDispatcher { envelope -> dispatched += envelope.id.value },
            now = { now },
        )
        BeforeUnlockAwareTimeAlarmRuntime(CoordinatorTimeAlarmRuntime(coordinator), handover).reconcile(ReconcileReason.BOOT)
    }

    private fun id(time: String) = "time:$fp:${at(time).toEpochMilli()}"

    private suspend fun bootAndRunUntil1020() {
        storage.locked { writeCopy(DirectBootCopy.encode(listOf(requireNotNull(DirectBootCopy.entryFor(rule))))) }
        session().onLockedBoot()
        for (minute in 17..20) {
            now = at("10:$minute").plusMillis(500)
            session().onAlarm()
        }
        assertEquals(4, surface.calls.size)
    }

    @Test
    fun `an occurrence the locked path stood down for runs once in the normal runtime`() = runTest {
        bootAndRunUntil1020()
        // Unlock at 10:20:55; the locked alarm of 10:21 finds the user unlocked and stands down.
        locked = false
        now = at("10:21").plusMillis(200)
        session().onAlarm()
        assertEquals(4, surface.calls.size)

        // The main process is up at 10:21:02.
        now = at("10:21").plusSeconds(2)
        mainRuntimeStarts()
        assertEquals(listOf(id("10:21")), dispatched)
        assertEquals(at("10:22").toEpochMilli(), state.values.getValue(rule.id).eventAtMillis)

        // Another reconcile (foreground, capability change) does not run it again.
        val again = BeforeUnlockAwareTimeAlarmRuntime(
            CoordinatorTimeAlarmRuntime(
                TimeAlarmCoordinator(store, state, backend, TimeEventDispatcher { dispatched += it.id.value }, { now }),
            ),
            BeforeUnlockHandover(storage, store, journal, audit, state, lockedAlarms, bootCount = { 3 }),
        )
        again.reconcile(ReconcileReason.APP_START)
        assertEquals(listOf(id("10:21")), dispatched)
    }

    @Test
    fun `an occurrence the locked path claimed does not run again after the takeover`() = runTest {
        bootAndRunUntil1020()
        locked = false
        now = at("10:20").plusSeconds(30)
        mainRuntimeStarts()

        // 10:16 (saved before the restart), 10:17..10:20 (run before unlock): none runs again.
        assertEquals(emptyList(), dispatched)
        assertEquals(4, audit.events.count { it.kind == AuditKind.FIRED })
        assertEquals(at("10:21").toEpochMilli(), backend.scheduled.last().eventAtMillis)
    }

    @Test
    fun `the locked path claiming while the handover starts still runs the occurrence once`() = runTest {
        bootAndRunUntil1020()
        // The 10:21 alarm is acting (holding the lock) when the user unlocks and the main process starts.
        val acting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val base = LockedActionRunner(AndroidBaseActionExecutor(surface))
        val gated = DirectBootActionRunner { action ->
            acting.complete(Unit)
            release.await()
            base.run(action)
        }
        now = at("10:21").plusMillis(100)
        val alarm = launch { session(gated).onAlarm() }
        acting.await()

        locked = false
        now = at("10:21").plusSeconds(2)
        val takeover = launch { mainRuntimeStarts() }
        release.complete(Unit)
        alarm.join()
        takeover.join()

        // Ran once, before unlock; the normal runtime resumes at 10:22.
        assertEquals(5, surface.calls.size)
        assertEquals(emptyList(), dispatched)
        assertEquals(at("10:22").toEpochMilli(), state.values.getValue(rule.id).eventAtMillis)
    }

    @Test
    fun `the handover first, then the locked alarm, still runs the occurrence once`() = runTest {
        bootAndRunUntil1020()
        // A screen without a lock can let the main process start before the locked alarm of the same minute.
        now = at("10:21").plusMillis(50)
        mainRuntimeStarts()
        session().onAlarm()

        assertEquals(4, surface.calls.size)
        assertEquals(listOf(id("10:21")), dispatched)
    }
}
