package dev.argus.automation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodicMaintenanceTest {
    private val hour = 60L * 60 * 1_000
    private var clock = 1_000_000L
    private val runs = mutableListOf<Long>()
    private var idle = true
    private var failNext = false

    private fun maintenance() = PeriodicMaintenance(
        run = { now ->
            if (failNext) {
                failNext = false
                error("forced maintenance failure")
            }
            runs += now
        },
        nowMillis = { clock },
        canRun = { idle },
    )

    @Test
    fun `runs at most once every 24 hours of wall clock`() = runTest {
        val maintenance = maintenance()
        val t0 = clock

        assertTrue(maintenance.runIfDue())
        assertEquals(listOf(t0), runs)

        clock = t0 + hour
        assertFalse(maintenance.isDue())
        assertFalse(maintenance.runIfDue())
        assertEquals(listOf(t0), runs)

        clock = t0 + 25 * hour
        assertTrue(maintenance.isDue())
        assertTrue(maintenance.runIfDue())
        assertEquals(listOf(t0, t0 + 25 * hour), runs)
    }

    @Test
    fun `a clock moved backwards does not block the maintenance`() = runTest {
        val maintenance = maintenance()
        maintenance.runIfDue()

        clock -= 2 * hour

        assertTrue(maintenance.runIfDue())
        assertEquals(2, runs.size)
    }

    @Test
    fun `a busy runtime skips without consuming the interval`() = runTest {
        val maintenance = maintenance()
        idle = false

        assertFalse(maintenance.isDue())
        assertFalse(maintenance.runIfDue())
        assertEquals(emptyList(), runs)

        idle = true
        assertTrue(maintenance.runIfDue())
        assertEquals(1, runs.size)
    }

    @Test
    fun `a failed run does not consume the interval`() = runTest {
        val maintenance = maintenance()
        failNext = true

        assertFailsWith<IllegalStateException> { maintenance.runIfDue() }
        assertEquals(emptyList(), runs)

        clock += hour
        assertTrue(maintenance.runIfDue())
        assertEquals(1, runs.size)
    }
}
