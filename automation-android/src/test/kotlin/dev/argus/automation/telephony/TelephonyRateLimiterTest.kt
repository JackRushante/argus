package dev.argus.automation.telephony

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Registro in memoria: lo stesso contratto del registro su SharedPreferences. */
internal class InMemoryLedgerStore : TelephonyLedgerStore {
    @Volatile var ledger = TelephonyLedger()
    var writable = true
    var writes = 0

    /** Allarga la finestra fra lettura e scrittura, per far emergere una corsa se il lock non c'è. */
    var slowWrite = false

    override fun read(): TelephonyLedger = ledger
    override fun write(ledger: TelephonyLedger): Boolean {
        if (slowWrite) Thread.sleep(2)
        if (!writable) return false
        writes++
        this.ledger = ledger
        return true
    }
    override fun destinationKey(number: String): String = "k" + number.hashCode()
}

class TelephonyRateLimiterTest {
    private var wall = 1_000_000_000L
    private var elapsed = 50_000L
    private var boot = 7
    private val store = InMemoryLedgerStore()
    private val limiter = TelephonyRateLimiter(store) { ClockReading(wall, boot, elapsed) }

    private fun advance(millis: Long) {
        wall += millis
        elapsed += millis
    }

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
        advance(TelephonyRateLimiter.WINDOW_MILLIS - 1)
        assertTrue(limiter.trySms("+393331112222") is RateVerdict.Limited)
        advance(1)
        assertEquals(RateVerdict.Allowed, limiter.trySms("+393331112222"))
    }

    @Test
    fun `three calls an hour`() {
        repeat(3) { assertEquals(RateVerdict.Allowed, limiter.tryCall()) }
        assertEquals(RateVerdict.Limited(TelephonyRateLimiter.CALL_RATE_LIMITED), limiter.tryCall())
        advance(TelephonyRateLimiter.WINDOW_MILLIS)
        assertEquals(RateVerdict.Allowed, limiter.tryCall())
    }

    @Test
    fun `moving the clock in the same boot frees no slot, either way`() {
        repeat(3) { limiter.tryCall() }
        wall -= 10 * 60 * 1_000L
        assertTrue(limiter.tryCall() is RateVerdict.Limited)
        wall += 5 * TelephonyRateLimiter.WINDOW_MILLIS
        assertTrue(limiter.tryCall() is RateVerdict.Limited, "l'ora spostata avanti non libera posti")
        elapsed += TelephonyRateLimiter.WINDOW_MILLIS
        assertEquals(RateVerdict.Allowed, limiter.tryCall())
    }

    @Test
    fun `after a reboot the wall clock counts, and sends in the future expire an hour later`() {
        repeat(3) { limiter.tryCall() }
        // Riavvio: il tempo dall'avvio riparte da zero.
        boot += 1
        elapsed = 1_000L
        wall += 10 * 60 * 1_000L
        assertTrue(limiter.tryCall() is RateVerdict.Limited)
        // Riavvio con l'orologio un anno indietro: gli invii «nel futuro» contano ancora…
        boot += 1
        elapsed = 1_000L
        wall -= 365L * 24 * TelephonyRateLimiter.WINDOW_MILLIS
        assertTrue(limiter.tryCall() is RateVerdict.Limited)
        // …ma per un'ora da adesso, non per sempre.
        advance(TelephonyRateLimiter.WINDOW_MILLIS)
        assertEquals(RateVerdict.Allowed, limiter.tryCall())
    }

    @Test
    fun `an unknown boot uses the wall clock`() {
        boot = UNKNOWN_BOOT
        repeat(3) { limiter.tryCall() }
        elapsed += 2 * TelephonyRateLimiter.WINDOW_MILLIS
        assertTrue(limiter.tryCall() is RateVerdict.Limited)
        wall += TelephonyRateLimiter.WINDOW_MILLIS
        assertEquals(RateVerdict.Allowed, limiter.tryCall())
    }

    @Test
    fun `a ledger that cannot be written or read sends nothing`() {
        store.writable = false
        assertEquals(RateVerdict.Limited(TelephonyRateLimiter.RATE_LEDGER_UNAVAILABLE), limiter.trySms("+393331112222"))
        assertEquals(RateVerdict.Limited(TelephonyRateLimiter.RATE_LEDGER_UNAVAILABLE), limiter.tryCall())
        val broken = object : TelephonyLedgerStore {
            override fun read(): TelephonyLedger = error("disco")
            override fun write(ledger: TelephonyLedger) = true
            override fun destinationKey(number: String) = "k"
        }
        val failing = TelephonyRateLimiter(broken) { ClockReading(wall, boot, elapsed) }
        assertEquals(RateVerdict.Limited(TelephonyRateLimiter.RATE_LEDGER_UNAVAILABLE), failing.trySms("+393331112222"))
    }

    @Test
    fun `concurrent fires never take more than the last slots`() {
        store.slowWrite = true
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..40).map { i ->
            pool.submit<RateVerdict> {
                start.await()
                if (i % 2 == 0) limiter.trySms("+39333000${i % 7}000") else limiter.tryCall()
            }
        }
        start.countDown()
        val verdicts = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(10 + 3, verdicts.count { it == RateVerdict.Allowed })
        assertEquals(10, store.ledger.sms.size)
        assertEquals(3, store.ledger.calls.size)
    }

    @Test
    fun `a second limiter on the same ledger shares the lock and the counts`() {
        val other = TelephonyRateLimiter(store) { ClockReading(wall, boot, elapsed) }
        repeat(2) { limiter.tryCall() }
        assertEquals(RateVerdict.Allowed, other.tryCall())
        assertTrue(limiter.tryCall() is RateVerdict.Limited)
    }
}
