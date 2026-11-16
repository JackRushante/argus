package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pausa per gruppo (0.7.2): la stessa pausa del motore (F21) decisa regola per regola. Una regola sospesa dal
 * suo gruppo salta lo scatto come in pausa globale (stesso tipo di voce nel Log, nessun claim, nessun cooldown,
 * niente recupero alla ripresa); le altre regole dello stesso evento scattano.
 */
class GroupPauseEngineTest {
    private val wifi = TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, null)

    private fun armed(id: String, cooldownMs: Long = 0): Automation {
        val unsigned = Automation(
            AutomationId(id), id, CreatedBy.USER, AutomationStatus.ARMED,
            Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED),
            listOf(Action.SetFlashlight(true)),
            cooldownMs = cooldownMs,
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    /** Pausa con un interruttore globale e un insieme di regole sospese dal loro gruppo. */
    private class GroupPause(
        var global: Boolean = false,
        var suspended: Set<String> = emptySet(),
    ) : AutomationPause {
        val asked = mutableListOf<String>()
        override fun isPaused(): Boolean = global
        override suspend fun pauseCodeFor(automationId: AutomationId): String? {
            asked += automationId.value
            return when {
                global -> PauseCodes.PAUSED
                automationId.value in suspended -> PauseCodes.GROUP_PAUSED
                else -> null
            }
        }
    }

    private class Rig(rules: List<Automation>, val pause: GroupPause, now: () -> Long = { 1_000 }) {
        val executor = FakeActionExecutor()
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val engine = Engine(
            store = FakeAutomationStore(rules),
            executor = executor,
            evaluator = ConditionEvaluator(Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC)),
            matcher = TriggerMatcher(),
            firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
            audit = audit,
            journal = journal,
            firingPause = pause,
            now = now,
        )
    }

    private fun envelope(id: String) = TriggerEnvelope(TriggerEventId(id), wifi)

    @Test
    fun `a rule suspended by its group skips the fire while the others of the same event run`() = runTest {
        val rig = Rig(listOf(armed("casa"), armed("lavoro")), GroupPause(suspended = setOf("casa")))

        val out = rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        assertEquals(listOf("lavoro"), out.map { it.automation.id.value })
        assertEquals(1, rig.executor.executed.size)
        val skipped = rig.audit.events.single { it.automationId.value == "casa" }
        assertEquals(AuditKind.BLOCKED_POLICY, skipped.kind)
        assertEquals(PauseCodes.GROUP_PAUSED, skipped.detail)
        assertEquals(1, rig.journal.completions.size, "lo scatto saltato non prende nessun claim")
    }

    @Test
    fun `the global pause keeps its own code even for a rule whose group is paused`() = runTest {
        val rig = Rig(listOf(armed("casa")), GroupPause(global = true, suspended = setOf("casa")))

        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        assertEquals(PauseCodes.PAUSED, rig.audit.events.single().detail)
        assertTrue(rig.executor.executed.isEmpty())
    }

    @Test
    fun `resuming the group does not recover the skipped fire and the cooldown was not consumed`() = runTest {
        var now = 1_000L
        val pause = GroupPause(suspended = setOf("casa"))
        val rig = Rig(listOf(armed("casa", cooldownMs = 60_000)), pause, now = { now })

        rig.engine.onTrigger(envelope("wifi:during")) { DeviceState() }
        pause.suspended = emptySet()
        now = 2_000L
        assertTrue(rig.executor.executed.isEmpty(), "niente recupero alla ripresa")

        val after = rig.engine.onTrigger(envelope("wifi:after")) { DeviceState() }
        assertEquals(1, after.size)
        assertEquals(0, rig.audit.events.count { it.kind == AuditKind.SUPPRESSED_COOLDOWN })
    }

    @Test
    fun `the decision is asked per rule only for the rules the event matches`() = runTest {
        val other = armed("bt").copy(trigger = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED))
        val pause = GroupPause()
        val rig = Rig(listOf(armed("casa"), other), pause)

        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        assertEquals(listOf("casa"), pause.asked)
    }

    @Test
    fun `a pause written as the global switch alone decides every rule the same way`() = runTest {
        var paused = true
        val pause = AutomationPause { paused }
        assertEquals(PauseCodes.PAUSED, pause.pauseCodeFor(AutomationId("x")))
        paused = false
        assertNull(pause.pauseCodeFor(AutomationId("x")))
        assertNull(AutomationPause.NEVER.pauseCodeFor(AutomationId("x")))
        assertEquals("paused_group", PauseCodes.GROUP_PAUSED)
    }
}
