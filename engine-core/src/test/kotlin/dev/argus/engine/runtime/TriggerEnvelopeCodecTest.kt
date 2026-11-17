package dev.argus.engine.runtime

import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Transition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8 `confirm`: l'evento in attesa di conferma torna identico, per ogni tipo di evento. */
class TriggerEnvelopeCodecTest {
    private val id = AutomationId("r1")
    private val fp = ApprovalFingerprint("b".repeat(64))

    private val events: List<TriggerEvent> = listOf(
        TriggerEvent.TimeFired(id, fp),
        TriggerEvent.ImmediateFired(id, fp),
        TriggerEvent.ManualFired(id, fp),
        TriggerEvent.NextAlarmFired(id, fp),
        TriggerEvent.GeofenceTransitioned(id, Transition.DWELL, fp),
        TriggerEvent.SensorChanged(id, SensorKind.SHAKE, fp),
        TriggerEvent.CalendarEventFired(id, CalendarPhase.END, fp),
        // Il titolo dell'evento alimenta il payload TITLE: un'esecuzione confermata lo deve ritrovare.
        TriggerEvent.CalendarEventFired(id, CalendarPhase.START, fp, title = "Riunione \"Q4\""),
        TriggerEvent.NotificationPosted("com.whatsapp", "jid@s", "Anna", "Anna", "Ci vediamo \"alle\" 8\n", false, "0|k"),
        TriggerEvent.NotificationPosted("com.example"),
        TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+39333", "Codice 123", sim = 1),
        TriggerEvent.PhoneStateChanged(PhoneEvent.MISSED_CALL, null),
        TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
        TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.DISCONNECTED, null),
        TriggerEvent.ScreenChanged(ScreenEvent.UNLOCKED),
        TriggerEvent.BatteryLevelChanged(21, 19),
        TriggerEvent.AudioOutputChanged(AudioOutputKind.WIRED, ConnState.CONNECTED),
        TriggerEvent.DeviceModeChanged(ModeKind.RINGER, "silent", null),
        TriggerEvent.MediaPlaybackChanged(MediaPlayState.PLAYING),
        TriggerEvent.AppForegroundChanged("com.example.game", AppEventKind.OPENED),
        TriggerEvent.NfcTagScanned("04:A2"),
        TriggerEvent.NfcTagScanned("04:A2", verified = true),
        TriggerEvent.AppUsageChanged("com.example.game", 59, 60),
        TriggerEvent.UserModeChanged("casa", "lavoro"),
        // 0.8: un cambio fatto da una regola di modalità resta concatenato anche dopo il riavvio.
        TriggerEvent.UserModeChanged("casa", null, chained = true),
    )

    @Test
    fun `every event kind round trips`() {
        events.forEachIndexed { index, event ->
            val envelope = TriggerEnvelope(TriggerEventId("e:$index"), event)
            assertEquals(envelope, TriggerEnvelopeCodec.decode(TriggerEnvelopeCodec.encode(envelope)), "$event")
        }
    }

    @Test
    fun `an nfc scan keeps its verified flag and never gains one`() {
        fun decoded(text: String) = assertIs<TriggerEvent.NfcTagScanned>(TriggerEnvelopeCodec.decode(text)?.event)
        fun roundTrip(event: TriggerEvent.NfcTagScanned) =
            decoded(TriggerEnvelopeCodec.encode(TriggerEnvelope(TriggerEventId("e"), event)))

        assertTrue(roundTrip(TriggerEvent.NfcTagScanned("04:A2", verified = true)).verified)
        assertFalse(roundTrip(TriggerEvent.NfcTagScanned("04:A2", verified = false)).verified)
        // Un evento salvato senza il flag (o col flag a null) resta non verificato: il default è il caso peggiore.
        assertFalse(decoded("""{"id":"e","event":{"type":"nfc","tagId":"04:A2"}}""").verified)
        assertFalse(decoded("""{"id":"e","event":{"type":"nfc","tagId":"04:A2","verified":null}}""").verified)
        // Un flag che non è un booleano JSON non promuove nulla: l'evento non si decodifica.
        listOf("\"true\"", "1", "\"yes\"", "{}").forEach { flag ->
            assertNull(
                TriggerEnvelopeCodec.decode("""{"id":"e","event":{"type":"nfc","tagId":"04:A2","verified":$flag}}"""),
                flag,
            )
        }
    }

    @Test
    fun `anything malformed decodes to nothing`() {
        listOf(
            "",
            "[]",
            """{"id":"e"}""",
            """{"id":"e","event":{"type":"unknown"}}""",
            """{"id":"e","event":{"type":"screen","event":"SIDEWAYS"}}""",
            """{"id":"e","event":{"type":"battery","previous":"21","current":19}}""",
            """{"id":"","event":{"type":"screen","event":"ON"}}""",
            """{"id":"e","event":{"type":"time","automationId":"r1"}}""",
        ).forEach { assertNull(TriggerEnvelopeCodec.decode(it), it) }
    }
}
