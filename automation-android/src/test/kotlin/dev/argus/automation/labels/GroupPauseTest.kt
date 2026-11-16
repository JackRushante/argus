package dev.argus.automation.labels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ScheduledAlarmMode
import dev.argus.automation.ScheduledTimeAlarm
import dev.argus.automation.TimeAlarmBackend
import dev.argus.automation.TimeAlarmCoordinator
import dev.argus.automation.TimeAlarmRegistration
import dev.argus.automation.TimeAlarmStateStore
import dev.argus.automation.TimeEventDispatcher
import dev.argus.automation.manual.FakePauseStore
import dev.argus.automation.manual.ImmediateLauncher
import dev.argus.automation.manual.MapStore
import dev.argus.automation.manual.ManualRuleRunner
import dev.argus.automation.manual.ManualRunResult
import dev.argus.automation.manual.FakeTokenStore
import dev.argus.automation.manual.RecordingAudit
import dev.argus.automation.manual.manualRule
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.RuleLabels
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.PauseCodes
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pausa per gruppo (0.7.2): la pausa globale (F21) decisa regola per regola. Stato che sopravvive al riavvio,
 * una regola con più etichette ferma se una sola è in pausa, e lo stesso trattamento della pausa globale anche
 * per le regole one-shot e per l'avvio manuale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupPauseTest {
    private lateinit var context: Context

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("argus_label_pause", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- stato persistente ---

    @Test
    fun `a paused group survives a restart and is matched without case`() {
        val first = PrefsLabelPauseStore(context)
        assertEquals(emptySet(), first.pausedKeys.value)

        first.setPaused(" Casa ", true)
        assertEquals(setOf("casa"), first.pausedKeys.value)

        val restored = PrefsLabelPauseStore(context)
        assertEquals(setOf("casa"), restored.pausedKeys.value)
        restored.setPaused("CASA", false)
        assertEquals(emptySet(), PrefsLabelPauseStore(context).pausedKeys.value)
    }

    @Test
    fun `the pause of a label no longer in use is forgotten`() {
        val store = PrefsLabelPauseStore(context)
        store.setPaused("Casa", true)
        store.setPaused("Lavoro", true)

        store.retainOnly(setOf("lavoro", "auto"))

        assertEquals(setOf("lavoro"), store.pausedKeys.value)
        assertEquals(setOf("lavoro"), PrefsLabelPauseStore(context).pausedKeys.value)
    }

    // --- decisione per regola ---

    @Test
    fun `a rule is paused if any of its labels is, and the global pause wins`() = runTest {
        val global = FakePauseStore()
        val groups = MemoryLabelPause(setOf("casa"))
        val labels = mapOf("both" to setOf("lavoro", "casa"), "work" to setOf("lavoro"))
        val pause = LabelAwarePause(global, groups) { labels[it.value].orEmpty() }

        assertEquals(PauseCodes.GROUP_PAUSED, pause.pauseCodeFor(AutomationId("both")))
        assertNull(pause.pauseCodeFor(AutomationId("work")))
        assertNull(pause.pauseCodeFor(AutomationId("none")))

        global.setPaused(true)
        assertEquals(PauseCodes.PAUSED, pause.pauseCodeFor(AutomationId("work")))
        assertTrue(pause.isPaused())
    }

    @Test
    fun `without paused groups the labels are not even read`() = runTest {
        var reads = 0
        val pause = LabelAwarePause(FakePauseStore(), MemoryLabelPause()) {
            reads++
            setOf("casa")
        }

        assertNull(pause.pauseCodeFor(AutomationId("r1")))
        assertEquals(0, reads)
    }

    @Test
    fun `labels that cannot be read keep the rule still while a group is paused`() = runTest {
        val pause = LabelAwarePause(FakePauseStore(), MemoryLabelPause(setOf("casa"))) { error("db chiuso") }

        assertEquals(PauseCodes.GROUP_PAUSED, pause.pauseCodeFor(AutomationId("r1")))
    }

    // --- stesso trattamento della pausa globale ---

    @Test
    fun `a one-shot rule skipped by its group is treated exactly like under the global pause`() = runTest {
        val global = oneShotDuringPause(globalPaused = true)
        val group = oneShotDuringPause(globalPaused = false)

        assertEquals(PauseCodes.PAUSED, global.skippedCode)
        assertEquals(PauseCodes.GROUP_PAUSED, group.skippedCode)
        assertEquals(global.copy(skippedCode = ""), group.copy(skippedCode = ""))
        assertTrue(group.executed == 0)
    }

    @Test
    fun `a manual start of a rule in a paused group says paused and is skipped by the engine`() = runTest {
        val rule = manualRule("m1")
        val other = manualRule("m2")
        val store = MapStore(rule, other)
        val audit = RecordingAudit()
        var executed = 0
        val pause = LabelAwarePause(FakePauseStore(), MemoryLabelPause(setOf("garage"))) { id ->
            if (id.value == "m1") setOf("garage") else emptySet()
        }
        val engine = engine(store, audit, pause) { executed++ }
        val runner = ManualRuleRunner(
            store = store,
            tokens = FakeTokenStore(),
            pause = pause,
            dispatcher = { envelope -> engine.onTrigger(envelope) { DeviceState() } },
            launcher = ImmediateLauncher,
        )

        assertTrue(assertIs<ManualRunResult.Accepted>(runner.runFromApp("m1")).paused)
        assertEquals(0, executed)
        assertEquals(PauseCodes.GROUP_PAUSED, audit.events.single { it.kind == AuditKind.BLOCKED_POLICY }.detail)

        assertEquals(false, assertIs<ManualRunResult.Accepted>(runner.runFromApp("m2")).paused)
        assertEquals(1, executed)
    }

    // --- aiuti ---

    private data class OneShotOutcome(
        val skippedCode: String,
        val executed: Int,
        val statusAfter: AutomationStatus?,
        val auditKinds: List<AuditKind>,
        val lifecycleDetails: List<String>,
    )

    private suspend fun oneShotDuringPause(globalPaused: Boolean): OneShotOutcome {
        val rule = manualRule("once", trigger = Trigger.Time(at = "2026-10-26T08:00", tz = "UTC"))
        val store = MapStore(rule)
        val audit = RecordingAudit()
        var executed = 0
        val pause = LabelAwarePause(
            FakePauseStore(globalPaused),
            MemoryLabelPause(if (globalPaused) emptySet() else setOf(RuleLabels.key("Sveglie"))),
        ) { setOf("sveglie") }
        val engine = engine(store, audit, pause) { executed++ }
        var now = Instant.parse("2026-10-26T07:00:00Z")
        val state = MemoryAlarmState()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = InexactBackend(),
            dispatcher = TimeEventDispatcher { envelope -> engine.onTrigger(envelope) { DeviceState() } },
            now = { now },
            audit = audit,
        )
        coordinator.reconcile(ReconcileReason.APP_START)
        val alarm = requireNotNull(state.get(rule.id))
        now = Instant.parse("2026-10-26T08:00:05Z")
        coordinator.onAlarm(rule.id, requireNotNull(rule.approvalFingerprint), alarm.eventAtMillis)

        return OneShotOutcome(
            skippedCode = audit.events.single { it.kind == AuditKind.BLOCKED_POLICY }.detail,
            executed = executed,
            statusAfter = store.get(rule.id)?.status,
            auditKinds = audit.events.map { it.kind },
            lifecycleDetails = audit.events.filter { it.kind != AuditKind.BLOCKED_POLICY }.map { it.detail },
        )
    }

    private fun engine(store: MapStore, audit: RecordingAudit, pause: LabelAwarePause, onRun: () -> Unit): Engine {
        val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC)
        return Engine(
            store = store,
            executor = ActionExecutor { _, _ ->
                onRun()
                ActionResult.Success
            },
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
            audit = audit,
            firingPause = pause,
            now = { clock.millis() },
        )
    }

    private class MemoryLabelPause(initial: Set<String> = emptySet()) : LabelPauseStore {
        private val state = MutableStateFlow(initial)
        override val pausedKeys: StateFlow<Set<String>> = state
        override fun setPaused(label: String, paused: Boolean) {
            val key = RuleLabels.key(label)
            state.value = if (paused) state.value + key else state.value - key
        }

        override fun retainOnly(keysInUse: Set<String>) {
            state.value = state.value intersect keysInUse
        }
    }

    private class MemoryAlarmState : TimeAlarmStateStore {
        private val values = linkedMapOf<AutomationId, ScheduledTimeAlarm>()
        override suspend fun get(automationId: AutomationId) = values[automationId]
        override suspend fun all() = values.values.toList()
        override suspend fun upsert(alarm: ScheduledTimeAlarm) {
            values[alarm.automationId] = alarm
        }

        override suspend fun delete(automationId: AutomationId) {
            values.remove(automationId)
        }
    }

    private class InexactBackend : TimeAlarmBackend {
        override fun canScheduleExact() = false
        override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode = ScheduledAlarmMode.INEXACT
        override fun cancel(automationId: AutomationId) = Unit
    }
}
