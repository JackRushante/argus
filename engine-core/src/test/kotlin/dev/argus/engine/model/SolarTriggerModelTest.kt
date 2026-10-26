package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Variante solare del trigger `time` (0.4.1): wire, round-trip e impronte delle regole esistenti. */
class SolarTriggerModelTest {
    private fun encode(trigger: Trigger) = ArgusJson.encodeToString(Trigger.serializer(), trigger)
    private fun decode(json: String) = ArgusJson.decodeFromString(Trigger.serializer(), json)

    @Test
    fun `an unlocated solar spec has exactly the wire shape of the registry`() {
        val trigger = Trigger.Time(
            tz = "Europe/Rome",
            solar = SolarSpec(SolarEvent.SUNSET, offsetMinutes = -30),
        )

        val wire = encode(trigger)

        assertEquals(
            """{"type":"time","cron":null,"at":null,"tz":"Europe/Rome","precision":"FLEXIBLE",""" +
                """"solar":{"event":"SUNSET","offsetMinutes":-30}}""",
            wire,
        )
        assertEquals(trigger, decode(wire))
        assertFalse(trigger.solar!!.located)
    }

    @Test
    fun `a located solar spec round-trips with its coordinates`() {
        val trigger = Trigger.Time(
            tz = "Europe/Rome",
            solar = SolarSpec(SolarEvent.SUNRISE, 15, lat = 41.9, lng = 12.5),
            precision = TimePrecision.EXACT,
        )

        val wire = encode(trigger)

        assertTrue(""""solar":{"event":"SUNRISE","offsetMinutes":15,"lat":41.9,"lng":12.5}""" in wire, wire)
        assertEquals(trigger, decode(wire))
        assertTrue(trigger.solar!!.located)
    }

    @Test
    fun `the model may leave the offset out and ignore coordinates it was never given`() {
        val decoded = decode("""{"type":"time","tz":"UTC","solar":{"event":"SUNRISE"}}""") as Trigger.Time

        assertEquals(SolarSpec(SolarEvent.SUNRISE, 0), decoded.solar)
    }

    @Test
    fun `a time trigger without solar keeps its bytes and the pinned approval fingerprint`() {
        val trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome")
        val wire = encode(trigger)
        assertFalse("solar" in wire, wire)
        assertEquals(
            """{"type":"time","cron":"0 23 * * *","at":null,"tz":"Europe/Rome","precision":"FLEXIBLE"}""",
            wire,
        )
        // Stesso fixture e stesso hash PINNATO di V1FingerprintCompatibilityTest ("time-all-conditions-actions").
        val automation = Automation(
            id = AutomationId("v1-time"),
            name = "v1-time",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = decode(wire),
            conditions = Condition.And(
                listOf(
                    Condition.TimeWindow("22:00", "07:00", "Europe/Rome"),
                    Condition.Or(
                        listOf(
                            Condition.StateEquals(StateKeys.BATTERY, CmpOp.GT, "20"),
                            Condition.Not(Condition.AppInForeground("dev.example.app")),
                        ),
                    ),
                    Condition.LocationIn(45.0, 9.0, 100.0),
                ),
            ),
            actions = listOf(
                Action.SetWifi(true),
                Action.SetBluetooth(false),
                Action.SetDnd(DndMode.PRIORITY),
                Action.SetRinger("vibrate"),
                Action.LaunchApp("dev.example.app"),
                Action.OpenUrl("https://example.org/path"),
                Action.ShowNotification("titolo", "testo"),
                Action.Tap(10, 20),
                Action.InputText("testo statico"),
                Action.WhatsAppReply("risposta statica"),
                Action.RunShell("id"),
                Action.CopyToClipboard("([0-9]{4})"),
                Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true),
            ),
            enabled = false,
            priority = 3,
            cooldownMs = 12_345,
        )
        assertEquals(
            "bfb074706c51d70975894c379a00df4636bc5b0729d074fe418c4f4aa8fb858c",
            ApprovalFingerprints.of(automation).value,
        )
    }

    @Test
    fun `a stored time trigger from before solar decodes with no solar spec`() {
        val old = """{"type":"time","cron":null,"at":"2026-07-15T08:00","tz":"UTC","precision":"EXACT"}"""
        val decoded = decode(old) as Trigger.Time

        assertEquals(null, decoded.solar)
        assertEquals(old, encode(decoded))
    }

    @Test
    fun `the located spec changes the approval fingerprint of the rule`() {
        fun fingerprint(spec: SolarSpec) = ApprovalFingerprints.of(
            Automation(
                id = AutomationId("solar"),
                name = "solar",
                createdBy = CreatedBy.LLM,
                status = AutomationStatus.PENDING_APPROVAL,
                trigger = Trigger.Time(tz = "UTC", solar = spec),
                actions = listOf(Action.SetFlashlight(true)),
                enabled = false,
                priority = 0,
                cooldownMs = 0,
            ),
        )
        val unlocated = fingerprint(SolarSpec(SolarEvent.SUNSET, -30))
        val located = fingerprint(SolarSpec(SolarEvent.SUNSET, -30, 45.4, 9.2))

        assertTrue(unlocated != located)
        assertTrue(located != fingerprint(SolarSpec(SolarEvent.SUNSET, -30, 45.5, 9.2)))
        assertTrue(located != fingerprint(SolarSpec(SolarEvent.SUNRISE, -30, 45.4, 9.2)))
    }

    @Test
    fun `coordinates are coarsened to a tenth of a degree`() {
        assertEquals(45.5, SolarTriggerPolicy.coarsen(45.4642))
        assertEquals(9.2, SolarTriggerPolicy.coarsen(9.1859))
        assertEquals(-33.9, SolarTriggerPolicy.coarsen(-33.8688))
        assertEquals(-157.9, SolarTriggerPolicy.coarsen(-157.8583))
        assertEquals(12.5, SolarTriggerPolicy.coarsen(12.4964))
    }
}
