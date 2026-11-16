package dev.argus.ui.presentation

import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.LabelChipUi
import dev.argus.ui.model.StatusBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ricerca ed etichette della lista (0.7.2): in memoria, senza maiuscole né accenti, conteggi sull'elenco completo. */
class RuleSearchTest {
    private fun row(
        id: String,
        name: String = "Regola $id",
        trigger: String = "Ogni giorno 23:00",
        actions: String? = null,
        apps: List<String> = emptyList(),
        labels: List<String> = emptyList(),
        status: StatusBadge = StatusBadge.ARMED,
        enabled: Boolean = status == StatusBadge.ARMED,
        pendingEdit: String? = null,
    ) = AutomationRow(
        id = id, name = name, triggerIconKey = "time", triggerSummary = trigger, status = status,
        enabled = enabled, isGenerative = false, hasWarnings = false, lastFiredLabel = null, nextFireLabel = null,
        pendingEditDraftId = pendingEdit, actionSummary = actions, appNames = apps, labels = labels,
    )

    private val home = row("a", name = "Arrivo a casa", trigger = "Wi-Fi «Casa» collegato", actions = "Modalità Casa, Volume")
    private val night = row("b", name = "Buonanotte", actions = "Non disturbare")
    private val youtube = row("c", name = "Pausa video", trigger = "App aperta", apps = listOf("YouTube"))

    @Test
    fun `search looks at the name, the trigger, the actions and the app names`() {
        val rows = listOf(home, night, youtube)
        assertEquals(listOf(home), RuleSearch.filter(rows, "arrivo"))
        assertEquals(listOf(home), RuleSearch.filter(rows, "wi-fi"))
        assertEquals(listOf(night), RuleSearch.filter(rows, "disturbare"))
        assertEquals(listOf(youtube), RuleSearch.filter(rows, "youtube"))
    }

    @Test
    fun `search ignores case and accents`() {
        assertTrue(RuleSearch.matches(home, "MODALITA"))
        assertTrue(RuleSearch.matches(row("x", name = "Perché non scatta"), "perche"))
        assertTrue(RuleSearch.matches(row("x", name = "Perche"), "perché"))
    }

    @Test
    fun `every word of the query must be found`() {
        assertTrue(RuleSearch.matches(home, "casa volume"))
        assertFalse(RuleSearch.matches(home, "casa notte"))
    }

    @Test
    fun `an empty query keeps every row and a query with no match keeps none`() {
        val rows = listOf(home, night)
        assertEquals(rows, RuleSearch.filter(rows, "   "))
        assertEquals(emptyList(), RuleSearch.filter(rows, "zzz"))
    }

    @Test
    fun `label chips count every rule, active and waiting ones, without case`() {
        val rows = listOf(
            row("1", labels = listOf("Casa")),
            row("2", labels = listOf("casa", "Lavoro")),
            row("3", labels = listOf("Casa"), status = StatusBadge.DISABLED),
            row("4", labels = listOf("CASA"), status = StatusBadge.PENDING_APPROVAL),
            row("5", labels = listOf("Lavoro"), pendingEdit = "d5"),
            row("6"),
        )

        assertEquals(
            listOf(
                LabelChipUi("Casa", count = 4, activeCount = 2, waitingCount = 1, paused = true),
                LabelChipUi("Lavoro", count = 2, activeCount = 2, waitingCount = 1, paused = false),
            ),
            labelChipsOf(rows, pausedKeys = setOf("casa")),
        )
    }

    @Test
    fun `with no labels there is no label chip`() {
        assertEquals(emptyList(), labelChipsOf(listOf(home, night), emptySet()))
    }

    @Test
    fun `a typed label is checked like the database will`() {
        val onRule = listOf("Casa")
        assertNull(labelInputProblem("", onRule, onRule))
        assertNull(labelInputProblem("Lavoro", onRule, onRule))
        assertEquals(LabelInputProblem.ALREADY_ON_RULE, labelInputProblem(" casa ", onRule, onRule))
        assertEquals(LabelInputProblem.INVALID, labelInputProblem("x".repeat(25), onRule, onRule))
        assertEquals(LabelInputProblem.RULE_FULL, labelInputProblem("F", listOf("A", "B", "C", "D", "E"), emptyList()))
        val twenty = (1..20).map { "L$it" }
        assertEquals(LabelInputProblem.TOO_MANY_LABELS, labelInputProblem("Nuova", emptyList(), twenty))
        assertNull(labelInputProblem("l3", emptyList(), twenty), "un'etichetta esistente si può sempre riusare")
    }

    @Test
    fun `existing labels are suggested first, without those the rule already has`() {
        assertEquals(listOf("Lavoro", "Auto"), labelSuggestions(listOf("casa"), listOf("Casa", "Lavoro", "Auto", "lavoro")))
    }

    @Test
    fun `a row has a label whatever the case`() {
        assertTrue(row("1", labels = listOf("Casa")).hasLabel("CASA"))
        assertFalse(row("1", labels = listOf("Casa")).hasLabel("Lavoro"))
    }
}
