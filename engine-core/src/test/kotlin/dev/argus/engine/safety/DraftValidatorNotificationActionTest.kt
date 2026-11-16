package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.FeatureGate
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.NotificationActionPolicy
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `notification_action` (0.8): chiudere, posticipare o premere un pulsante della notifica del trigger. */
class DraftValidatorNotificationActionTest {
    private val v = DraftValidator(knownTools = emptySet())

    private fun issues(d: AutomationDraft) = v.validate(d, emptySet())
    private fun errors(d: AutomationDraft) = issues(d).filter { it.severity == Severity.ERROR }.map { it.code }

    private fun draft(action: Action, trigger: Trigger = Trigger.Notification("com.example.mail")) =
        AutomationDraft(name = "notifica", trigger = trigger, actions = listOf(action))

    private fun dismiss() = Action.NotificationAction(NotificationActionOp.DISMISS)
    private fun snooze(minutes: Int?) = Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = minutes)
    private fun click(label: String?) = Action.NotificationAction(NotificationActionOp.CLICK, label = label)

    @Test
    fun `the feature is switched on`() {
        assertTrue(FeatureGate.ACTION_NOTIFICATION_ACTION.enabled)
    }

    @Test
    fun `the three operations are valid with their own fields`() {
        assertEquals(emptyList(), errors(draft(dismiss())))
        assertEquals(emptyList(), errors(draft(snooze(1))))
        assertEquals(emptyList(), errors(draft(snooze(1_440))))
        assertEquals(emptyList(), errors(draft(click("Segna come letto"))))
    }

    @Test
    fun `only a notification trigger has a notification to act on`() {
        val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        assertTrue("notification_action_no_notification" in errors(draft(dismiss(), time)))
        assertTrue("notification_action_no_notification" in errors(draft(click("OK"), Trigger.Manual)))
    }

    @Test
    fun `snooze lasts from one minute to one day`() {
        assertTrue("notification_action_snooze_invalid" in errors(draft(snooze(null))))
        assertTrue("notification_action_snooze_invalid" in errors(draft(snooze(0))))
        assertTrue("notification_action_snooze_invalid" in errors(draft(snooze(1_441))))
        assertTrue(
            "notification_action_snooze_invalid" in errors(
                draft(Action.NotificationAction(NotificationActionOp.DISMISS, snoozeMinutes = 5)),
            ),
        )
    }

    @Test
    fun `click needs a literal label of a visible button`() {
        assertTrue("notification_action_label_invalid" in errors(draft(click(null))))
        assertTrue("notification_action_label_invalid" in errors(draft(click("   "))))
        assertTrue("notification_action_label_invalid" in errors(draft(click("x".repeat(101)))))
        assertTrue("notification_action_label_invalid" in errors(draft(click("OK\nAnnulla"))))
        assertTrue("notification_action_label_interpolated" in errors(draft(click("\${scelta}"))))
        assertTrue(
            "notification_action_label_invalid" in errors(
                draft(Action.NotificationAction(NotificationActionOp.DISMISS, label = "OK")),
            ),
        )
    }

    @Test
    fun `click never presses buttons of argus or of the apps macros cannot touch`() {
        NotificationActionPolicy.isForbiddenTarget("com.android.settings").let(::assertTrue)
        listOf("com.android.systemui", "com.android.vending", "moe.shizuku.privileged.api", "com.android.settings")
            .forEach { pkg ->
                assertTrue("notification_action_forbidden_app" in errors(draft(click("OK"), Trigger.Notification(pkg))), pkg)
            }
        // Dismiss e snooze nascondono soltanto: restano ammessi anche lì.
        assertFalse("notification_action_forbidden_app" in errors(draft(dismiss(), Trigger.Notification("com.android.systemui"))))
    }

    @Test
    fun `a click is shown for review with a warning`() {
        val warnings = issues(draft(click("Archivia"))).filter { it.severity == Severity.WARNING }.map { it.code }
        assertTrue("notification_click_review" in warnings)
        assertFalse("notification_click_review" in issues(draft(dismiss())).map { it.code })
    }

    @Test
    fun `labels match whole, trimmed and ignoring case`() {
        assertTrue(NotificationActionPolicy.labelMatches("  Segna come letto ", "segna come LETTO"))
        assertFalse(NotificationActionPolicy.labelMatches("Segna come letto", "Segna"))
        assertFalse(NotificationActionPolicy.labelMatches(null, "OK"))
    }
}
