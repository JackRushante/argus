package dev.argus.automation.phone

import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals("3932077480", events.last().number)
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
    fun `anonymous then numbered ringing enriches the same execution identity`() = runTest {
        // PHONE_STATE arriva due volte (con e senza numero, ordine non garantito). Il secondo
        // envelope permette alle regole filtrate di fare match, ma conserva lo stesso event id:
        // una regola generica viene quindi claimata una sola volta dall'engine.
        ingress.onCallStateChanged("RINGING", number = null, atMillis = 1_000L)
        ingress.onCallStateChanged("RINGING", number = "3932077480", atMillis = 1_050L)
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 5_000L)
        ingress.onCallStateChanged("IDLE", number = null, atMillis = 5_050L)

        val events = dispatched.map { it.event as TriggerEvent.PhoneStateChanged }
        assertEquals(
            listOf(PhoneEvent.INCOMING_CALL, PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED),
            events.map { it.event },
        )
        assertNull(events[0].number)
        assertEquals("3932077480", events[1].number)
        assertEquals("3932077480", events[2].number)
        assertEquals(dispatched[0].id, dispatched[1].id)
    }

    @Test
    fun `numbered then anonymous duplicate is ignored`() = runTest {
        ingress.onCallStateChanged("RINGING", number = "3932077480", atMillis = 1_000L)
        ingress.onCallStateChanged("RINGING", number = null, atMillis = 1_050L)

        assertEquals(1, dispatched.size)
        val event = dispatched.single().event as TriggerEvent.PhoneStateChanged
        assertEquals("3932077480", event.number)
    }

    @Test
    fun `stale persisted ringing never creates a false call ended`() = runTest {
        callState.record(CallStateSnapshot("RINGING", "3932077480", 1L))

        ingress.onCallStateChanged(
            "IDLE",
            number = null,
            atMillis = 24 * 60 * 60 * 1_000L + 2L,
        )

        assertTrue(dispatched.isEmpty())
        assertEquals(CallStateSnapshot("IDLE", null, 24 * 60 * 60 * 1_000L + 2L), callState.last())
    }

    @Test
    fun `future persisted ringing after clock rollback never creates a false call ended`() =
        runTest {
            callState.record(CallStateSnapshot("RINGING", "3932077480", 10_000L))

            ingress.onCallStateChanged("IDLE", number = null, atMillis = 9_000L)

            assertTrue(dispatched.isEmpty())
            assertEquals(CallStateSnapshot("IDLE", null, 9_000L), callState.last())
        }

    @Test
    fun `empty sms bodies are dropped`() = runTest {
        ingress.onSms(listOf(SmsPart(sender = "+39001", body = "   ", atMillis = 1L)))
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `failed call dispatch survives process death with the exact same event id`() = runTest {
        var failedId: String? = null
        val failing = PhoneEventIngress(PhoneEventParser(), callState) { envelope ->
            failedId = envelope.id.value
            error("process dies")
        }

        assertFailsWith<IllegalStateException> {
            failing.onCallStateChanged("RINGING", "3932077480", 1_000L)
        }
        assertEquals(failedId, callState.pending()?.id?.value)

        val retried = mutableListOf<TriggerEnvelope>()
        val restarted = PhoneEventIngress(PhoneEventParser(), callState) { retried += it }
        assertTrue(restarted.recoverPending())
        assertEquals(failedId, retried.single().id.value)
        assertNull(callState.pending())
    }
}

private class InMemoryCallStateStore : CallStateStore {
    private var value: CallStateSnapshot? = null
    private var pending: TriggerEnvelope? = null
    override fun last(): CallStateSnapshot? = value
    override fun pending(): TriggerEnvelope? = pending
    override fun record(snapshot: CallStateSnapshot, pending: TriggerEnvelope?) {
        value = snapshot
        this.pending = pending
    }
    override fun complete(eventId: String) {
        require(pending?.id?.value == eventId)
        pending = null
    }
}
