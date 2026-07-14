package dev.argus.automation.phone

import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneEventIngressTest {
    private val dispatched = mutableListOf<TriggerEnvelope>()
    private val callState = InMemoryCallStateStore()
    private val ingress = PhoneEventIngress(
        parser = PhoneEventParser(),
        callState = callState,
        dispatcher = { envelope -> dispatched += envelope },
    )

    @Test
    fun `multipart sms is recomposed per sender before dispatch`() = runTest {
        ingress.onSms(
            listOf(
                SmsPart(sender = "+39001", body = "Il tuo codice ", atMillis = 1_000L),
                SmsPart(sender = "+39001", body = "è 482913", atMillis = 1_000L),
                SmsPart(sender = "+39002", body = "altro mittente", atMillis = 1_001L),
            ),
        )

        assertEquals(2, dispatched.size)
        val first = dispatched.map { it.event }.filterIsInstance<TriggerEvent.PhoneStateChanged>()
            .single { it.number == "+39001" }
        assertEquals(PhoneEvent.SMS_RECEIVED, first.event)
        assertEquals("Il tuo codice è 482913", first.smsText)
        // Il testo non entra mai in chiaro negli event id.
        assertTrue(dispatched.none { it.id.value.contains("482913") })
    }

    @Test
    fun `ringing then idle produces incoming call and call ended once`() = runTest {
        ingress.onCallStateChanged("RINGING", number = "3932077480", atMillis = 1_000L)
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 2_000L)

        val events = dispatched.map { it.event }.filterIsInstance<TriggerEvent.PhoneStateChanged>()
        assertEquals(listOf(PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED), events.map { it.event })
        assertEquals("3932077480", events.first().number)
        assertNull(events.last().smsText)
    }

    @Test
    fun `idle without a preceding call never fires and unknown states are ignored`() = runTest {
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 1_000L)
        ingress.onCallStateChanged("SOMETHING_NEW", number = null, atMillis = 2_000L)

        assertTrue(dispatched.isEmpty())

        // OFFHOOK (risposta a una chiamata) → IDLE = chiamata terminata.
        ingress.onCallStateChanged("OFFHOOK", number = null, atMillis = 3_000L)
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 4_000L)
        val events = dispatched.map { it.event }.filterIsInstance<TriggerEvent.PhoneStateChanged>()
        assertEquals(listOf(PhoneEvent.CALL_ENDED), events.map { it.event })
    }

    @Test
    fun `duplicate framework broadcasts of the same call state fire once`() = runTest {
        // PHONE_STATE arriva due volte (con e senza numero, a seconda dei grant): una sola
        // transizione RINGING deve produrre UN solo evento, non uno per broadcast.
        ingress.onCallStateChanged("RINGING", number = null, atMillis = 1_000L)
        ingress.onCallStateChanged("RINGING", number = "3932077480", atMillis = 1_050L)
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 5_000L)
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 5_050L)

        val events = dispatched.map { it.event }.filterIsInstance<TriggerEvent.PhoneStateChanged>()
        assertEquals(listOf(PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED), events.map { it.event })
    }

    @Test
    fun `empty sms bodies are dropped`() = runTest {
        ingress.onSms(listOf(SmsPart(sender = "+39001", body = "   ", atMillis = 1L)))
        assertTrue(dispatched.isEmpty())
    }
}

private class InMemoryCallStateStore : CallStateStore {
    private var value: String? = null
    override fun lastState(): String? = value
    override fun record(state: String) {
        value = state
    }
}
