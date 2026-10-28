package dev.argus.e2e

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * L'evento sintetico che l'aggancio consegna al motore deve combaciare con il trigger della regola
 * (altrimenti la suite proverebbe il vuoto) e non con gli altri. Il matcher è quello vero.
 */
class E2eEventFactoryTest {
    private val matcher = TriggerMatcher()
    private val factory = E2eEventFactory(nowMillis = { 1_000_000L })

    private fun rule(trigger: Trigger) = Automation(
        id = AutomationId("rule-1"),
        name = "e2e/test",
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = trigger,
        actions = listOf(Action.ShowNotification("t", "x")),
        approvalFingerprint = ApprovalFingerprint("a".repeat(64)),
    )

    private val triggers: List<Trigger> = listOf(
        Trigger.Time(cron = "0 9 * * *", tz = "Europe/Rome"),
        Trigger.Time(afterMs = 5_000, tz = "Europe/Rome", precision = TimePrecision.EXACT),
        Trigger.Immediate,
        Trigger.Manual,
        Trigger.Geofence(lat = 45.0, lng = 9.0, radiusM = 200.0, transition = Transition.EXIT),
        Trigger.Notification(pkg = "com.example.chat", conversationId = "c1", isGroup = false, textMatch = "ciao"),
        Trigger.Notification(pkg = "com.example.chat", sender = "Mario", titleMatch = "Mario"),
        Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = "+393331112222", textMatch = "otp"),
        Trigger.PhoneState(PhoneEvent.INCOMING_CALL),
        Trigger.PhoneState(PhoneEvent.MISSED_CALL, number = "+393331112222"),
        Trigger.PhoneState(PhoneEvent.CALL_ENDED),
        Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, match = "home"),
        Trigger.Connectivity(ConnMedium.POWER, ConnState.DISCONNECTED),
        Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED),
        Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
        Trigger.Screen(ScreenEvent.UNLOCKED),
        Trigger.Screen(ScreenEvent.OFF),
        Trigger.Battery(below = 20),
        Trigger.Battery(above = 80),
        Trigger.Battery(below = 1),
        Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED),
        Trigger.AudioOutput(AudioOutputKind.BLUETOOTH, ConnState.DISCONNECTED),
        Trigger.ModeChanged(ModeKind.RINGER),
        Trigger.ModeChanged(ModeKind.DND, to = "total"),
        Trigger.Media(MediaPlayState.PLAYING),
        Trigger.AppEvent("com.android.calculator2", AppEventKind.CLOSED),
    )

    @Test
    fun `the default synthetic event matches the trigger of every kind`() {
        triggers.forEach { trigger ->
            val built = factory.build(rule(trigger), JsonObject(emptyMap()))
            assertIs<E2eEventResult.Built>(built, "nessun evento per $trigger")
            assertEquals(triggerTypeOf(trigger), built.type)
            assertTrue(matcher.matches(trigger, built.envelope.event), "l'evento non combacia con $trigger")
        }
    }

    @Test
    fun `an event of another type does not match`() {
        val screenRule = rule(Trigger.Screen(ScreenEvent.ON))
        val built = factory.build(screenRule, buildJsonObject { put("as", "media") })
        assertIs<E2eEventResult.Built>(built)
        assertFalse(matcher.matches(screenRule.trigger, built.envelope.event))
    }

    @Test
    fun `payload fields turn a matching event into a non matching one`() {
        val phone = rule(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = "+393331112222", textMatch = "otp"))
        val wrongText = factory.build(phone, buildJsonObject { put("text", "nothing here") })
        assertIs<E2eEventResult.Built>(wrongText)
        assertFalse(matcher.matches(phone.trigger, wrongText.envelope.event))

        val battery = rule(Trigger.Battery(below = 20))
        val noCross = factory.build(
            battery,
            buildJsonObject {
                put("previous", 50)
                put("current", 40)
            },
        )
        assertIs<E2eEventResult.Built>(noCross)
        assertFalse(matcher.matches(battery.trigger, noCross.envelope.event))
    }

    @Test
    fun `the same instant gives the same event id and distinct instants give distinct ids`() {
        val screen = rule(Trigger.Screen(ScreenEvent.ON))
        fun id(at: Long?): String {
            val built = factory.build(screen, buildJsonObject { if (at != null) put("at", at) })
            return (built as E2eEventResult.Built).envelope.id.value
        }
        assertEquals(id(42), id(42))
        assertNotEquals(id(42), id(43))
        assertNotEquals(id(null), id(null))
    }

    @Test
    fun `registered events carry the approved fingerprint and the rule id`() {
        val manual = rule(Trigger.Manual)
        val built = factory.build(manual, JsonObject(emptyMap()))
        assertIs<E2eEventResult.Built>(built)
        val event = built.envelope.event
        assertIs<TriggerEvent.ManualFired>(event)
        assertEquals(manual.id, event.automationId)
        assertEquals(manual.approvalFingerprint, event.approvalFingerprint)
    }

    @Test
    fun `a rule that is not approved yields no registered event`() {
        val unapproved = rule(Trigger.Manual).copy(approvalFingerprint = null)
        val built = factory.build(unapproved, JsonObject(emptyMap()))
        assertIs<E2eEventResult.Invalid>(built)
    }

    @Test
    fun `unknown event types and bad enum names are refused without throwing`() {
        val screen = rule(Trigger.Screen(ScreenEvent.ON))
        assertIs<E2eEventResult.Invalid>(factory.build(screen, buildJsonObject { put("as", "teleport") }))
        assertIs<E2eEventResult.Invalid>(factory.build(screen, buildJsonObject { put("event", "SIDEWAYS") }))
    }

    @Test
    fun `a null group flag is delivered as unknown so the group filters fail closed`() {
        val chat = rule(Trigger.Notification(pkg = "com.example.chat", conversationId = "c1", isGroup = false))
        val built = factory.build(chat, JsonObject(mapOf("isGroup" to JsonNull)))
        assertIs<E2eEventResult.Built>(built)
        assertFalse(matcher.matches(chat.trigger, built.envelope.event))
    }
}
