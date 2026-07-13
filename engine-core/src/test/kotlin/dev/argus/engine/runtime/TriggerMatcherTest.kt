package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class TriggerMatcherTest {
    private val m = TriggerMatcher()

    @Test fun `notification conversationId takes precedence over display name`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", conversationId = "jid:42", sender = "Moglie")
        // conversationId giusto ma display name diverso (rinominato/spoof) -> match comunque
        assertTrue(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", conversationId = "jid:42", sender = "Chiunque")))
        // display name giusto ma conversationId diverso (spoof del nome) -> NO match
        assertFalse(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", conversationId = "jid:666", sender = "Moglie")))
    }
    @Test fun `notification falls back to sender when no conversationId in spec`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", sender = "Moglie")
        assertTrue(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", sender = "Moglie")))
        assertFalse(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", sender = "Capo")))
    }
    @Test fun `notification isGroup filter and case-insensitive textMatch`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", isGroup = false, textMatch = "ciao")
        assertTrue(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", text = "CIAO amore", isGroup = false)))
        assertFalse(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", text = "ciao", isGroup = true)))
    }
    @Test fun `time matches by construction`() {
        assertTrue(m.matches(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), TriggerEvent.TimeFired(AutomationId("a1"))))
    }
    @Test fun `connectivity matches medium state and name`() {
        val spec = Trigger.Connectivity(ConnMedium.WIFI, ConnState.DISCONNECTED, match = "Casa")
        assertTrue(m.matches(spec, TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa")))
        assertFalse(m.matches(spec, TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa")))
        assertFalse(m.matches(spec, TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.DISCONNECTED, "Casa")))
    }
    @Test fun `phone numbers match across formats`() {
        val spec = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+39 320 000 0000")
        assertTrue(m.matches(spec, TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, "3200000000")))
        assertFalse(m.matches(spec, TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, "3331112223")))
    }
}
