package dev.argus.automation.vm

import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Log delle azioni privilegiate 0.5.1: nome del tipo e motivi nuovi, in inglese e in italiano. */
class LogPrivileged051Test {
    private val codes = listOf(
        "target_forbidden",
        "nfc_unavailable",
        "permission_not_applied",
        "screenshot_save_failed",
        "screen_capture_failed",
        "screen_capture_invalid",
    )

    @Test
    fun `each new reason has a readable text in both languages`() {
        codes.forEach { code ->
            val en = assertNotNull(LogReasonLabels.label(code, RenderLanguage.EN), code)
            val it = assertNotNull(LogReasonLabels.label(code, RenderLanguage.IT), code)
            assertTrue(en.isNotBlank() && it.isNotBlank() && en != it, code)
            assertTrue(code !in en && code !in it, code)
        }
    }

    private fun row(type: String, code: String?, language: RenderLanguage) = AuditLogRecord(
        id = 1L,
        automationId = "a",
        automationName = "Regola",
        kind = AuditKind.FIRED,
        atMillis = 1_000L,
        detail = "",
        executionId = "e",
        executionStatus = null,
        succeededCount = null,
        failedCount = null,
        submittedCount = null,
    ).toLogRow(
        actions = listOf(
            ActionResultEntity(
                executionId = "e",
                actionIndex = 0,
                actionType = type,
                outcome = if (code == null) ActionJournalOutcome.SUCCEEDED else ActionJournalOutcome.FAILED,
                atMillis = 1_000L,
                errorCode = code,
            ),
        ),
        language = language,
    )

    @Test
    fun `the log names every new action in both languages`() {
        val expected = mapOf(
            ActionTypeIds.APP_CONTROL to ("App control" to "Controllo app"),
            ActionTypeIds.SET_AIRPLANE to ("Set airplane mode" to "Imposta modalità aereo"),
            ActionTypeIds.SET_LOCATION to ("Set location" to "Imposta posizione"),
            ActionTypeIds.SET_NFC to ("Set NFC" to "Imposta NFC"),
            ActionTypeIds.SET_BATTERY_SAVER to ("Set battery saver" to "Imposta risparmio energetico"),
            ActionTypeIds.LOCK_SCREEN to ("Lock screen" to "Blocca schermo"),
            ActionTypeIds.SCREENSHOT to ("Screen capture" to "Cattura schermata"),
            ActionTypeIds.APP_PERMISSION to ("App permission" to "Permesso app"),
        )
        expected.forEach { (type, names) ->
            assertEquals("1. ${names.first} → ok", assertNotNull(row(type, null, RenderLanguage.EN).expandedDetail).single(), type)
            assertEquals("1. ${names.second} → ok", assertNotNull(row(type, null, RenderLanguage.IT).expandedDetail).single(), type)
        }
    }

    @Test
    fun `a failed action shows the reason and never a path or a package`() {
        val detail = assertNotNull(row(ActionTypeIds.APP_CONTROL, "target_forbidden", RenderLanguage.IT).expandedDetail).single()
        assertEquals(
            "1. Controllo app → fallita · Argus, il launcher e le app di sistema essenziali non si possono modificare con una regola",
            detail,
        )
        val exit = assertNotNull(row(ActionTypeIds.SET_AIRPLANE, "set_airplane_exit_1", RenderLanguage.EN).expandedDetail).single()
        assertEquals("1. Set airplane mode → failed · the system command ended with code 1", exit)
    }

    private fun screenshotRow(outcome: ActionJournalOutcome, code: String?, language: RenderLanguage) = AuditLogRecord(
        id = 2L,
        automationId = "a",
        automationName = "Regola",
        kind = AuditKind.FIRED,
        atMillis = 1_000L,
        detail = "",
        executionId = "e",
        executionStatus = null,
        succeededCount = null,
        failedCount = null,
        submittedCount = null,
    ).toLogRow(
        actions = listOf(
            ActionResultEntity(
                executionId = "e",
                actionIndex = 0,
                actionType = ActionTypeIds.SCREENSHOT,
                outcome = outcome,
                atMillis = 1_000L,
                errorCode = code,
            ),
        ),
        language = language,
    )

    @Test
    fun `a saved screenshot shows its file name and only that`() {
        val code = "screenshot_20261028_153012"
        assertEquals(
            "1. Screen capture → ok · Pictures/Argus/Argus-20261028-153012.png",
            assertNotNull(screenshotRow(ActionJournalOutcome.SUCCEEDED, code, RenderLanguage.EN).expandedDetail).single(),
        )
        assertEquals(
            "1. Cattura schermata → ok · Pictures/Argus/Argus-20261028-153012.png",
            assertNotNull(screenshotRow(ActionJournalOutcome.SUCCEEDED, code, RenderLanguage.IT).expandedDetail).single(),
        )
    }

    @Test
    fun `a screenshot that failed never shows a file name`() {
        // Lo stesso codice su una riga fallita non e' un nome di file: cade sul testo dei motivi.
        val detail = assertNotNull(
            screenshotRow(ActionJournalOutcome.FAILED, "screenshot_20261028_153012", RenderLanguage.EN).expandedDetail,
        ).single()
        assertTrue("Pictures/Argus" !in detail, detail)
    }
}
