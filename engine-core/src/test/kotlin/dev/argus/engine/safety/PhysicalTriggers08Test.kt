package dev.argus.engine.safety

import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.FeatureGate
import dev.argus.engine.model.FeatureGates
import dev.argus.engine.model.GeofenceDwellPolicy
import dev.argus.engine.model.NextAlarmTriggerPolicy
import dev.argus.engine.model.NfcTagPolicy
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UiStep
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.RevalidatingFirePolicy
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.8, trigger fisici: tag NFC, prossima sveglia, tempo d'uso di un'app, gesti dall'accelerometro e
 * geofence DWELL. Limiti del validatore, legame tag/scansione verificata con la shell e parser.
 */
class PhysicalTriggers08Test {
    private val validator = DraftValidator(setOf("whatsapp_reply"))

    private fun draft(trigger: Trigger, actions: List<Action> = listOf(Action.Vibrate(100)), cooldownMs: Long = 0) =
        AutomationDraft(name = "fisico", trigger = trigger, actions = actions, cooldownMs = cooldownMs)

    private fun errors(d: AutomationDraft): List<String> =
        validator.validate(d, emptySet()).filter { it.severity == Severity.ERROR }.map { it.code }

    @Test
    fun `the five physical gates are on`() {
        val mine = setOf(
            FeatureGate.TRIGGER_NFC_TAG,
            FeatureGate.TRIGGER_NEXT_ALARM,
            FeatureGate.TRIGGER_APP_USAGE,
            FeatureGate.TRIGGER_SENSOR_SHAKE,
            FeatureGate.TRIGGER_SENSOR_FLIP_FACE_DOWN,
        )
        mine.forEach { assertTrue(it.enabled, it.wireName) }
        assertEquals(emptyList(), FeatureGates.disabledIn(draft(Trigger.NfcTag("04:A2:19:B3"))))
    }

    // --- nfc_tag --------------------------------------------------------------------------------

    @Test
    fun `an nfc tag rule needs either a read tag id or the read at arm`() {
        assertEquals(emptyList(), errors(draft(Trigger.NfcTag("04:A2:19:B3:5C:61:80"))))
        assertEquals(emptyList(), errors(draft(Trigger.NfcTag("04a219b3"))))
        // Il segnaposto del backup condiviso: si legge all'arm, come la posizione «qui».
        assertEquals(emptyList(), errors(draft(Trigger.NfcTag("", resolveTag = true))))
        // Il vuoto «da completare» senza lettura all'arm non si arma mai.
        assertEquals(listOf("nfc_tag_missing"), errors(draft(Trigger.NfcTag(""))))
        assertEquals(listOf("nfc_tag_missing"), errors(draft(Trigger.NfcTag("   "))))
        assertEquals(listOf("nfc_tag_invalid"), errors(draft(Trigger.NfcTag("casa"))))
        assertEquals(listOf("nfc_tag_invalid"), errors(draft(Trigger.NfcTag("04:A"))))
        assertEquals(listOf("nfc_tag_invalid"), errors(draft(Trigger.NfcTag("04:".repeat(40) + "04"))))
        // Un id già scritto E la lettura all'arm: quale vale? Si rifiuta.
        assertEquals(listOf("nfc_tag_ambiguous"), errors(draft(Trigger.NfcTag("04:A2", resolveTag = true))))
    }

    @Test
    fun `tag ids are normalized to colon separated upper case bytes`() {
        assertEquals("04:A2:19:B3", NfcTagPolicy.format(byteArrayOf(0x04, 0xA2.toByte(), 0x19, 0xB3.toByte())))
        assertNull(NfcTagPolicy.format(ByteArray(0)))
        assertNull(NfcTagPolicy.format(ByteArray(NfcTagPolicy.MAX_BYTES + 1)))
        assertEquals("04:A2:19", NfcTagPolicy.normalize("04a219"))
        assertEquals("04:A2:19", NfcTagPolicy.normalize("04:a2:19"))
        assertNull(NfcTagPolicy.normalize("xyz"))
    }

    @Test
    fun `a scan matches its tag in any spelling, an empty tag never`() {
        val matcher = TriggerMatcher()
        assertTrue(matcher.matches(Trigger.NfcTag("04:A2:19"), TriggerEvent.NfcTagScanned("04:a2:19", verified = false)))
        assertTrue(matcher.matches(Trigger.NfcTag("04a219"), TriggerEvent.NfcTagScanned("04:A2:19", verified = true)))
        assertFalse(matcher.matches(Trigger.NfcTag("04:A2:19"), TriggerEvent.NfcTagScanned("04:A2:18", verified = true)))
        assertFalse(matcher.matches(Trigger.NfcTag("", resolveTag = true), TriggerEvent.NfcTagScanned("", verified = true)))
    }

    @Test
    fun `only a scan proven to come from the nfc service may press the shell switch`() {
        val tag = Trigger.NfcTag("04:A2:19")
        assertTrue(StaticShellSafety.allows(tag, emptySet()))
        assertTrue(StaticShellSafety.allows(TriggerEvent.NfcTagScanned("04:A2:19", verified = true), emptySet()))
        assertFalse(StaticShellSafety.allows(TriggerEvent.NfcTagScanned("04:A2:19", verified = false), emptySet()))
        assertTrue(StaticShellSafety.allows(tag, TriggerEvent.NfcTagScanned("04:a2:19", verified = true), emptySet()))
        assertFalse(StaticShellSafety.allows(tag, TriggerEvent.NfcTagScanned("04:a2:19", verified = false), emptySet()))
        assertFalse(StaticShellSafety.allows(tag, TriggerEvent.NfcTagScanned("FF", verified = true), emptySet()))
        // Il default è il caso peggiore: non verificata.
        assertFalse(TriggerEvent.NfcTagScanned("04").verified)
    }

    private fun armed(trigger: Trigger, actions: List<Action>): Automation {
        val draft = AutomationDraft("nfc", trigger, actions)
        val value = Automation(
            id = AutomationId("n1"),
            name = "nfc",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = actions,
            schemaVersion = AutomationSchema.versionFor(draft),
        )
        val fingerprinted = value.copy(approvalFingerprint = ApprovalFingerprints.of(value))
        return fingerprinted.copy(
            requiredCapabilities = dev.argus.engine.model.CapabilityRequirements.derive(fingerprinted),
        )
    }

    /** Ogni capability della regola è disponibile: resta solo la domanda «chi preme l'interruttore». */
    private suspend fun evaluate(automation: Automation, event: TriggerEvent) = RevalidatingFirePolicy(
        FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = setOf("whatsapp_reply"),
                availableCapabilities = automation.requiredCapabilities + CapabilityIds.TRIGGER_NFC_TAG,
                whitelistedConversationIds = emptySet(),
            )
        },
    ).evaluate(automation, event)

    @Test
    fun `an unverified scan cannot run a shell or screen macro rule but runs the others`() = runTest {
        val tag = Trigger.NfcTag("04:A2:19")
        val verified = TriggerEvent.NfcTagScanned("04:A2:19", verified = true)
        val forged = TriggerEvent.NfcTagScanned("04:A2:19", verified = false)

        val shell = armed(tag, listOf(Action.RunShell("svc wifi disable")))
        assertEquals(FirePolicyDecision.Allow, evaluate(shell, verified))
        val blocked = assertIs<FirePolicyDecision.Block>(evaluate(shell, forged))
        assertEquals("shell_external_trigger", blocked.code)
        assertFalse(blocked.needsReview)

        val macro = armed(tag, listOf(Action.UiSteps(steps = listOf(UiStep.Back))))
        assertEquals(FirePolicyDecision.Allow, evaluate(macro, verified))
        assertEquals("ui_steps_external_trigger", assertIs<FirePolicyDecision.Block>(evaluate(macro, forged)).code)

        val plain = armed(tag, listOf(Action.Vibrate(200)))
        assertEquals(FirePolicyDecision.Allow, evaluate(plain, forged))
    }

    @Test
    fun `the parser keeps the tag out of the id and the verified flag in it`() {
        val parser = DeviceEventParser()
        val real = assertNotNull(parser.nfcTag("04:A2:19", verified = true, atMillis = 1_000))
        val forged = assertNotNull(parser.nfcTag("04:A2:19", verified = false, atMillis = 1_000))
        assertEquals(TriggerEvent.NfcTagScanned("04:A2:19", verified = true), real.event)
        assertFalse((forged.event as TriggerEvent.NfcTagScanned).verified)
        assertNotEquals(real.id, forged.id)
        assertFalse("04:A2" in real.event.toString())
        assertFalse("04:A2" in real.id.value)
        assertNull(parser.nfcTag("", verified = true, atMillis = 1_000))
        assertNull(parser.nfcTag("04\u0000", verified = true, atMillis = 1_000))
    }

    // --- next_alarm -----------------------------------------------------------------------------

    @Test
    fun `next alarm offset is 0 to 180 minutes`() {
        assertEquals(emptyList(), errors(draft(Trigger.NextAlarm(0))))
        assertEquals(emptyList(), errors(draft(Trigger.NextAlarm(NextAlarmTriggerPolicy.MAX_OFFSET_MINUTES))))
        assertEquals(listOf("next_alarm_offset_invalid"), errors(draft(Trigger.NextAlarm(-1))))
        assertEquals(listOf("next_alarm_offset_invalid"), errors(draft(Trigger.NextAlarm(181))))
        // Qualunque app può impostare la sveglia: niente shell.
        assertTrue("shell_external_trigger" in errors(draft(Trigger.NextAlarm(10), listOf(Action.RunShell("true")))))
    }

    // --- app_usage ------------------------------------------------------------------------------

    @Test
    fun `app usage needs a package and 1 to 1440 minutes`() {
        assertEquals(emptyList(), errors(draft(Trigger.AppUsage("com.google.android.youtube", 60))))
        assertEquals(emptyList(), errors(draft(Trigger.AppUsage("com.google.android.youtube", 1440))))
        assertEquals(listOf("app_usage_minutes_invalid"), errors(draft(Trigger.AppUsage("com.example.game", 0))))
        assertEquals(listOf("app_usage_minutes_invalid"), errors(draft(Trigger.AppUsage("com.example.game", 1441))))
        assertEquals(listOf("pkg_blank"), errors(draft(Trigger.AppUsage("", 30))))
        assertEquals(listOf("package_invalid"), errors(draft(Trigger.AppUsage("not a package", 30))))
        assertTrue("shell_external_trigger" in errors(draft(Trigger.AppUsage("com.example.game", 30), listOf(Action.RunShell("true")))))
    }

    @Test
    fun `app usage fires at the crossing of its own threshold only`() {
        val matcher = TriggerMatcher()
        val rule = Trigger.AppUsage("com.example.game", 30)
        assertTrue(matcher.matches(rule, TriggerEvent.AppUsageChanged("com.example.game", 29, 30)))
        assertFalse(matcher.matches(rule, TriggerEvent.AppUsageChanged("com.example.game", 30, 31)))
        assertFalse(matcher.matches(rule, TriggerEvent.AppUsageChanged("com.example.other", 29, 30)))
        assertFalse(matcher.matches(Trigger.AppUsage("com.example.game", 60), TriggerEvent.AppUsageChanged("com.example.game", 29, 30)))
    }

    // --- sensori ------------------------------------------------------------------------------

    @Test
    fun `shake and flip are one-shot gestures with the sensor cooldown`() {
        listOf(SensorKind.SHAKE, SensorKind.FLIP_FACE_DOWN).forEach { kind ->
            assertEquals(emptyList(), errors(draft(Trigger.Sensor(kind), cooldownMs = 60_000)), kind.wireName)
            assertEquals(listOf("sensor_cooldown_invalid"), errors(draft(Trigger.Sensor(kind), cooldownMs = 5_000)))
            assertEquals(
                listOf("sensor_event_count_invalid"),
                errors(draft(Trigger.Sensor(kind, minimumEventCount = 2), cooldownMs = 60_000)),
            )
        }
    }

    // --- geofence DWELL ----------------------------------------------------------------------

    private fun geofence(transition: Transition, loiteringDelayMs: Long) = Trigger.Geofence(
        lat = 45.4642,
        lng = 9.19,
        radiusM = 150.0,
        transition = transition,
        loiteringDelayMs = loiteringDelayMs,
    )

    @Test
    fun `dwell needs a delay between 1 and 120 minutes`() {
        assertEquals(emptyList(), errors(draft(geofence(Transition.DWELL, 60_000))))
        assertEquals(emptyList(), errors(draft(geofence(Transition.DWELL, GeofenceDwellPolicy.MAX_DELAY_MS))))
        assertEquals(listOf("loitering_invalid"), errors(draft(geofence(Transition.DWELL, 0))))
        assertEquals(listOf("loitering_invalid"), errors(draft(geofence(Transition.DWELL, 59_999))))
        assertEquals(listOf("loitering_invalid"), errors(draft(geofence(Transition.DWELL, 120 * 60_000L + 1))))
        // ENTER ed EXIT non hanno attesa.
        assertEquals(listOf("geofence_loitering_unsupported"), errors(draft(geofence(Transition.ENTER, 60_000))))
        assertEquals(emptyList(), errors(draft(geofence(Transition.EXIT, 0))))
        // Il dwell resta un'identità fisica come ENTER/EXIT.
        assertTrue(StaticShellSafety.allows(geofence(Transition.DWELL, 600_000), emptySet()))
    }

    @Test
    fun `a dwell event matches only a dwell rule`() {
        val matcher = TriggerMatcher()
        val id = AutomationId("g")
        val fingerprint = dev.argus.engine.model.ApprovalFingerprint("a".repeat(64))
        assertTrue(matcher.matches(geofence(Transition.DWELL, 600_000), TriggerEvent.GeofenceTransitioned(id, Transition.DWELL, fingerprint)))
        assertFalse(matcher.matches(geofence(Transition.DWELL, 600_000), TriggerEvent.GeofenceTransitioned(id, Transition.ENTER, fingerprint)))
        assertFalse(matcher.matches(geofence(Transition.ENTER, 0), TriggerEvent.GeofenceTransitioned(id, Transition.DWELL, fingerprint)))
    }
}
