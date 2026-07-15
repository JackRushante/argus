package dev.argus.automation.phone

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

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
}
