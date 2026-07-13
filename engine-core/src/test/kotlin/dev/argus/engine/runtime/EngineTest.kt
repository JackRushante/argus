package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class EngineTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private fun armed(id: String, t: Trigger, acts: List<Action>, cond: Condition? = null, cooldown: Long = 0, prio: Int = 0) =
        Automation(AutomationId(id), id, CreatedBy.LLM, AutomationStatus.ARMED, t, acts, cond, cooldownMs = cooldown, priority = prio)
    private fun engine(store: AutomationStore, ex: ActionExecutor, clockIso: String, audit: AuditSink = NoopAuditSink, now: () -> Long = { 1000 }) =
        Engine(store, ex, ConditionEvaluator(clock(clockIso)), TriggerMatcher(), audit, now)

    @Test fun `time trigger fires deterministic action when condition holds`() = runTest {
        val a = armed("a1", Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
            cond = Condition.StateEquals("ringer", CmpOp.NEQ, "silent"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        engine(store, ex, "2026-07-12T21:30:00Z")
            .onTrigger(TriggerEvent.TimeFired(AutomationId("a1"))) { DeviceState(values = mapOf("ringer" to "normal")) }
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }
    @Test fun `condition false skips actions and audits`() = runTest {
        val a = armed("a1", Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), listOf(Action.SetWifi(false)),
            cond = Condition.StateEquals("ringer", CmpOp.EQ, "silent"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a)); val audit = FakeAuditSink()
        engine(store, ex, "2026-07-12T21:30:00Z", audit)
            .onTrigger(TriggerEvent.TimeFired(AutomationId("a1"))) { DeviceState(values = mapOf("ringer" to "normal")) }
        assertEquals(emptyList<Action>(), ex.executed)
        assertEquals(AuditKind.CONDITIONS_NOT_MET, audit.events.single().kind)
    }
    @Test fun `state provider is lazy - not called when nothing matches`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var reads = 0
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(TriggerEvent.NotificationPosted("com.telegram")) { reads++; DeviceState() }
        assertEquals(0, reads)
        assertEquals(emptyList<Action>(), ex.executed)
    }
    @Test fun `higher priority executes LAST and wins on shared target`() = runTest {
        val low = armed("low", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), prio = 1)
        val high = armed("high", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(true)), prio = 10)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(high, low))
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(TriggerEvent.NotificationPosted("com.whatsapp")) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetWifi(false), Action.SetWifi(true)), ex.executed)  // last-writer-wins
    }
    @Test fun `exception in one automation does not break the batch`() = runTest {
        val bad = armed("bad", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), prio = 0)
        val good = armed("good", Trigger.Notification("com.whatsapp"), listOf(Action.SetBluetooth(true)), prio = 1)
        val store = FakeAutomationStore(listOf(bad, good)); val audit = FakeAuditSink()
        val throwing = object : ActionExecutor {
            val executed = mutableListOf<Action>()
            override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
                if (ctx.automationId == AutomationId("bad")) throw IllegalStateException("boom")
                executed += action; return ActionResult.Success
            }
        }
        val outcomes = engine(store, throwing, "2026-07-12T10:00:00Z", audit)
            .onTrigger(TriggerEvent.NotificationPosted("com.whatsapp")) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetBluetooth(true)), throwing.executed)
        assertEquals(1, outcomes.size)
        assertTrue(audit.events.any { it.kind == AuditKind.ERROR && it.automationId == AutomationId("bad") })
    }
    @Test fun `cooldown suppresses second fire`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), cooldown = 5000)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var now = 1000L
        val e = engine(store, ex, "2026-07-12T10:00:00Z", now = { now })
        val ev = TriggerEvent.NotificationPosted("com.whatsapp")
        e.onTrigger(ev) { DeviceState() }; now = 2000L; e.onTrigger(ev) { DeviceState() }
        assertEquals(1, ex.executed.size)
    }
    @Test fun `generative rules get a minimum 60s cooldown even when configured 0`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"),
            listOf(Action.InvokeLlm("reply", listOf("notification"), listOf("whatsapp_reply"), true)), cooldown = 0)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var now = 1000L
        val e = engine(store, ex, "2026-07-12T10:00:00Z", now = { now })
        val ev = TriggerEvent.NotificationPosted("com.whatsapp")
        e.onTrigger(ev) { DeviceState() }; now = 31_000L; e.onTrigger(ev) { DeviceState() }   // +30s: dentro il minimo
        assertEquals(1, ex.executed.size)
        now = 62_000L; e.onTrigger(ev) { DeviceState() }                                       // +61s: fuori
        assertEquals(2, ex.executed.size)
    }
}
