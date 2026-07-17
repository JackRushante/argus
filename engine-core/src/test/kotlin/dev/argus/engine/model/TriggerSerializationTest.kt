package dev.argus.engine.model
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
class TriggerSerializationTest {
    @Test fun `time trigger round-trips`() {
        val t: Trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome")
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `one-shot time trigger uses at`() {
        val t: Trigger = Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome")
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `relative-delay time trigger round-trips with afterMs`() {
        val t: Trigger = Trigger.Time(afterMs = 120_000, tz = "Europe/Rome")
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assert(json.contains("\"afterMs\":120000")) { json }
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `afterMs is omitted from the wire when null so legacy Time bytes are unchanged`() {
        // @EncodeDefault(NEVER): un Time cron/at NON deve emettere "afterMs":null, altrimenti i byte
        // cambierebbero e i fingerprint v1 pinnati si romperebbero.
        val cronTime: Trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome")
        val cronJson = ArgusJson.encodeToString(Trigger.serializer(), cronTime)
        assert(!cronJson.contains("afterMs")) { cronJson }
        val atTime: Trigger = Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome")
        assert(!ArgusJson.encodeToString(Trigger.serializer(), atTime).contains("afterMs"))
    }
    @Test fun `a JSON carrying afterMs decodes back into the field`() {
        val json = """{"type":"time","afterMs":120000,"tz":"Europe/Rome","precision":"FLEXIBLE"}"""
        val decoded = ArgusJson.decodeFromString(Trigger.serializer(), json) as Trigger.Time
        assertEquals(120_000L, decoded.afterMs)
        assertEquals(null, decoded.cron)
        assertEquals(null, decoded.at)
    }
    @Test fun `isOneShot is true for at and afterMs but false for cron`() {
        assert(Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome").isOneShot())
        assert(Trigger.Time(afterMs = 120_000, tz = "Europe/Rome").isOneShot())
        assert(!Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome").isOneShot())
    }
    @Test fun `time precision is explicit and legacy drafts remain flexible`() {
        val exact: Trigger = Trigger.Time(
            at = "2026-07-15T08:00",
            tz = "Europe/Rome",
            precision = TimePrecision.EXACT,
        )
        val encoded = ArgusJson.encodeToString(Trigger.serializer(), exact)
        assert(encoded.contains("\"precision\":\"EXACT\"")) { encoded }
        assertEquals(exact, ArgusJson.decodeFromString(Trigger.serializer(), encoded))

        val legacy = """{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"}"""
        assertEquals(
            TimePrecision.FLEXIBLE,
            (ArgusJson.decodeFromString(Trigger.serializer(), legacy) as Trigger.Time).precision,
        )
    }
    @Test fun `immediate trigger round-trips and keeps its discriminator`() {
        val t: Trigger = Trigger.Immediate
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assert(json.contains("\"type\":\"immediate\"")) { json }
        val decoded = ArgusJson.decodeFromString(Trigger.serializer(), json)
        assertEquals(Trigger.Immediate, decoded)
        assert(decoded === Trigger.Immediate) { "data object deve deserializzare al singleton" }
    }
    @Test fun `notification trigger keeps discriminator and identity fields`() {
        val t: Trigger = Trigger.Notification(pkg = "com.whatsapp", conversationId = "id:42", isGroup = false)
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assert(json.contains("\"type\":\"notification\"")) { json }
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `geofence with placeholder round-trips`() {
        val t: Trigger = Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true)
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), ArgusJson.encodeToString(Trigger.serializer(), t)))
    }

    @Test fun `sensor trigger has a closed lower-case kind and bounded parameters`() {
        val trigger: Trigger = Trigger.Sensor(SensorKind.STEP_COUNTER, minimumEventCount = 250)
        val json = ArgusJson.encodeToString(Trigger.serializer(), trigger)
        assert(json.contains("\"type\":\"sensor\"")) { json }
        assert(json.contains("\"kind\":\"step_counter\"")) { json }
        assertEquals(trigger, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }

    @Test fun `raw sensor kinds are not representable`() {
        assertFailsWith<SerializationException> {
            ArgusJson.decodeFromString<Trigger>(
                """{"type":"sensor","kind":"accelerometer_raw","minimumEventCount":1}""",
            )
        }
    }
}
