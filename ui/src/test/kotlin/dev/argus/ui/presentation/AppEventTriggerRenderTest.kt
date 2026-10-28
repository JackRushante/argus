package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** L'approvazione del trigger «app aperta/chiusa» (0.5.1) è resa dai tipi, in inglese e in italiano. */
class AppEventTriggerRenderTest {
    private val youtube = "com.google.android.youtube"

    private fun render(trigger: Trigger, language: RenderLanguage) = RuleRenderMapper.map(
        Automation(
            AutomationId("r"),
            "regola",
            CreatedBy.LLM,
            AutomationStatus.PENDING_APPROVAL,
            trigger,
            listOf(Action.SetRotation(auto = false)),
        ),
        language = language,
    )

    private fun line(trigger: Trigger, language: RenderLanguage) = render(trigger, language).triggerLine

    /** Il risolutore è globale: ogni test lo installa qui e lo toglie sempre, anche se fallisce. */
    private fun <T> withLabels(resolver: (String) -> String?, block: () -> T): T {
        AppLabels.install(resolver)
        try {
            return block()
        } finally {
            AppLabels.install(null)
        }
    }

    @Test
    fun `without a name the package is shown alone`() {
        assertEquals(
            "When: the app $youtube is opened (it comes to the foreground)",
            line(Trigger.AppEvent(youtube, AppEventKind.OPENED), RenderLanguage.EN),
        )
        assertEquals(
            "Quando: l'app $youtube viene chiusa (lascia il primo piano)",
            line(Trigger.AppEvent(youtube, AppEventKind.CLOSED), RenderLanguage.IT),
        )
    }

    @Test
    fun `when the phone knows the app its name comes with the package`() {
        withLabels({ pkg -> if (pkg == youtube) "YouTube" else null }) {
            assertEquals(
                "When: the app YouTube ($youtube) is closed (it leaves the foreground)",
                line(Trigger.AppEvent(youtube, AppEventKind.CLOSED), RenderLanguage.EN),
            )
            assertEquals(
                "Quando: l'app YouTube ($youtube) viene aperta (passa in primo piano)",
                line(Trigger.AppEvent(youtube, AppEventKind.OPENED), RenderLanguage.IT),
            )
            // Un'app che il telefono non conosce resta col solo package.
            assertTrue(
                line(Trigger.AppEvent("org.example.unknown", AppEventKind.OPENED), RenderLanguage.EN)
                    .startsWith("When: the app org.example.unknown is opened"),
            )
        }
    }

    @Test
    fun `a blank name, a name equal to the package or a failing resolver change nothing`() {
        val expected = "When: the app $youtube is opened (it comes to the foreground)"
        val trigger = Trigger.AppEvent(youtube, AppEventKind.OPENED)

        withLabels({ "   " }) { assertEquals(expected, line(trigger, RenderLanguage.EN)) }
        withLabels({ it }) { assertEquals(expected, line(trigger, RenderLanguage.EN)) }
        withLabels({ error("PackageManager non disponibile") }) {
            assertEquals(expected, line(trigger, RenderLanguage.EN))
        }
    }

    @Test
    fun `the name never replaces the package in the approval text`() {
        withLabels({ "Qualcosa di diverso" }) {
            listOf(RenderLanguage.EN, RenderLanguage.IT).forEach { language ->
                AppEventKind.entries.forEach { kind ->
                    val text = line(Trigger.AppEvent(youtube, kind), language)
                    assertTrue(youtube in text, text)
                    assertTrue("Qualcosa di diverso" in text, text)
                }
            }
        }
    }

    @Test
    fun `the icon is the app event one`() {
        assertEquals("app_event", render(Trigger.AppEvent(youtube, AppEventKind.OPENED), RenderLanguage.EN).triggerIconKey)
    }
}
