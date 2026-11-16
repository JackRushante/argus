package dev.argus.automation.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    @Test
    fun `the ledger survives a new store and never holds the number`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = PrefsTelephonyLedgerStore(context)
        val key = first.destinationKey("+393331112222")
        first.write(TelephonyLedger(sms = listOf(5L to key), calls = listOf(7L, 9L)))

        val second = PrefsTelephonyLedgerStore(context)
        assertEquals(TelephonyLedger(sms = listOf(5L to key), calls = listOf(7L, 9L)), second.read())
        assertEquals(key, second.destinationKey("+393331112222"))
        assertNotEquals(key, second.destinationKey("+393331112223"))
        assertFalse("3331112222" in key)
        val raw = context.getSharedPreferences("argus_telephony_rate", Context.MODE_PRIVATE).all.values
        assertTrue(raw.none { it.toString().contains("3331112222") })
    }

    @Test
    fun `an empty store reads as an empty ledger`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("argus_telephony_rate", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(TelephonyLedger(), PrefsTelephonyLedgerStore(context).read())
    }
}
