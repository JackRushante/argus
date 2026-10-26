package dev.argus.automation.vm

import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.ExportOutcome
import dev.argus.data.backup.ImportSummary
import dev.argus.data.backup.RuleDiscardReason
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesBackupTextTest {
    private fun summary(imported: Int, vararg discarded: Pair<RuleDiscardReason, Int>) =
        ImportSummary(imported, discarded.toMap())

    @Test
    fun `the import summary says how many rules wait for approval`() {
        assertEquals(
            "5 regole importate da approvare.",
            RulesBackupText.importSummary(summary(5), RenderLanguage.IT),
        )
        assertEquals(
            "5 rules imported, to approve.",
            RulesBackupText.importSummary(summary(5), RenderLanguage.EN),
        )
    }

    @Test
    fun `the import summary handles one rule`() {
        assertEquals("1 regola importata da approvare.", RulesBackupText.importSummary(summary(1), RenderLanguage.IT))
        assertEquals("1 rule imported, to approve.", RulesBackupText.importSummary(summary(1), RenderLanguage.EN))
    }

    @Test
    fun `the import summary names the discarded rules and the reason`() {
        val oneReason = summary(4, RuleDiscardReason.INVALID_RULE to 2)

        assertEquals(
            "4 regole importate da approvare; 2 scartate (regola non valida).",
            RulesBackupText.importSummary(oneReason, RenderLanguage.IT),
        )
        assertEquals(
            "4 rules imported, to approve; 2 discarded (not a valid rule).",
            RulesBackupText.importSummary(oneReason, RenderLanguage.EN),
        )
    }

    @Test
    fun `the import summary counts each reason when there is more than one`() {
        val mixed = summary(
            1,
            RuleDiscardReason.RULE_TOO_LARGE to 1,
            RuleDiscardReason.INVALID_RULE to 3,
        )

        assertEquals(
            "1 regola importata da approvare; 4 scartate (regola non valida: 3, troppo grande: 1).",
            RulesBackupText.importSummary(mixed, RenderLanguage.IT),
        )
        assertEquals(
            "1 rule imported, to approve; 4 discarded (not a valid rule: 3, too large: 1).",
            RulesBackupText.importSummary(mixed, RenderLanguage.EN),
        )
    }

    @Test
    fun `one discarded rule reads in the singular in italian`() {
        assertEquals(
            "2 regole importate da approvare; 1 scartata (voce malformata).",
            RulesBackupText.importSummary(summary(2, RuleDiscardReason.MALFORMED_ENTRY to 1), RenderLanguage.IT),
        )
    }

    @Test
    fun `nothing imported reads as no rules imported`() {
        assertEquals(
            "Nessuna regola importata; 2 scartate (non salvata).",
            RulesBackupText.importSummary(summary(0, RuleDiscardReason.NOT_SAVED to 2), RenderLanguage.IT),
        )
        assertEquals(
            "No rules imported; 2 discarded (could not be saved).",
            RulesBackupText.importSummary(summary(0, RuleDiscardReason.NOT_SAVED to 2), RenderLanguage.EN),
        )
    }

    @Test
    fun `the export summary counts the rules and the ones left out`() {
        assertEquals("1 regola esportata.", RulesBackupText.exportDone(ExportOutcome.Done(1, 0), RenderLanguage.IT))
        assertEquals("7 rules exported.", RulesBackupText.exportDone(ExportOutcome.Done(7, 0), RenderLanguage.EN))
        assertEquals(
            "7 regole esportate. 1 regola illeggibile è stata lasciata fuori.",
            RulesBackupText.exportDone(ExportOutcome.Done(7, 1), RenderLanguage.IT),
        )
        assertEquals(
            "7 rules exported. 2 unreadable rules were left out.",
            RulesBackupText.exportDone(ExportOutcome.Done(7, 2), RenderLanguage.EN),
        )
    }

    @Test
    fun `every failure has its own text in both languages`() {
        BackupFailure.entries.forEach { failure ->
            val italian = RulesBackupText.failure(failure, RenderLanguage.IT)
            val english = RulesBackupText.failure(failure, RenderLanguage.EN)
            assertTrue(italian.isNotBlank() && english.isNotBlank(), "testo mancante per $failure")
            assertTrue(italian != english, "stesso testo nelle due lingue per $failure")
        }
    }

    @Test
    fun `the limits in the messages are the real limits`() {
        assertEquals(
            "Il file ha più di 500 regole.",
            RulesBackupText.failure(BackupFailure.TOO_MANY_RULES, RenderLanguage.IT),
        )
        assertEquals(
            "The file is too large (limit 2 MB).",
            RulesBackupText.failure(BackupFailure.FILE_TOO_LARGE, RenderLanguage.EN),
        )
    }
}
