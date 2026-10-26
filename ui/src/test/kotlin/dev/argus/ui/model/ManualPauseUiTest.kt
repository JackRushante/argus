package dev.argus.ui.model

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.RuleRenderMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Avvio manuale (F8) e «Pausa automazioni» (F21) nella UI: render dai tipi, stringhe, contratti. */
class ManualPauseUiTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private val englishOwn = strings(english).filterKeys { it.startsWith("manual_") || it.startsWith("pause_") }
    private val italianOwn = strings(italian).filterKeys { it.startsWith("manual_") || it.startsWith("pause_") }

    private fun render(language: RenderLanguage) = RuleRenderMapper.map(
        Automation(
            AutomationId("m"),
            "Garage",
            CreatedBy.LLM,
            AutomationStatus.PENDING_APPROVAL,
            Trigger.Manual,
            listOf(Action.SetWifi(false)),
        ),
        language = language,
    )

    @Test
    fun `the manual trigger is rendered from the type in both languages`() {
        val en = render(RenderLanguage.EN)
        val it = render(RenderLanguage.IT)
        assertTrue(en.triggerLine.startsWith("When: you run it by hand"), en.triggerLine)
        assertTrue(it.triggerLine.startsWith("Quando: la lanci tu a mano"), it.triggerLine)
        assertNotEquals(en.triggerLine, it.triggerLine)
        assertEquals("manual", en.triggerIconKey)
    }

    @Test
    fun `the manual icon key is not the generic fallback`() {
        assertNotEquals(iconFor("unknown_key"), iconFor("manual"))
    }

    @Test
    fun `manual and pause strings exist in both languages with the same placeholders`() {
        assertTrue(englishOwn.isNotEmpty())
        assertEquals(englishOwn.keys, italianOwn.keys)
        englishOwn.forEach { (key, text) ->
            assertEquals(PLACEHOLDER.findAll(text).map { it.value }.toList(),
                PLACEHOLDER.findAll(italianOwn.getValue(key)).map { it.value }.toList(), key)
            assertNotEquals(text, italianOwn.getValue(key), key)
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(italianOwn.getValue(key)), "apostrofo non escapato in it:$key")
        }
    }

    @Test
    fun `a rule that is not manual has no manual entry points and the new callbacks default to no-ops`() {
        val state = AutomationDetailState(
            id = "a",
            name = "regola",
            status = StatusBadge.ARMED,
            rule = RuleRender("Quando: ora", "time", emptyList(), emptyList(), false, null),
            rationale = null,
            warnings = emptyList(),
            canArm = false,
            armBlockedReason = null,
            estimatedLlmCallsPerDay = null,
            recentRuns = emptyList(),
            geofencePreviewLabel = null,
        )
        assertNull(state.manual)
        val list = AutomationListState(rows = emptyList(), filter = StatusFilter.ALL, banner = EngineBanner.NONE, loading = false)
        assertFalse(list.paused)
        assertFalse(ManualEntryUi().paused)
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""")
        val PLACEHOLDER = Regex("""%(\d+\$)?[sd]""")
    }
}
