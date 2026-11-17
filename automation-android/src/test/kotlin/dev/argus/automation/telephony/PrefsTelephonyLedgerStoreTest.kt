package dev.argus.automation.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsTelephonyLedgerStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("argus_telephony_rate", Context.MODE_PRIVATE)
    private val clock = TelephonyClock { ClockReading(9_000L, 3, 500L) }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `the ledger survives a new store and never holds the number`() {
        val first = PrefsTelephonyLedgerStore(context, clock)
        val key = first.destinationKey("+393331112222")
        val ledger = TelephonyLedger(
            sms = listOf(TelephonySend(ClockReading(5L, 2, 40L), key)),
            calls = listOf(TelephonySend(ClockReading(7L, 2, 41L)), TelephonySend(ClockReading(9L, UNKNOWN_BOOT, 0L))),
        )
        assertTrue(first.write(ledger))

        val second = PrefsTelephonyLedgerStore(context, clock)
        assertEquals(ledger, second.read())
        assertEquals(key, second.destinationKey("+393331112222"))
        assertNotEquals(key, second.destinationKey("+393331112223"))
        assertFalse("3331112222" in key)
        assertTrue(prefs.all.values.none { it.toString().contains("3331112222") })
    }

    @Test
    fun `the same number written two ways is one destination`() {
        val store = PrefsTelephonyLedgerStore(context, clock)
        assertEquals(store.destinationKey("+393331112222"), store.destinationKey("3331112222"))
        assertEquals(store.destinationKey("+393331112222"), store.destinationKey("00393331112222"))
    }

    @Test
    fun `an empty store reads as an empty ledger`() {
        assertEquals(TelephonyLedger(), PrefsTelephonyLedgerStore(context, clock).read())
    }

    @Test
    fun `an unreadable entry counts as a send made just now`() {
        prefs.edit().putString("sms_v2", "garbage").putString("calls_v2", "1:2").commit()
        val ledger = PrefsTelephonyLedgerStore(context, clock).read()
        assertEquals(listOf(TelephonySend(ClockReading(9_000L, UNKNOWN_BOOT, 500L), "0")), ledger.sms)
        assertEquals(listOf(TelephonySend(ClockReading(9_000L, UNKNOWN_BOOT, 500L))), ledger.calls)
    }
}
