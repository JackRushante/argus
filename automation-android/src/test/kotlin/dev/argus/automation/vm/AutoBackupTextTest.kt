package dev.argus.automation.vm

import dev.argus.automation.backup.AutoBackupSnapshot
import dev.argus.data.backup.AutoBackupFailure
import dev.argus.data.backup.AutoBackupFrequency
import dev.argus.data.backup.AutoBackupLastRun
import dev.argus.data.backup.AutoBackupState
import dev.argus.ui.model.AutoBackupFrequencyUi
import dev.argus.ui.presentation.RenderLanguage
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoBackupTextTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-11-16T09:30")

    @Test
    fun `the last backup reads with a relative day, the time and the rule count`() {
        assertEquals(
            "Ultimo backup: ieri 03:10 · 12 regole",
            AutoBackupText.lastRun(AutoBackupLastRun.Written(at("2026-11-15T03:10"), 12), now, zone, RenderLanguage.IT),
        )
        assertEquals(
            "Last backup: today 03:10 · 1 rule",
            AutoBackupText.lastRun(AutoBackupLastRun.Written(at("2026-11-16T03:10"), 1), now, zone, RenderLanguage.EN),
        )
        assertEquals(
            "Ultimo backup: 12 nov 03:10 · 1 regola",
            AutoBackupText.lastRun(AutoBackupLastRun.Written(at("2026-11-12T03:10"), 1), now, zone, RenderLanguage.IT),
        )
        assertEquals("Nov 12 03:10", AutoBackupText.whenText(at("2026-11-12T03:10"), now, zone, RenderLanguage.EN))
    }

    @Test
    fun `a failure says what went wrong in plain words`() {
        assertEquals(
            "Ultimo backup non riuscito (oggi 03:00): la cartella non è raggiungibile (cancellata, o accesso tolto). " +
                "Argus riprova più tardi; se continua, scegli di nuovo la cartella.",
            AutoBackupText.lastRun(
                AutoBackupLastRun.Failed(at("2026-11-16T03:00"), AutoBackupFailure.FOLDER_UNREACHABLE),
                now,
                zone,
                RenderLanguage.IT,
            ),
        )
        AutoBackupFailure.entries.forEach { failure ->
            val it = AutoBackupText.failure(failure, RenderLanguage.IT)
            val en = AutoBackupText.failure(failure, RenderLanguage.EN)
            assertTrue(it.isNotBlank() && en.isNotBlank(), "testo mancante per $failure")
            assertTrue(it != en, "stesso testo nelle due lingue per $failure")
        }
    }

    @Test
    fun `nothing to back up is not shown as a failure`() {
        val ui = AutoBackupText.ui(
            AutoBackupSnapshot(
                AutoBackupState(lastRun = AutoBackupLastRun.NothingToBackUp(at("2026-11-16T03:00"))),
            ),
            now,
            zone,
            RenderLanguage.EN,
        )
        assertEquals("Last check: today 03:00 · no rules to back up yet", ui.lastRun)
        assertFalse(ui.lastRunFailed)
    }

    @Test
    fun `the card state carries no folder uri and only whether a key exists`() {
        val ui = AutoBackupText.ui(
            AutoBackupSnapshot(
                AutoBackupState(
                    enabled = true,
                    frequency = AutoBackupFrequency.WEEKLY,
                    folderUri = "content://com.nextcloud.client.documents/tree/secret-path",
                    folderLabel = "Argus",
                    keyConfigured = true,
                    lastRun = AutoBackupLastRun.Failed(at("2026-11-16T03:00"), AutoBackupFailure.WRITE_FAILED),
                ),
                running = true,
            ),
            now,
            zone,
            RenderLanguage.IT,
        )

        assertEquals("Argus", ui.folderLabel)
        assertEquals(AutoBackupFrequencyUi.WEEKLY, ui.frequency)
        assertTrue(ui.passphraseSet && ui.enabled && ui.running && ui.lastRunFailed && ui.ready)
        assertFalse("content://" in ui.toString())
    }

    @Test
    fun `never run and no folder read as such`() {
        val ui = AutoBackupText.ui(AutoBackupSnapshot(AutoBackupState()), now, zone, RenderLanguage.IT)

        assertNull(ui.lastRun)
        assertNull(ui.folderLabel)
        assertFalse(ui.ready)
    }
}
