package dev.argus.automation.vm

import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogExportTest {
    private val header = LogExportHeader("0.3.6", 36, "OnePlus", "CPH2581")
    private val zone = ZoneId.of("Europe/Rome")

    private fun record(
        id: Long,
        kind: AuditKind = AuditKind.FIRED,
        at: Long = 1_784_541_600_000L, // 2026-07-20T10:00:00Z
        detail: String = "",
        executionId: String? = null,
        status: ExecutionStatus? = null,
        automationId: String = "abc",
        name: String? = "Mario Rossi sveglia",
    ) = AuditLogRecord(
        id = id, automationId = automationId, automationName = name, kind = kind, atMillis = at,
        detail = detail, executionId = executionId, executionStatus = status,
        succeededCount = 1, failedCount = 0, submittedCount = 0,
    )

    @Test
    fun `short rule id is the first eight hex characters of the sha-256 of the id`() {
        assertEquals("ba7816bf", LogExport.shortRuleId("abc"))
        assertEquals(8, LogExport.shortRuleId("qualunque-id").length)
    }

    @Test
    fun `header carries version sdk manufacturer and model only`() {
        val text = LogExport.build(header, emptyList(), emptyList(), zone)
        assertTrue(text.contains("app: 0.3.6"))
        assertTrue(text.contains("android_sdk: 36"))
        assertTrue(text.contains("device: OnePlus CPH2581"))
        assertTrue(text.contains("entries: 0"))
    }

    @Test
    fun `an entry shows local time short id event kind status code and its actions`() {
        val text = LogExport.build(
            header,
            listOf(
                record(1, executionId = "e1", status = ExecutionStatus.PARTIAL),
                record(2, kind = AuditKind.ERROR, detail = "shizuku_timeout", at = 1_784_541_660_000L),
            ),
            listOf(
                ActionResultEntity("e1", 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, 1L),
                ActionResultEntity(
                    "e1", 1, "invoke_llm", ActionJournalOutcome.FAILED, 1L,
                    errorCode = "backend_failed", actionPath = "2",
                ),
            ),
            zone,
        )

        // 10:00Z in Europe/Rome (luglio, CEST) = 12:00 locale.
        assertTrue(text.contains("2026-07-20 12:00:00 | rule ba7816bf | FIRED | status PARTIAL | code -"), text)
        assertTrue(text.contains("2026-07-20 12:01:00 | rule ba7816bf | ERROR | status - | code shizuku_timeout"), text)
        assertTrue(text.contains("action 1 set_wifi | SUCCEEDED | code -"), text)
        assertTrue(text.contains("action 2 invoke_llm | FAILED | code backend_failed"), text)
    }

    @Test
    fun `the export never contains the rule name or free text found in details`() {
        val text = LogExport.build(
            header,
            listOf(
                record(1, kind = AuditKind.ERROR, detail = "Mario Rossi 3331234567"),
                record(2, kind = AuditKind.BLOCKED_POLICY, detail = "http://leak.example/x?token=segreto"),
                record(3, kind = AuditKind.FIRED, executionId = "e1"),
            ),
            listOf(
                ActionResultEntity(
                    "e1", 0, "Testo Libero Di Mario", ActionJournalOutcome.FAILED, 1L,
                    errorCode = "ciao Giulia +39 333 1234567",
                ),
            ),
            zone,
        )

        for (leak in listOf("Mario", "Rossi", "3331234567", "leak.example", "segreto", "Giulia", "+39", "Testo Libero")) {
            assertFalse(text.contains(leak), "L'export non deve contenere '$leak':\n$text")
        }
        // Un solo identificativo della regola: l'hash breve, mai l'id in chiaro.
        assertFalse(text.contains("abc |"))
        assertTrue(text.contains("rule ba7816bf"))
    }

    @Test
    fun `only the latest five hundred entries are exported`() {
        val records = (1L..600L).map { record(it, at = 1_784_541_600_000L - it * 1_000) }
        val text = LogExport.build(header, records, emptyList(), zone)

        assertTrue(text.contains("entries: 500"))
        assertEquals(500, text.lines().count { it.contains(" | rule ") })
    }

    @Test
    fun `actions of one execution are listed once even with several audit rows`() {
        val text = LogExport.build(
            header,
            listOf(
                record(1, executionId = "e1", status = ExecutionStatus.SUCCEEDED),
                record(2, kind = AuditKind.SUPPRESSED_BUDGET, executionId = "e1", detail = "month_cost:openai"),
            ),
            listOf(ActionResultEntity("e1", 0, "set_wifi", ActionJournalOutcome.SUCCEEDED, 1L)),
            zone,
        )
        assertEquals(1, text.lines().count { it.contains("action 1 set_wifi") })
        assertTrue(text.contains("code month_cost:openai"))
    }

    @Test
    fun `odd characters in the device strings are stripped`() {
        val text = LogExport.build(
            LogExportHeader("0.3.6\nINJECT", 36, "Acme<script>", "Mod\u0000el X"),
            emptyList(),
            emptyList(),
            zone,
        )
        assertFalse(text.contains("<script>"))
        assertEquals(1, text.lines().count { it.startsWith("app:") })
    }
}
