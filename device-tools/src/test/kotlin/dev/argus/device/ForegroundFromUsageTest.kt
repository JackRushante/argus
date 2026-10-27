package dev.argus.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundFromUsageTest {
    private fun resumed(pkg: String, at: Long) = UsageEventRecord(pkg, ForegroundFromUsage.RESUMED, at)
    private fun paused(pkg: String, at: Long) = UsageEventRecord(pkg, ForegroundFromUsage.PAUSED, at)
    private fun stopped(pkg: String, at: Long) = UsageEventRecord(pkg, ForegroundFromUsage.STOPPED, at)

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
    fun `two activities of one app keep the app in the foreground`() {
        val events = listOf(resumed("a", 1), paused("a", 2), resumed("a", 3))
        assertEquals("a", ForegroundFromUsage.resolve(events))
    }
}
