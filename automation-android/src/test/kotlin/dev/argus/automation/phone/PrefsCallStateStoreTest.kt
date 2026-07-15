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
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class PrefsCallStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = PrefsCallStateStore(context)

    @After
    fun clear() {
        context.getSharedPreferences("argus_phone_state", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `snapshot survives a new store instance`() {
        val snapshot = CallStateSnapshot("RINGING", "+3932077480", 12_345L)
        store.record(snapshot)

        assertEquals(snapshot, PrefsCallStateStore(context).last())
    }

    @Test
    fun `idle snapshot removes the transient phone number`() {
        store.record(CallStateSnapshot("RINGING", "+3932077480", 12_345L))
        store.record(CallStateSnapshot("IDLE", null, 13_000L))

        assertEquals(CallStateSnapshot("IDLE", null, 13_000L), store.last())
    }

    @Test
    fun `pending call event retains only bounded call metadata until completion`() {
        val envelope = TriggerEnvelope(
            TriggerEventId("phone:${"d".repeat(64)}"),
            TriggerEvent.PhoneStateChanged(PhoneEvent.CALL_ENDED, "+3932077480"),
        )
        store.record(CallStateSnapshot("IDLE", null, 13_000L), envelope)

        PrefsCallStateStore(context).apply {
            assertEquals(envelope, pending())
            assertNull(last()?.number)
            complete(envelope.id.value)
            assertNull(pending())
        }
    }
}
