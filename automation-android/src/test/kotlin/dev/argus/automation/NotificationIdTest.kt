package dev.argus.automation

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NotificationIdTest {
    private val rule = AutomationId("rule")
    private val fingerprint = ApprovalFingerprint("a".repeat(64))

    private fun context(execution: String, index: Int, path: String = (index + 1).toString()) = FireContext(
        event = TriggerEvent.ImmediateFired(rule, fingerprint),
        state = DeviceState(),
        automationId = rule,
        approvalFingerprint = fingerprint,
        eventId = TriggerEventId("immediate:$execution"),
        executionId = ExecutionId(execution),
        actionIndex = index,
        actionPath = path,
    )

    @Test
    fun `two notifications of the same execution get different ids`() {
        val first = notificationId(context("execution-1", index = 0))
        val second = notificationId(context("execution-1", index = 1))

        assertNotEquals(first, second)
    }

    @Test
    fun `a plain and a generated notification of the same rule do not overwrite each other`() {
        // Azione 1 = show_notification, azione 2 = invoke_llm con consegna locale: percorsi distinti.
        val plain = notificationId(context("execution-1", index = 0))
        val generated = notificationId(context("execution-1", index = 1))

        assertNotEquals(plain, generated)
    }

    @Test
    fun `nested and looped actions have their own ids`() {
        val ids = listOf("1", "2.then.1", "2.else.1", "3.while[1].1", "3.while[2].1")
            .mapIndexed { index, path -> notificationId(context("execution-1", index, path)) }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the same action of the same execution republishes the same id`() {
        assertEquals(
            notificationId(context("execution-1", index = 0)),
            notificationId(context("execution-1", index = 0)),
        )
    }

    @Test
    fun `different executions of the same action get different ids`() {
        assertNotEquals(
            notificationId(context("execution-1", index = 0)),
            notificationId(context("execution-2", index = 0)),
        )
    }
}
