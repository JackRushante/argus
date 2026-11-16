package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Forma risolta di `notification_reply` e `notification_action` (0.8). */
class TaintAwareInterpolatorNotification08Test {
    private val interpolator = TaintAwareInterpolator()

    private val message = VarValue(
        "ci vediamo alle 8",
        VarType.TEXT,
        IntegrityLabel.TAINTED,
        ConfidentialityLabel.PRIVATE,
        setOf(ValueProvenance.NOTIFICATION),
    )

    @Test
    fun `the reply text is a sink like whatsapp_reply`() {
        val resolved = assertIs<ActionResolution.Resolved>(
            interpolator.resolve(Action.NotificationReply("Ok: \${msg}"), VarScope(mapOf("msg" to message))),
        ).value
        assertEquals("Ok: ci vediamo alle 8", assertIs<Action.NotificationReply>(resolved.action).text)
        assertEquals(IntegrityLabel.TAINTED, resolved.inputIntegrity)
        assertEquals(
            "resolved_action_invalid",
            assertIs<ActionResolution.Blocked>(interpolator.resolve(Action.NotificationReply(" "), VarScope())).code,
        )
    }

    @Test
    fun `notification actions resolve as approved, and a malformed one is blocked`() {
        listOf(
            Action.NotificationAction(NotificationActionOp.DISMISS),
            Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 15),
            Action.NotificationAction(NotificationActionOp.CLICK, label = "Archivia"),
        ).forEach { action ->
            assertEquals(
                action,
                assertIs<ActionResolution.Resolved>(interpolator.resolve(action, VarScope())).value.action,
            )
        }
        listOf(
            Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 0),
            Action.NotificationAction(NotificationActionOp.CLICK),
            Action.NotificationAction(NotificationActionOp.CLICK, label = "\${x}"),
        ).forEach { action ->
            assertEquals(
                "resolved_action_invalid",
                assertIs<ActionResolution.Blocked>(interpolator.resolve(action, VarScope())).code,
            )
        }
    }
}
