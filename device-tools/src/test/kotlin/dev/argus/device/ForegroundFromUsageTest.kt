package dev.argus.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundFromUsageTest {
    private fun event(pkg: String, type: Int, at: Long, cls: String?) = UsageActivityEvent(pkg, cls, type, at)
    private fun resumed(pkg: String, at: Long, cls: String? = "$pkg.Main") = event(pkg, ForegroundFromUsage.RESUMED, at, cls)
    private fun paused(pkg: String, at: Long, cls: String? = "$pkg.Main") = event(pkg, ForegroundFromUsage.PAUSED, at, cls)
    private fun stopped(pkg: String, at: Long, cls: String? = "$pkg.Main") = event(pkg, ForegroundFromUsage.STOPPED, at, cls)

    @Test
    fun `no events means unknown`() {
        assertNull(ForegroundFromUsage.resolve(emptyList()))
    }

    @Test
    fun `the last resumed package is in the foreground`() {
        val events = listOf(resumed("a", 1), paused("a", 2), resumed("b", 3))
        assertEquals("b", ForegroundFromUsage.resolve(events))
    }

    @Test
    fun `the old app stopping after the new one resumed does not clear it`() {
        val events = listOf(resumed("a", 1), paused("a", 2), resumed("b", 3), stopped("a", 4))
        assertEquals("b", ForegroundFromUsage.resolve(events))
    }

    @Test
    fun `an app that went away with nothing after it is unknown`() {
        assertNull(ForegroundFromUsage.resolve(listOf(resumed("a", 1), paused("a", 2))))
        assertNull(ForegroundFromUsage.resolve(listOf(resumed("a", 1), stopped("a", 2))))
    }

    @Test
    fun `events are ordered by time before being read`() {
        val shuffled = listOf(resumed("b", 3), paused("a", 2), resumed("a", 1))
        assertEquals("b", ForegroundFromUsage.resolve(shuffled))
    }

    @Test
    fun `moving to another screen of the same app keeps the app in the foreground`() {
        // Come lo riporta Android: la nuova schermata riprende, poi la vecchia si ferma.
        val events = listOf(
            resumed("a", 1, "a.List"),
            paused("a", 2, "a.List"),
            resumed("a", 3, "a.Detail"),
            stopped("a", 4, "a.List"),
        )
        assertEquals("a", ForegroundFromUsage.resolve(events))
    }

    @Test
    fun `coming back to the first screen of the app keeps it in the foreground too`() {
        val events = listOf(
            resumed("a", 1, "a.List"),
            paused("a", 2, "a.List"),
            resumed("a", 3, "a.Detail"),
            stopped("a", 4, "a.List"),
            paused("a", 5, "a.Detail"),
            resumed("a", 6, "a.List"),
            stopped("a", 7, "a.Detail"),
        )
        assertEquals("a", ForegroundFromUsage.resolve(events))
    }

    @Test
    fun `a stop of an activity that is not resumed changes nothing`() {
        val events = listOf(resumed("b", 1), stopped("a", 2, "a.Old"), paused("b", 3, "b.Other"))
        assertEquals("b", ForegroundFromUsage.resolve(events))
    }

    @Test
    fun `with two apps resumed the last one resumed wins and the other remains if it leaves`() {
        // Schermo diviso: entrambe riprese; poi la seconda va via.
        val events = listOf(resumed("a", 1), resumed("b", 2))
        assertEquals("b", ForegroundFromUsage.resolve(events))
        assertEquals("a", ForegroundFromUsage.resolve(events + paused("b", 3)))
    }

    @Test
    fun `the source reads a short window, then a long one, and is unknown without access`() {
        val asked = mutableListOf<Long>()
        val longAgo = listOf(resumed("a", 0, "a.List"), resumed("a", 5, "a.Detail"), stopped("a", 6, "a.List"))
        val source = UsageStatsForegroundSource(
            events = { from, to ->
                asked += to - from
                if (to - from > 10 * 60_000L) longAgo else emptyList()
            },
            now = { 1_000_000_000L },
        )
        assertEquals("a", source.current())
        assertEquals(listOf(10 * 60_000L, 3 * 60 * 60_000L), asked)

        assertNull(UsageStatsForegroundSource(events = { _, _ -> null }, now = { 1L }).current())
    }
}
