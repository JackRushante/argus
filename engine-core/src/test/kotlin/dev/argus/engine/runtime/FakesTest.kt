package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
class FakesTest {
    @Test fun `fake executor records calls and submits generative`() = runTest {
        val ex = FakeActionExecutor()
        val eventId = TriggerEventId("alarm:a1:1")
        val automationId = AutomationId("a1")
        val ctx = FireContext(
            TriggerEvent.TimeFired(automationId), DeviceState(), automationId, eventId,
            StableExecutionIdFactory.create(automationId, eventId),
        )
        assertEquals(ActionResult.Success, ex.execute(Action.SetWifi(false), ctx))
        assertEquals(ActionResult.Submitted, ex.execute(Action.InvokeLlm("g", listOf(), listOf("whatsapp_reply"), true), ctx))
        assertEquals(2, ex.executed.size)
    }
    @Test fun `fake store cooldown`() = runTest {
        val store = FakeAutomationStore()
        val id = AutomationId("a1")
        store.recordFired(id, 1_000)
        assertEquals(1_000, store.lastFiredAt(id))
    }
    @Test fun `fake audit records events`() = runTest {
        val sink = FakeAuditSink()
        sink.record(AuditEvent(AutomationId("a1"), AuditKind.FIRED, 42))
        assertEquals(AuditKind.FIRED, sink.events.single().kind)
    }
}
