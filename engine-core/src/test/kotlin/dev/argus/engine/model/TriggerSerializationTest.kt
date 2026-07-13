package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
