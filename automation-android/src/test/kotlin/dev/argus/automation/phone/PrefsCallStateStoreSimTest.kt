package dev.argus.automation.phone

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 0.4.1: slot SIM e chiamata persa nello stato persistente delle chiamate. */
@RunWith(RobolectricTestRunner::class)
class PrefsCallStateStoreSimTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = PrefsCallStateStore(context)

    @After
    fun clear() {
        context.getSharedPreferences("argus_phone_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `the slot of the ongoing call survives a new store instance`() {
        val snapshot = CallStateSnapshot("RINGING", "+3932077480", 12_345L, sim = 2)
        store.record(snapshot)

        assertEquals(snapshot, PrefsCallStateStore(context).last())
    }

    @Test
    fun `a snapshot without slot reads back as unknown`() {
        store.record(CallStateSnapshot("RINGING", null, 1L))
        assertNull(PrefsCallStateStore(context).last()?.sim)
        // Un valore fuori intervallo scritto da altrove non diventa mai uno slot.
        context.getSharedPreferences("argus_phone_state", android.content.Context.MODE_PRIVATE)
            .edit().putInt("last_call_sim", 7).commit()
        assertNull(PrefsCallStateStore(context).last()?.sim)
    }

    @Test
    fun `a pending missed call keeps its number and slot until it is completed`() {
        val envelope = TriggerEnvelope(
            TriggerEventId("phone:${"e".repeat(64)}"),
            TriggerEvent.PhoneStateChanged(PhoneEvent.MISSED_CALL, "+3932077480", sim = 1),
        )
        store.record(CallStateSnapshot("IDLE", null, 13_000L), envelope)

        PrefsCallStateStore(context).apply {
            assertEquals(envelope, pending())
            complete(envelope.id.value)
            assertNull(pending())
        }
    }

    @Test
    fun `a missed call can only be pending on an idle snapshot and the slot must be 1 or 2`() {
        val missed = TriggerEnvelope(
            TriggerEventId("phone:${"e".repeat(64)}"),
            TriggerEvent.PhoneStateChanged(PhoneEvent.MISSED_CALL, null),
        )
        assertFailsWith<IllegalArgumentException> {
            store.record(CallStateSnapshot("RINGING", null, 1L), missed)
        }
        val badSim = TriggerEnvelope(
            TriggerEventId("phone:${"f".repeat(64)}"),
            TriggerEvent.PhoneStateChanged(PhoneEvent.MISSED_CALL, null, sim = 3),
        )
        assertFailsWith<IllegalArgumentException> {
            store.record(CallStateSnapshot("IDLE", null, 1L), badSim)
        }
        assertFailsWith<IllegalArgumentException> {
            store.record(CallStateSnapshot("RINGING", null, 1L, sim = 0))
        }
    }
}
