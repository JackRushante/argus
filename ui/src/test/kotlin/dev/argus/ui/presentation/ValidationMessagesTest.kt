package dev.argus.ui.presentation

import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B9: ogni codice che il validator, il rilevatore di conflitti o il flusso di arm possono emettere
 * ha un testo EN e IT. I codici si leggono dal SORGENTE (come nel test di copertura dell'approvazione):
 * aggiungere un codice senza il suo testo fa fallire il test.
 */
class ValidationMessagesTest {
    /** Il doppio apice nei pattern: evita sequenze di virgolette ambigue nelle stringhe raw. */
    private val Q = '"'

    private fun source(relative: String): String {
        // Gradle esegue i test con la cartella del modulo come working dir.
        val candidates = listOf(File("../$relative"), File(relative))
        val file = candidates.firstOrNull(File::isFile)
            ?: error("sorgente non trovato: $relative (cwd=${File(".").absoluteFile})")
        return file.readText()
    }

    private val validatorSource =
        source("engine-core/src/main/kotlin/dev/argus/engine/safety/DraftValidator.kt")

    private val validatorCodes: Set<String> = buildSet {
        // err("codice", …), warn("codice", …) e errDetail("codice", …, dettaglio)
        Regex("""\b(?:err|warn|errDetail)\(\s*$Q([a-z][a-z0-9_]*)$Q""").findAll(validatorSource)
            .forEach { add(it.groupValues[1]) }
        // helper che ricevono il codice come argomento: validateX(valore, …, "codice", err)
        Regex("""$Q([a-z][a-z0-9_]*)$Q,\s*err\)""").findAll(validatorSource)
            .forEach { add(it.groupValues[1]) }
    }

    private val issueRegex = Regex("""ValidationIssue\(\s*Severity\.\w+,\s*$Q([a-z][a-z0-9_]*)$Q""")
    private val uiWarningRegex = Regex("""UiWarning\(\s*Severity\.\w+,\s*$Q([a-z][a-z0-9_]*)$Q""")

    private val armCodes: Set<String> = buildSet {
        listOf(
            "engine-core/src/main/kotlin/dev/argus/engine/safety/Approval.kt",
            "automation-android/src/main/kotlin/dev/argus/automation/ApprovalFlow.kt",
        ).forEach { path ->
            issueRegex.findAll(source(path)).forEach { add(it.groupValues[1]) }
        }
        uiWarningRegex.findAll(
            source("automation-android/src/main/kotlin/dev/argus/automation/vm/AutomationDetailViewModel.kt"),
        ).forEach { add(it.groupValues[1]) }
    }

    private val conflictCodes: Set<String> =
        Regex("""Setting\($Q([a-z][a-z0-9_]*)$Q""")
            .findAll(source("engine-core/src/main/kotlin/dev/argus/engine/safety/ConflictDetector.kt"))
            .mapTo(linkedSetOf()) { "conflict_${it.groupValues[1]}" }

    @Test
    fun `the source scan finds the expected codes so the coverage check cannot pass vacuously`() {
        assertTrue(validatorCodes.size > 100, "codici validator: ${validatorCodes.size}")
        assertTrue(
            validatorCodes.containsAll(
                listOf(
                    "no_actions", "tz_invalid", "title_invalid", "pkg_blank", "shell_external_trigger",
                    "write_setting_high_impact", "write_setting_external_value", "interpolation_shell_context",
                ),
            ),
        )
        assertTrue(
            armCodes.containsAll(
                listOf(
                    "approval_integrity_failure", "approval_policy_unavailable", "capability_unavailable",
                    "capability_temporarily_unavailable", "capability_policy_unavailable",
                    "state_query_unavailable", "state_query_type_mismatch", "draft_review_unavailable",
                    "privacy_generative", "schema_or_integrity_review",
                ),
            ),
            "codici arm: $armCodes",
        )
        assertEquals(
            setOf(
                "conflict_wifi", "conflict_bluetooth", "conflict_mobile_data", "conflict_dnd",
                "conflict_ringer", "conflict_dark_mode",
            ),
            conflictCodes,
        )
    }

    @Test
    fun `every emitted validation conflict and arm code has a text`() {
        val emitted = validatorCodes + armCodes + conflictCodes
        val missing = emitted.filterNot(ValidationMessages::isKnown).sorted()
        assertTrue(missing.isEmpty(), "codici senza testo in ValidationMessages: $missing")
    }

    @Test
    fun `the table has no code that nothing emits any more`() {
        val emitted = validatorCodes + armCodes + conflictCodes
        val stale = ValidationMessages.knownCodes.filterNot(emitted::contains).sorted()
        assertTrue(stale.isEmpty(), "testi per codici non piu' emessi: $stale")
    }

    private val italianWords = setOf(
        "non", "della", "delle", "dello", "nella", "nelle", "regola", "regole", "deve", "devono", "può",
        "possono", "sono", "che", "uno", "una", "nel", "dei", "degli", "per", "con", "il", "lo", "gli",
        "anche", "quando", "questa", "questo", "azione", "azioni", "dispositivo", "impostazione",
        "valore", "ammesso", "troppe", "troppo", "mancante", "manca",
    )

    @Test
    fun `english texts contain no typical italian words and italian texts differ from english`() {
        for (code in ValidationMessages.knownCodes.sorted()) {
            val en = ValidationMessages.text(code, RenderLanguage.EN)
            val italian = ValidationMessages.text(code, RenderLanguage.IT)
            val words = Regex("""[A-Za-zÀ-ÿ]+""").findAll(en).map { match -> match.value.lowercase() }.toSet()
            val offending = words.intersect(italianWords)
            assertTrue(offending.isEmpty(), "$code: parole italiane nel testo EN $offending -> $en")
            assertTrue(en.isNotBlank() && italian.isNotBlank(), "$code: testo vuoto")
            assertTrue(en != italian, "$code: IT identico a EN")
            assertTrue(en.length <= 260 && italian.length <= 320, "$code: testo troppo lungo per un banner")
        }
    }

    @Test
    fun `an unknown code gets a localized generic text with the code in parentheses`() {
        assertEquals(
            "Unrecognized validation problem (some_future_code).",
            ValidationMessages.text("some_future_code", RenderLanguage.EN),
        )
        assertEquals(
            "Problema di validazione non riconosciuto (some_future_code).",
            ValidationMessages.text("some_future_code", RenderLanguage.IT),
        )
        // Un codice ostile non porta caratteri strani nella UI.
        assertEquals(
            "Unrecognized validation problem (a_b_c_d).",
            ValidationMessages.text("a b<c>d", RenderLanguage.EN),
        )
        assertEquals(
            "Unrecognized validation problem (?).",
            ValidationMessages.text("", RenderLanguage.EN),
        )
        assertTrue(!ValidationMessages.isKnown("some_future_code"))
    }

    @Test
    fun `codes that need it append the structured detail`() {
        val issue = ValidationIssue(
            Severity.ERROR,
            "capability_unavailable",
            "Capacità non disponibile in questa fase: action.run_shell",
            detail = "action.run_shell",
        )
        assertEquals(
            ValidationMessages.text("capability_unavailable", RenderLanguage.EN) + " (action.run_shell)",
            ValidationMessages.text(issue, RenderLanguage.EN),
        )
        // Il detail e' ignorato dai codici che non lo prevedono e ripulito dai caratteri strani.
        assertEquals(
            ValidationMessages.text("no_actions", RenderLanguage.EN),
            ValidationMessages.text(
                ValidationIssue(Severity.ERROR, "no_actions", "x", detail = "ignored"),
                RenderLanguage.EN,
            ),
        )
        val hostile = ValidationIssue(Severity.ERROR, "state_query_unavailable", "x", detail = "a b<script>")
        assertTrue(ValidationMessages.text(hostile, RenderLanguage.EN).endsWith("(a_b_script_)"))
        // Senza detail il testo resta quello del codice.
        assertEquals(
            ValidationMessages.text("capability_unavailable", RenderLanguage.IT),
            ValidationMessages.text(
                ValidationIssue(Severity.ERROR, "capability_unavailable", "x"),
                RenderLanguage.IT,
            ),
        )
    }

    @Test
    fun `a macro step error names the step and says why`() {
        val noTarget = ValidationIssue(Severity.ERROR, "ui_step_wait_no_target", "x", detail = "1")
        val en = ValidationMessages.text(noTarget, RenderLanguage.EN)
        assertTrue(en.startsWith("Step 1: "), en)
        assertTrue("no text and no id" in en && "no app to open" in en, en)
        val italian = ValidationMessages.text(noTarget, RenderLanguage.IT)
        assertTrue(italian.startsWith("Passo 1: "), italian)
        assertTrue("senza testo né id" in italian && "non ha un'app da aprire" in italian, italian)

        // Ogni motivo ha un testo suo, e il passo viene dal detail (solo cifre).
        val reasons = listOf(
            "ui_step_wait_no_target", "ui_step_selector_missing", "ui_step_selector_empty",
            "ui_step_selector_too_long", "ui_step_selector_control", "ui_step_timeout", "ui_step_index",
            "ui_step_type_empty", "ui_step_type_too_long", "ui_step_interpolated",
        )
        val texts = reasons.map { code ->
            ValidationMessages.text(ValidationIssue(Severity.ERROR, code, "x", detail = "12"), RenderLanguage.EN)
        }
        assertEquals(reasons.size, texts.toSet().size)
        assertTrue(texts.all { text -> text.startsWith("Step 12: ") }, texts.toString())

        // Senza numero (voci d'audit con i soli codici) o con un detail strano il testo resta quello del codice.
        assertEquals(
            ValidationMessages.text("ui_step_timeout", RenderLanguage.EN),
            ValidationMessages.text(ValidationIssue(Severity.ERROR, "ui_step_timeout", "x"), RenderLanguage.EN),
        )
        assertEquals(
            ValidationMessages.text("ui_step_timeout", RenderLanguage.EN),
            ValidationMessages.text(
                ValidationIssue(Severity.ERROR, "ui_step_timeout", "x", detail = "3<b>"),
                RenderLanguage.EN,
            ),
        )
        // Il vecchio codice generico non esiste più: niente testo che non dice quale passo né perché.
        assertTrue(!ValidationMessages.isKnown("ui_step_invalid"))
    }

    @Test
    fun `a code list renders one text per code`() {
        assertEquals(
            ValidationMessages.text("tz_invalid", RenderLanguage.EN) + " " +
                ValidationMessages.text("cron_invalid", RenderLanguage.EN),
            ValidationMessages.textList("tz_invalid,cron_invalid", RenderLanguage.EN),
        )
        assertEquals("", ValidationMessages.textList("", RenderLanguage.EN))
        assertEquals("", ValidationMessages.textList(" , ", RenderLanguage.IT))
    }
}
