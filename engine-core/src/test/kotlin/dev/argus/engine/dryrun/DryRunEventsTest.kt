package dev.argus.engine.dryrun

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.runtime.VarScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DryRunEventsTest {
    private fun armed(trigger: Trigger): Automation {
        val unsigned = Automation(AutomationId("e"), "E", CreatedBy.USER, AutomationStatus.ARMED, trigger, listOf(Action.Vibrate(1)))
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    @Test
    fun `the form asks only for what the trigger carries`() {
        assertEquals(
            listOf(DryRunField.SENDER, DryRunField.TEXT),
            DryRunEvents.fields(Trigger.Notification("com.whatsapp")),
        )
        assertEquals(
            listOf(DryRunField.NUMBER, DryRunField.TEXT),
            DryRunEvents.fields(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)),
        )
        assertEquals(listOf(DryRunField.NUMBER), DryRunEvents.fields(Trigger.PhoneState(PhoneEvent.INCOMING_CALL)))
        assertEquals(emptyList(), DryRunEvents.fields(Trigger.Time(cron = "0 8 * * *", tz = "UTC")))
        assertEquals(emptyList(), DryRunEvents.fields(Trigger.Manual))
        assertEquals(listOf(DryRunField.MODE), DryRunEvents.fields(Trigger.UserModeChanged()))
        assertEquals(emptyList(), DryRunEvents.fields(Trigger.UserModeChanged("Casa")))
    }

    @Test
    fun `the defaults make the rule fire with the real matcher`() {
        val triggers = listOf(
            Trigger.Notification("com.whatsapp", conversationId = "wa:x", isGroup = false, textMatch = "urgente"),
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = "+393331234567", textMatch = "codice"),
            Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
            Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            Trigger.Manual,
            Trigger.Battery(below = 20),
            Trigger.UserModeChanged("Casa"),
        )
        for (trigger in triggers) {
            val event = DryRunEvents.event(armed(trigger), trigger, SpanPhase.START, DryRunEvents.defaults(trigger, "Anna"), "Anna")
            assertTrue(TriggerMatcher().matches(trigger, event), "$trigger")
        }
    }

    @Test
    fun `a time event carries the phase of the span it was scheduled for`() {
        val trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")
        val event = DryRunEvents.event(armed(trigger), trigger, SpanPhase.EXIT, DryRunSample())
        assertEquals(SpanPhase.EXIT, assertIs<TriggerEvent.TimeFired>(event).spanPhase)
    }

    @Test
    fun `the interpolator marks the escaped external part of a json body`() {
        val scope = VarScope(
            mapOf(
                "msg" to VarValue(
                    "say \"hi\"", VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE,
                    setOf(ValueProvenance.NOTIFICATION),
                ),
            ),
        )
        val action = Action.HttpRequest(
            HttpMethod.POST, "https://example.com/x", body = "{\"t\":\"\${msg}\"}", contentType = "application/json",
        )
        val resolved = assertIs<ActionResolution.Resolved>(TaintAwareInterpolator().resolve(action, scope)).value
        val body = assertIs<Action.HttpRequest>(resolved.action).body!!
        assertEquals("say \\\"hi\\\"", body.substring(resolved.taintedRanges.getValue("body").single()))
    }
}
