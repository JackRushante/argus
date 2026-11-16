package dev.argus.automation.telephony

import kotlin.test.Test
import kotlin.test.assertEquals
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
