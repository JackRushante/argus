package dev.argus.data.backup

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nomi, pulizia e calendario del backup automatico: puro JVM. */
class AutoBackupPolicyTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()
    private fun local(millis: Long): String =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), rome).toLocalDateTime().toString()

    private val ready = AutoBackupState(enabled = true, folderUri = "content://tree/backup", keyConfigured = true)

    // --- nomi e pulizia ---------------------------------------------------------------------------

    @Test
    fun `the file name carries the local date and time`() {
        assertEquals("argus-rules-20261116-0310.argusbackup", AutoBackupRetention.fileName(at("2026-11-16T03:10:59"), rome))
        assertTrue(AutoBackupRetention.isAutoBackupName("argus-rules-20261116-0310.argusbackup"))
        assertFalse(AutoBackupRetention.isAutoBackupName("argus-rules-2026-11-16.json"))
        assertFalse(AutoBackupRetention.isAutoBackupName("argus-rules-20261116-0310 (1).argusbackup"))
        assertFalse(AutoBackupRetention.isAutoBackupName("x-argus-rules-20261116-0310.argusbackup"))
    }

    @Test
    fun `retention keeps the seven newest files argus wrote and deletes the older ones`() {
        val ours = (1..10).map { day -> "argus-rules-202611%02d-0300.argusbackup".format(day) }

        val deleted = AutoBackupRetention.toDelete(ours.shuffled(), ours.toSet())

        assertEquals(
            listOf(
                "argus-rules-20261103-0300.argusbackup",
                "argus-rules-20261102-0300.argusbackup",
                "argus-rules-20261101-0300.argusbackup",
            ),
            deleted,
        )
    }

    @Test
    fun `retention never touches files argus did not write, even with the same name pattern`() {
        val ours = (1..9).map { day -> "argus-rules-202611%02d-0300.argusbackup".format(day) }
        val foreign = listOf(
            "argus-rules-20200101-0000.argusbackup", // copiato a mano dall'utente
            "argus-rules-2026-11-01.json",
            "vacanze.jpg",
        )

        val deleted = AutoBackupRetention.toDelete(ours + foreign, ours.toSet())

        assertEquals(listOf("argus-rules-20261102-0300.argusbackup", "argus-rules-20261101-0300.argusbackup"), deleted)
        assertTrue(deleted.none { it in foreign })
    }

    @Test
    fun `with seven or fewer files nothing is deleted`() {
        val ours = (1..7).map { day -> "argus-rules-202611%02d-0300.argusbackup".format(day) }

        assertTrue(AutoBackupRetention.toDelete(ours, ours.toSet()).isEmpty())
        assertTrue(AutoBackupRetention.toDelete(emptyList(), emptySet()).isEmpty())
    }

    // --- calendario --------------------------------------------------------------------------------

    @Test
    fun `nothing is scheduled unless enabled, with a folder and a key`() {
        val now = at("2026-11-16T12:00")
        assertNull(AutoBackupSchedule.nextRunAt(ready.copy(enabled = false), now, rome))
        assertNull(AutoBackupSchedule.nextRunAt(ready.copy(folderUri = null), now, rome))
        assertNull(AutoBackupSchedule.nextRunAt(ready.copy(keyConfigured = false), now, rome))
        assertFalse(AutoBackupSchedule.isDue(ready.copy(enabled = false), now, rome))
    }

    @Test
    fun `the first backup is the next night at three`() {
        assertEquals("2026-11-17T03:00", local(AutoBackupSchedule.nextRunAt(ready, at("2026-11-16T12:00"), rome)!!))
        assertEquals("2026-11-16T03:00", local(AutoBackupSchedule.nextRunAt(ready, at("2026-11-16T01:30"), rome)!!))
    }

    @Test
    fun `daily runs every night, weekly once a week, also after a late or early run`() {
        val daily = ready.copy(lastCompletedAtMillis = at("2026-11-16T03:10"))
        assertEquals("2026-11-17T03:00", local(AutoBackupSchedule.nextRunAt(daily, at("2026-11-16T03:11"), rome)!!))
        // «Esegui ora» di pomeriggio: la notte dopo si salta solo se il backup è di meno di 12 ore prima.
        val afternoon = ready.copy(lastCompletedAtMillis = at("2026-11-16T14:00"))
        assertEquals("2026-11-17T03:00", local(AutoBackupSchedule.nextRunAt(afternoon, at("2026-11-16T14:01"), rome)!!))

        val weekly = daily.copy(frequency = AutoBackupFrequency.WEEKLY)
        assertEquals("2026-11-23T03:00", local(AutoBackupSchedule.nextRunAt(weekly, at("2026-11-16T03:11"), rome)!!))
        // Sveglia in ritardo (Android l'ha spostata alle 4:40): la settimana dopo resta alle 3.
        val lateWeekly = weekly.copy(lastCompletedAtMillis = at("2026-11-16T04:40"))
        assertEquals("2026-11-23T03:00", local(AutoBackupSchedule.nextRunAt(lateWeekly, at("2026-11-16T05:00"), rome)!!))
    }

    @Test
    fun `an overdue backup is due at once`() {
        val stale = ready.copy(lastCompletedAtMillis = at("2026-11-01T03:00"))
        val now = at("2026-11-16T09:00")

        assertTrue(AutoBackupSchedule.nextRunAt(stale, now, rome)!! < now)
        assertTrue(AutoBackupSchedule.isDue(stale, now, rome))
        assertFalse(AutoBackupSchedule.isDue(ready.copy(lastCompletedAtMillis = at("2026-11-16T03:00")), now, rome))
    }

    @Test
    fun `the night stays at three across daylight saving changes`() {
        val beforeSpring = ready.copy(lastCompletedAtMillis = at("2026-03-28T03:00"))
        assertEquals("2026-03-29T03:00", local(AutoBackupSchedule.nextRunAt(beforeSpring, at("2026-03-28T04:00"), rome)!!))
        val beforeAutumn = ready.copy(lastCompletedAtMillis = at("2026-10-24T03:00"))
        assertEquals("2026-10-25T03:00", local(AutoBackupSchedule.nextRunAt(beforeAutumn, at("2026-10-24T04:00"), rome)!!))
    }

    @Test
    fun `after a failure it retries with a growing delay, capped at twelve hours`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 12L, 12L), (1..6).map { AutoBackupSchedule.retryDelayMillis(it) / 3_600_000 })
        val failed = ready.copy(
            lastCompletedAtMillis = at("2026-11-15T03:00"),
            lastAttemptAtMillis = at("2026-11-16T03:00"),
            consecutiveFailures = 2,
        )
        assertEquals("2026-11-16T05:00", local(AutoBackupSchedule.nextRunAt(failed, at("2026-11-16T03:01"), rome)!!))
    }

    // --- esiti ------------------------------------------------------------------------------------

    @Test
    fun `a written backup resets the failures and records the count`() {
        val failing = ready.copy(consecutiveFailures = 4, failureNotified = true)
        val now = at("2026-11-16T03:10")

        val recorded = AutoBackupSchedule.record(failing, AutoBackupOutcome.Written(12, "f", 0), now)

        assertEquals(0, recorded.state.consecutiveFailures)
        assertFalse(recorded.state.failureNotified)
        assertEquals(now, recorded.state.lastCompletedAtMillis)
        assertEquals(AutoBackupLastRun.Written(now, 12), recorded.state.lastRun)
        assertFalse(recorded.notifyFailure)
        assertTrue(recorded.clearFailureNotice)
    }

    @Test
    fun `nothing to back up counts as a completed run, not a failure`() {
        val now = at("2026-11-16T03:10")
        val recorded = AutoBackupSchedule.record(ready, AutoBackupOutcome.NothingToBackUp, now)

        assertEquals(AutoBackupLastRun.NothingToBackUp(now), recorded.state.lastRun)
        assertEquals(now, recorded.state.lastCompletedAtMillis)
        assertEquals(0, recorded.state.consecutiveFailures)
    }

    @Test
    fun `the third failure in a row posts one notification, and only one`() {
        var state = ready.copy(lastCompletedAtMillis = at("2026-11-10T03:00"))
        val notified = (1..5).map { attempt ->
            val recorded = AutoBackupSchedule.record(
                state,
                AutoBackupOutcome.Failed(AutoBackupFailure.FOLDER_UNREACHABLE),
                at("2026-11-16T03:00") + attempt,
            )
            state = recorded.state
            recorded.notifyFailure
        }

        assertEquals(listOf(false, false, true, false, false), notified)
        assertEquals(5, state.consecutiveFailures)
        assertTrue(state.failureNotified)
        assertEquals(at("2026-11-10T03:00"), state.lastCompletedAtMillis, "un fallimento non sposta l'ultimo riuscito")
        assertEquals(AutoBackupLastRun.Failed(at("2026-11-16T03:00") + 5, AutoBackupFailure.FOLDER_UNREACHABLE), state.lastRun)
    }
}
