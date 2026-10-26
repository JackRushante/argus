package dev.argus.engine.model

import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.StaticShellSafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Trigger `manual` (0.4.1, F8): wire, capability, validatore, shell e impronta. */
class ManualTriggerModelTest {
    private fun encode(trigger: Trigger) = ArgusJson.encodeToString(Trigger.serializer(), trigger)
    private fun decode(json: String) = ArgusJson.decodeFromString(Trigger.serializer(), json)

    private fun rule(trigger: Trigger, actions: List<Action> = listOf(Action.SetWifi(false))): Automation {
        val unsigned = Automation(
            id = AutomationId("manual-rule"),
            name = "manual-rule",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = actions,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    @Test
    fun `manual has the exact wire of the registry and round-trips`() {
        assertEquals("""{"type":"manual"}""", encode(Trigger.Manual))
        assertEquals(Trigger.Manual, decode("""{"type":"manual"}"""))
    }

    @Test
    fun `a rule with a manual trigger round-trips through the automation serializer`() {
        val automation = rule(Trigger.Manual)
        val json = ArgusJson.encodeToString(Automation.serializer(), automation)
        assertEquals(automation, ArgusJson.decodeFromString(Automation.serializer(), json))
    }

    @Test
    fun `the manual trigger needs only its own capability`() {
        val required = CapabilityRequirements.derive(Trigger.Manual, listOf(Action.SetFlashlight(true)))
        assertTrue(CapabilityIds.TRIGGER_MANUAL in required)
        assertEquals("trigger.manual", CapabilityIds.TRIGGER_MANUAL)
        assertEquals(
            setOf(CapabilityIds.TRIGGER_MANUAL, CapabilityIds.ACTION_SET_FLASHLIGHT),
            required,
        )
    }

    @Test
    fun `the approval fingerprint is stable and tells a manual rule from an immediate one`() {
        val manual = rule(Trigger.Manual)
        assertEquals(manual.approvalFingerprint, ApprovalFingerprints.of(manual))
        assertNotEquals(
            manual.approvalFingerprint,
            rule(Trigger.Immediate).approvalFingerprint,
        )
    }

    @Test
    fun `existing triggers keep their pinned approval fingerprint`() {
        // Stesso fixture e stesso hash PINNATO di V1FingerprintCompatibilityTest ("phone-state"): il
        // nuovo tipo non tocca la serializzazione degli altri.
        val automation = Automation(
            id = AutomationId("v1-phone"),
            name = "v1-phone",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = decode(
                """{"type":"phone_state","event":"SMS_RECEIVED","number":null,"textMatch":"codice"}""",
            ),
            actions = listOf(Action.ShowNotification("fixture", "fixture")),
            enabled = false,
            priority = 3,
            cooldownMs = 12_345,
        )
        assertEquals(
            "63ea2dc5dbaf1fadea1de24512ed890a6436135e480f2e57bd49ba05ca907179",
            ApprovalFingerprints.of(automation).value,
        )
    }

    @Test
    fun `the validator accepts a manual draft and has nothing to say about the trigger`() {
        val validator = DraftValidator(knownTools = setOf("notify.show"))
        val issues = validator.validate(
            AutomationDraft(
                name = "Apri il garage",
                trigger = Trigger.Manual,
                actions = listOf(Action.ShowNotification("Argus", "ok")),
            ),
            emptySet(),
        )
        assertEquals(emptyList(), issues.filter { it.severity == Severity.ERROR })
    }

    @Test
    fun `a manual trigger may start a static shell command, only for its own event`() {
        assertTrue(StaticShellSafety.allows(Trigger.Manual, emptySet()))
        val event = TriggerEvent.ManualFired(AutomationId("manual-rule"), ApprovalFingerprint("f".repeat(64)))
        assertTrue(StaticShellSafety.allows(event, emptySet()))
        assertTrue(StaticShellSafety.allows(Trigger.Manual, event, emptySet()))
        // Un evento manuale non innesca la shell di una regola con un altro trigger.
        assertFalse(
            StaticShellSafety.allows(Trigger.Time(cron = "0 8 * * *", tz = "UTC"), event, emptySet()),
        )
        assertFalse(
            StaticShellSafety.allows(
                Trigger.Manual,
                TriggerEvent.TimeFired(AutomationId("manual-rule"), ApprovalFingerprint("f".repeat(64))),
                emptySet(),
            ),
        )
    }
}
