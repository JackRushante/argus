package dev.argus.automation.vm

import dev.argus.data.dao.AuditLogRecord
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.PauseCodes
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uno scatto saltato per «Pausa automazioni» (F21) si legge nel Log, in italiano e in inglese. */
class PauseLogTextTest {
    private fun pausedRecord(kind: AuditKind = AuditKind.BLOCKED_POLICY, detail: String = PauseCodes.PAUSED) =
        AuditLogRecord(
            id = 1L,
            automationId = "m1",
            automationName = "Apri il garage",
            kind = kind,
            atMillis = 1_000L,
            detail = detail,
            executionId = null,
            executionStatus = null,
            succeededCount = null,
            failedCount = null,
            submittedCount = null,
        )

    @Test
    fun `a skipped fire says the automations are paused`() {
        assertEquals(
            "skipped: automations are paused",
            pausedRecord().toLogRow(emptyList(), RenderLanguage.EN).summary,
        )
        assertEquals(
            "saltata: automazioni in pausa",
            pausedRecord().toLogRow(emptyList(), RenderLanguage.IT).summary,
        )
    }

    @Test
    fun `a skipped fire is a warning, not a failure`() {
        assertEquals(LogOutcome.PARTIAL, pausedRecord().toLogRow(emptyList(), RenderLanguage.EN).outcome)
        // Un blocco di policy vero resta un fallimento.
        assertEquals(
            LogOutcome.FAILED,
            pausedRecord(detail = "capability_unavailable").toLogRow(emptyList(), RenderLanguage.EN).outcome,
        )
    }

    @Test
    fun `the paused code has its own label in both languages`() {
        val en = assertNotNull(LogReasonLabels.label(PauseCodes.PAUSED, RenderLanguage.EN))
        val italian = assertNotNull(LogReasonLabels.label(PauseCodes.PAUSED, RenderLanguage.IT))
        assertNotEquals(en, italian)
        assertTrue(en.isNotBlank() && italian.isNotBlank())
    }
}
