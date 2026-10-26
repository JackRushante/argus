package dev.argus.shizuku

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BindCircuitBreakerTest {
    private var now = 1_000L

    private fun breaker() = BindCircuitBreaker(clock = { now }, openMillis = 60_000L)

    @Test
    fun `starts closed`() {
        assertFalse(breaker().isOpen(epoch = 0))
    }

    @Test
    fun `stays open for the whole window and closes right after it`() {
        val breaker = breaker()

        breaker.trip(epoch = 3)

        assertTrue(breaker.isOpen(epoch = 3))
        now += 59_999L
        assertTrue(breaker.isOpen(epoch = 3))
        now += 1L
        assertFalse(breaker.isOpen(epoch = 3))
        // Una volta chiuso resta chiuso finche' un nuovo bind non fallisce.
        assertFalse(breaker.isOpen(epoch = 3))
    }

    @Test
    fun `any change of Shizuku state closes the breaker`() {
        val breaker = breaker()
        breaker.trip(epoch = 3)

        assertFalse(breaker.isOpen(epoch = 4))
        // Tornare all'epoca precedente non lo riapre.
        assertFalse(breaker.isOpen(epoch = 3))
    }

    @Test
    fun `reset closes it and a new trip restarts the window`() {
        val breaker = breaker()
        breaker.trip(epoch = 1)
        breaker.reset()
        assertFalse(breaker.isOpen(epoch = 1))

        now += 10_000L
        breaker.trip(epoch = 1)
        now += 59_000L
        assertTrue(breaker.isOpen(epoch = 1))
    }
}
