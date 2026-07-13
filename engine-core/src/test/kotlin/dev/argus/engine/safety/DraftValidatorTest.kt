package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class DraftValidatorTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun errors(issues: List<ValidationIssue>) = issues.filter { it.severity == Severity.ERROR }.map { it.code }

    private val validGenerative = AutomationDraft(
        name = "reply moglie",
        trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
        actions = listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)),
        cooldownMs = 120_000,
    )

    @Test fun `valid generative draft has no errors`() {
        assertEquals(emptyList(), errors(v.validate(validGenerative, whitelistedIds = setOf("jid:42"))))
    }
    @Test fun `forbidden tools in InvokeLlm are rejected`() {
        val d = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("shell.run", "automation.create"), true)))
        val e = errors(v.validate(d, setOf("jid:42")))
        assertEquals(2, e.count { it == "tool_forbidden" })
    }
    @Test fun `generative reply on group or without conversationId is rejected`() {
        val group = validGenerative.copy(trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:g", isGroup = null))
        assertTrue("reply_target_group" in errors(v.validate(group, setOf("jid:g"))))
        val noId = validGenerative.copy(trigger = Trigger.Notification("com.whatsapp", sender = "Moglie", isGroup = false))
        assertTrue("reply_needs_conversation_id" in errors(v.validate(noId)))
    }
    @Test fun `target must be whitelisted when whitelist provided`() {
        assertTrue("target_not_whitelisted" in errors(v.validate(validGenerative, whitelistedIds = setOf("jid:other"))))
    }
    @Test fun `invalid cron and unknown state key are errors`() {
        val d = AutomationDraft("x", Trigger.Time(cron = "99 99 * * *", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)), conditions = Condition.StateEquals("suoneria", CmpOp.EQ, "silent"))
        val e = errors(v.validate(d))
        assertTrue("cron_invalid" in e); assertTrue("state_key_unknown" in e)
    }
    @Test fun `time requires exactly one of cron and at`() {
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(cron = "0 8 * * *", at = "2026-07-15T08:00", tz = "Europe/Rome"), listOf(Action.SetWifi(true))))))
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(tz = "Europe/Rome"), listOf(Action.SetWifi(true))))))
    }
    @Test fun `small geofence radius is a warning, empty actions an error`() {
        val d = AutomationDraft("x", Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true), emptyList())
        val issues = v.validate(d)
        assertTrue("no_actions" in errors(issues))
        assertTrue(issues.any { it.severity == Severity.WARNING && it.code == "radius_small" })
    }
    @Test fun `read tools plus reply channel is a privacy warning`() {
        val d = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("whatsapp_reply", "state.read"), true)))
        assertTrue(v.validate(d, setOf("jid:42")).any { it.code == "read_plus_reply" && it.severity == Severity.WARNING })
    }
}
