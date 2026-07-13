package dev.argus.ui.presentation
import dev.argus.engine.model.*
import dev.argus.ui.model.RuleRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class RuleRenderMapperTest {
    @Test fun `notification 1-1 generative rule renders trigger, condition, generative flag`() {
        val a = Automation(
            AutomationId("w1"), "Rispondi a Moglie", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", sender = "Moglie", isGroup = false),
            listOf(Action.InvokeLlm("rispondi nel tono X", listOf("notification"), listOf("whatsapp_reply"), true)),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"),
        )
        val r: RuleRender = RuleRenderMapper.map(a)
        assertEquals("notification", r.triggerIconKey)
        assertTrue(r.triggerLine.contains("WhatsApp") && r.triggerLine.contains("Moglie"))
        assertTrue(r.conditionLines.any { it.contains("18:00") && it.contains("22:00") })
        assertTrue(r.isGenerative)
        assertTrue(r.actions.single().isGenerative)
        assertTrue(r.privacyNote != null)
    }
    @Test fun `time DND rule renders cron humanized and deterministic action`() {
        val a = Automation(
            AutomationId("d1"), "DND notte", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val r = RuleRenderMapper.map(a)
        assertEquals("time", r.triggerIconKey)
        assertTrue(r.triggerLine.contains("23:00"))
        assertTrue(!r.isGenerative && r.privacyNote == null)
        assertEquals(1, r.actions.size)
    }
    @Test fun `shell action is flagged and command preserved`() {
        val a = Automation(
            AutomationId("s1"), "Backup", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(cron = "0 3 * * *", tz = "Europe/Rome"),
            listOf(Action.RunShell("cp -r /sdcard/DCIM /sdcard/backup")),
        )
        val row = RuleRenderMapper.map(a).actions.single()
        assertTrue(row.isShell)
        assertEquals("cp -r /sdcard/DCIM /sdcard/backup", row.shellCommand)
    }
}
