package dev.argus.automation.dryrun

import dev.argus.automation.ASK_OUTSIDE_PROGRAM
import dev.argus.automation.http.HttpRequestExecutor
import dev.argus.automation.telephony.ContactLookup
import dev.argus.automation.telephony.TelephonyActionRunner
import dev.argus.engine.dryrun.DryRunCodes
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.CallerScopeGate
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.PendingDraft
import dev.argus.shizuku.ShizukuShellException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DryRunSourcesTest {
    @Test
    fun `the dry run codes are the executor's own`() {
        assertEquals(TelephonyActionRunner.TELEPHONY_UNAVAILABLE, DryRunCodes.TELEPHONY_UNAVAILABLE)
        assertEquals(TelephonyActionRunner.SMS_PERMISSION_MISSING, DryRunCodes.SMS_PERMISSION_MISSING)
        assertEquals(TelephonyActionRunner.CALL_PERMISSION_MISSING, DryRunCodes.CALL_PERMISSION_MISSING)
        assertEquals(TelephonyActionRunner.ACTION_INVALID, DryRunCodes.ACTION_INVALID)
        assertEquals(TelephonyActionRunner.SHIZUKU_UNAVAILABLE, DryRunCodes.SHIZUKU_UNAVAILABLE)
        assertEquals(ShizukuShellException.UNAVAILABLE, DryRunCodes.SHIZUKU_UNAVAILABLE)
        assertEquals(HttpRequestExecutor.REQUEST_INVALID, DryRunCodes.HTTP_REQUEST_INVALID)
        assertEquals(HttpRequestExecutor.CLEARTEXT_SECRET, DryRunCodes.HTTP_CLEARTEXT_SECRET)
        assertEquals(HttpRequestExecutor.SECRET_MISSING, DryRunCodes.HTTP_SECRET_MISSING)
        assertEquals(ASK_OUTSIDE_PROGRAM, DryRunCodes.ASK_OUTSIDE_PROGRAM)
        val executor = File("src/main/kotlin/dev/argus/automation/ShizukuActionExecutor.kt").readText()
        listOf(
            DryRunCodes.SHELL_EXTERNAL_TRIGGER,
            DryRunCodes.UI_STEPS_EXTERNAL_TRIGGER,
            DryRunCodes.REPLY_EVENT_UNVERIFIED,
            DryRunCodes.REPLY_PACKAGE_UNTRUSTED,
            DryRunCodes.UNSUPPORTED_PHASE,
            DryRunCodes.STORED_UNRESOLVED,
            DryRunCodes.INVOKE_LLM_V2_UNSUPPORTED,
            DryRunCodes.P4_NOT_YET_EXECUTABLE,
        ).forEach { code -> assertTrue("\"$code\"" in executor, code) }
    }

    private fun automation(status: AutomationStatus) =
        Automation(AutomationId("rule"), "Salvata", CreatedBy.USER, status, Trigger.Manual, listOf(Action.Vibrate(1)))

    private fun draft(id: String, automationId: String) = PendingDraft(
        id = DraftId(id),
        automationId = AutomationId(automationId),
        revision = 1,
        fingerprint = ApprovalFingerprint("a".repeat(64)),
        draft = AutomationDraft("Bozza", Trigger.Manual, listOf(Action.Vibrate(2))),
        createdBy = CreatedBy.LLM,
        priority = 0,
        schemaVersion = 1,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    @Test
    fun `a saved rule stays the truth of its detail and a draft becomes the rule it would be`() = runBlocking {
        val saved = automation(AutomationStatus.ARMED)
        val edit = draft("d1", "rule")
        val reader = DryRunRuleReader({ id -> saved.takeIf { it.id == id } }, { listOf(edit) }, { emptyMap() })

        assertEquals("Salvata", reader.load("rule")?.automation?.name)
        val fromDraft = reader.load("d1")?.automation
        assertEquals("Bozza", fromDraft?.name)
        assertEquals(AutomationStatus.PENDING_APPROVAL, fromDraft?.status)
        assertNull(reader.load("nothing"))
    }

    @Test
    fun `the caller scope of a dry run is a read-only contact lookup`() = runBlocking {
        val rule = automation(AutomationStatus.ARMED)
        fun call(number: String?) = TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, number)
        val contacts = ContactLookup { number -> if (number == "+391111111") true else if (number == "+392222222") false else null }
        val gate = ContactScopeGate(contacts)
        assertEquals(CallerScopeGate.KNOWN_CALLER, gate.closedCode(rule, call("+391111111")))
        assertNull(gate.closedCode(rule, call("+392222222")))
        // A hidden number counts as unknown, as for a real call.
        assertNull(gate.closedCode(rule, call(null)))
        assertEquals(CallerScopeGate.CALLER_UNVERIFIED, gate.closedCode(rule, call("+393333333")))
    }
}
