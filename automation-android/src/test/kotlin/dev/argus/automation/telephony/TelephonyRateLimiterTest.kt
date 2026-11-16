package dev.argus.automation.telephony

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Registro in memoria: lo stesso contratto del registro su SharedPreferences. */
internal class InMemoryLedgerStore : TelephonyLedgerStore {
    var ledger = TelephonyLedger()
    override fun read(): TelephonyLedger = ledger
    override fun write(ledger: TelephonyLedger) {
        this.ledger = ledger
    }
    override fun destinationKey(number: String): String = "k" + number.hashCode()
}

class TelephonyRateLimiterTest {
    private var now = 1_000_000L
    private val store = InMemoryLedgerStore()
    private val limiter = TelephonyRateLimiter(store) { now }

    @Test
    fun `three sms an hour to the same number, then the destination cap`() {
        repeat(3) { assertEquals(RateVerdict.Allowed, limiter.trySms("+393331112222")) }
        assertEquals(
            RateVerdict.Limited(TelephonyRateLimiter.SMS_DESTINATION_RATE_LIMITED),
            limiter.trySms("+393331112222"),
        )
        // Un altro numero passa ancora.
        assertEquals(RateVerdict.Allowed, limiter.trySms("+393334445555"))
    }

    @Test
    fun `ten sms an hour in all, whatever the numbers`() {
        repeat(10) { assertEquals(RateVerdict.Allowed, limiter.trySms("+39333000000$it")) }
        assertEquals(RateVerdict.Limited(TelephonyRateLimiter.SMS_RATE_LIMITED), limiter.trySms("+393339999999"))
        // Un blocco non conta come invio.
        assertEquals(10, store.ledger.sms.size)
    }

    @Test
    fun `the window slides after an hour`() {
        repeat(3) { limiter.trySms("+393331112222") }
        now += TelephonyRateLimiter.WINDOW_MILLIS - 1
        assertTrue(limiter.trySms("+393331112222") is RateVerdict.Limited)
        now += 1
        assertEquals(RateVerdict.Allowed, limiter.trySms("+393331112222"))
    }

    @Test
    fun `three calls an hour`() {
        repeat(3) { assertEquals(RateVerdict.Allowed, limiter.tryCall()) }
        assertEquals(RateVerdict.Limited(TelephonyRateLimiter.CALL_RATE_LIMITED), limiter.tryCall())
        now += TelephonyRateLimiter.WINDOW_MILLIS
        assertEquals(RateVerdict.Allowed, limiter.tryCall())
    }

    @Test
    fun `a clock moved back keeps the recent sends counted`() {
        repeat(3) { limiter.tryCall() }
        now -= 10 * 60 * 1_000L
        assertTrue(limiter.tryCall() is RateVerdict.Limited)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsTelephonyLedgerStoreTest {
    @Test
    fun `the ledger survives a new store and never holds the number`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = PrefsTelephonyLedgerStore(context)
        val key = first.destinationKey("+393331112222")
        first.write(TelephonyLedger(sms = listOf(5L to key), calls = listOf(7L, 9L)))

        val second = PrefsTelephonyLedgerStore(context)
        assertEquals(TelephonyLedger(sms = listOf(5L to key), calls = listOf(7L, 9L)), second.read())
        assertEquals(key, second.destinationKey("+393331112222"))
        assertNotEquals(key, second.destinationKey("+393331112223"))
        assertFalse("3331112222" in key)
        val raw = context.getSharedPreferences("argus_telephony_rate", android.content.Context.MODE_PRIVATE).all.values
        assertTrue(raw.none { it.toString().contains("3331112222") })
    }

    @Test
    fun `an empty store reads as an empty ledger`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("argus_telephony_rate", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(TelephonyLedger(), PrefsTelephonyLedgerStore(context).read())
    }
}
