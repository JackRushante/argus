package dev.argus.engine.runtime

import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarTriggerPlannerTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun instance(
        id: Long,
        begin: Long,
        end: Long = begin + 30 * minute,
        title: String? = "Riunione",
        calendar: String? = "Lavoro",
        allDay: Boolean = false,
        declined: Boolean = false,
        free: Boolean = false,
    ) = CalendarInstance(id, begin, end, title, calendar, allDay, declined, free, canceled = false)

    @Test
    fun `next start is the earliest future instance shifted by the offset`() {
        val trigger = Trigger.CalendarEvent(CalendarPhase.START, offsetMinutes = -10)
        val instances = listOf(
            instance(2, now + 60 * minute),
            instance(1, now + 30 * minute),
            // Già iniziato: il suo inizio è passato.
            instance(3, now - 5 * minute),
        )
        val next = assertNotNullOccurrence(CalendarTriggerPlanner.next(trigger, instances, now, emptySet()))
        assertEquals(1, next.eventId)
        assertEquals(now + 20 * minute, next.fireAtMillis)
        assertEquals(CalendarPhase.START, next.phase)
        assertEquals("1:${now + 30 * minute}:START", next.key)
        assertEquals("Riunione", next.title)
    }

    @Test
    fun `end phase uses the end of the instance`() {
        val trigger = Trigger.CalendarEvent(CalendarPhase.END, offsetMinutes = 5)
        val next = assertNotNullOccurrence(
            CalendarTriggerPlanner.next(trigger, listOf(instance(1, now - 10 * minute, now + 10 * minute)), now, emptySet()),
        )
        assertEquals(now + 15 * minute, next.fireAtMillis)
    }

    @Test
    fun `filters and exclusions are the same as calendar_busy`() {
        val trigger = Trigger.CalendarEvent(CalendarPhase.START, titleMatch = "riunione", calendar = "LAVORO")
        val instances = listOf(
            instance(1, now + minute, allDay = true),
            instance(2, now + 2 * minute, declined = true),
            instance(3, now + 3 * minute, free = true),
            instance(4, now + 4 * minute, title = "Palestra"),
            instance(5, now + 5 * minute, calendar = "Casa"),
            instance(6, now + 6 * minute),
        )
        assertEquals(6, CalendarTriggerPlanner.next(trigger, instances, now, emptySet())?.eventId)
    }

    @Test
    fun `fired instances are skipped and nothing beyond the look-ahead is planned`() {
        val trigger = Trigger.CalendarEvent(CalendarPhase.START)
        val first = instance(1, now + minute)
        val firedKey = CalendarTriggerPlanner.occurrences(trigger, listOf(first)).single().key
        val far = instance(2, now + CalendarTriggerPlanner.LOOKAHEAD_MILLIS + minute)
        assertNull(CalendarTriggerPlanner.next(trigger, listOf(first, far), now, setOf(firedKey)))
    }

    @Test
    fun `due instances are those whose time has come within the grace, not yet fired`() {
        val trigger = Trigger.CalendarEvent(CalendarPhase.START)
        val onTime = instance(1, now)
        val late = instance(2, now - CalendarTriggerPlanner.LATE_GRACE_MILLIS + minute)
        val tooLate = instance(3, now - CalendarTriggerPlanner.LATE_GRACE_MILLIS - minute)
        val future = instance(4, now + 5 * minute)
        val due = CalendarTriggerPlanner.due(trigger, listOf(onTime, late, tooLate, future), now, emptySet())
        assertEquals(listOf(2L, 1L), due.map { it.eventId })
        val firedKey = due.first().key
        assertEquals(listOf(1L), CalendarTriggerPlanner.due(trigger, listOf(onTime, late), now, setOf(firedKey)).map { it.eventId })
        // Ciò che è dovuto non è anche «il prossimo».
        assertEquals(4, CalendarTriggerPlanner.next(trigger, listOf(onTime, future), now, emptySet())?.eventId)
    }

    @Test
    fun `two instances of the same recurring event are distinct`() {
        val trigger = Trigger.CalendarEvent(CalendarPhase.START)
        val keys = CalendarTriggerPlanner.occurrences(trigger, listOf(instance(7, now), instance(7, now + 86_400_000L)))
            .map { it.key }
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `the query window covers every instance whose shifted start or end can fall in the look-ahead`() {
        val (from, to) = CalendarTriggerPlanner.queryWindow(now)
        assertTrue(from <= now - CalendarTriggerPlanner.LATE_GRACE_MILLIS - CalendarTriggerPlanner.MAX_OFFSET_MINUTES * minute)
        assertTrue(to >= now + CalendarTriggerPlanner.LOOKAHEAD_MILLIS + CalendarTriggerPlanner.MAX_OFFSET_MINUTES * minute)
    }

    @Test
    fun `an occurrence never prints its title`() {
        val occurrence = CalendarTriggerPlanner.occurrences(Trigger.CalendarEvent(CalendarPhase.START), listOf(instance(1, now)))
            .single()
        assertTrue("Riunione" !in occurrence.toString())
    }

    private fun assertNotNullOccurrence(value: CalendarOccurrence?): CalendarOccurrence {
        kotlin.test.assertNotNull(value)
        return value
    }
}
