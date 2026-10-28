package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validazione delle azioni privilegiate tipizzate 0.5.1. */
class DraftValidatorPrivileged051Test {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun codes(issues: List<ValidationIssue>, severity: Severity) =
        issues.filter { it.severity == severity }.map { it.code }
    private fun errors(issues: List<ValidationIssue>) = codes(issues, Severity.ERROR)
    private fun warnings(issues: List<ValidationIssue>) = codes(issues, Severity.WARNING)

    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
    private fun check(vararg actions: Action, trigger: Trigger = time) =
        v.validate(AutomationDraft(name = "r", trigger = trigger, actions = actions.toList()), emptySet())

    private val youtube = "com.google.android.youtube"
    private val mic = "android.permission.RECORD_AUDIO"

    @Test
    fun `the plain toggles and the no field actions are valid and silent`() {
        listOf(
            Action.SetAirplane(true), Action.SetLocation(false), Action.SetNfc(true),
            Action.SetBatterySaver(true), Action.LockScreen, Action.Screenshot,
        ).forEach { action ->
            val issues = check(action)
            assertEquals(emptyList(), errors(issues), action.toString())
            assertEquals(emptyList(), warnings(issues), action.toString())
        }
    }

    @Test
    fun `force stop and unfreeze of an ordinary app are valid and silent`() {
        listOf(AppControlOp.FORCE_STOP, AppControlOp.UNFREEZE).forEach { op ->
            val issues = check(Action.AppControl(youtube, op))
            assertEquals(emptyList(), errors(issues), op.name)
            assertEquals(emptyList(), warnings(issues), op.name)
        }
    }

    @Test
    fun `freeze carries the review warning`() {
        val issues = check(Action.AppControl(youtube, AppControlOp.FREEZE))
        assertEquals(emptyList(), errors(issues))
        assertEquals(listOf("app_control_freeze_review"), warnings(issues))
    }

    @Test
    fun `app control refuses Argus and the essential system packages`() {
        listOf("dev.argus", "dev.argus.debug", "com.android.systemui", "com.android.settings", "com.google.android.gms")
            .forEach { pkg ->
                AppControlOp.entries.forEach { op ->
                    assertTrue("priv_target_forbidden" in errors(check(Action.AppControl(pkg, op))), "$pkg $op")
                }
            }
    }

    @Test
    fun `a package that is malformed or a placeholder is rejected`() {
        listOf("", "youtube", "\${app}", "com.\${app}.x", "com.x; rm -rf /", "a".repeat(300) + ".b").forEach { pkg ->
            assertTrue("priv_pkg_invalid" in errors(check(Action.AppControl(pkg, AppControlOp.FORCE_STOP))), pkg)
            assertTrue("priv_pkg_invalid" in errors(check(Action.AppPermission(pkg, mic, true))), pkg)
        }
    }

    @Test
    fun `a permission is granted or revoked only from the closed runtime list`() {
        assertEquals(emptyList(), errors(check(Action.AppPermission(youtube, mic, grant = true))))
        assertEquals(emptyList(), warnings(check(Action.AppPermission(youtube, mic, grant = true))))
        listOf(
            "android.permission.WRITE_SECURE_SETTINGS", "android.permission.INTERNET", "RECORD_AUDIO", "",
            "\${perm}", "android.permission.\${x}",
        ).forEach { permission ->
            assertTrue(
                "priv_permission_invalid" in errors(check(Action.AppPermission(youtube, permission, true))),
                permission,
            )
        }
    }

    @Test
    fun `revoking a permission carries the review warning`() {
        val issues = check(Action.AppPermission(youtube, mic, grant = false))
        assertEquals(emptyList(), errors(issues))
        assertEquals(listOf("app_permission_revoke_review"), warnings(issues))
    }

    @Test
    fun `permissions of Argus cannot be changed`() {
        listOf(true, false).forEach { grant ->
            assertTrue("priv_target_forbidden" in errors(check(Action.AppPermission("dev.argus", mic, grant))))
        }
        // Un'app di sistema qualunque non è protetta per i permessi.
        assertFalse("priv_target_forbidden" in errors(check(Action.AppPermission("com.android.settings", mic, true))))
    }

    @Test
    fun `a forbidden target is rejected also inside a block`() {
        val inside = Action.While(
            condition = Condition.BooleanLiteral(true),
            body = listOf(Action.AppControl("dev.argus", AppControlOp.FORCE_STOP)),
            maxIterations = 2,
        )
        assertTrue("priv_target_forbidden" in errors(check(inside)))
    }

    @Test
    fun `the actions are valid with an external trigger like the other privileged ones`() {
        val sms = check(Action.SetAirplane(true), trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED))
        assertEquals(emptyList(), errors(sms))
    }
}
