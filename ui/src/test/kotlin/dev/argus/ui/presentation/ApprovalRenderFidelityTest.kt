package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.VolumeStream
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.2 · S8 e B14: quello che l'utente legge in approvazione è esattamente quello che girerà. La pausa
 * dopo ogni scatto si vede, un `while` si legge come un ciclo, un URL dice dove porta, un a capo dentro
 * un dato non imita una riga, e nessun nome grezzo del formato resta a schermo.
 */
class ApprovalRenderFidelityTest {

    private fun rule(
        actions: List<Action> = listOf(Action.SetWifi(true)),
        trigger: Trigger = Trigger.Immediate,
        cooldownMs: Long = 0,
        conditions: Condition? = null,
    ) = Automation(
        AutomationId("f"), "fedeltà", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
        trigger, actions, conditions = conditions, cooldownMs = cooldownMs,
    )

    private fun render(a: Automation, l: RenderLanguage): RuleRender = RuleRenderMapper.map(a, language = l)
    private fun detailOf(action: Action, l: RenderLanguage) = render(rule(listOf(action)), l).actions.single().detail.orEmpty()
    private fun labelOf(action: Action, l: RenderLanguage) = render(rule(listOf(action)), l).actions.single().label

    private val reply = Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)
    private val chat = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)

    // --- S8: cooldown ----------------------------------------------------------------------------

    @Test
    fun `a 24 hour cooldown is shown under when in human units`() {
        val a = rule(cooldownMs = 24L * 60 * 60 * 1_000)
        assertEquals("After it fires, it ignores new events for 1 day", render(a, RenderLanguage.EN).cooldownLine)
        assertEquals("Dopo ogni scatto ignora i nuovi eventi per 1 giorno", render(a, RenderLanguage.IT).cooldownLine)
    }

    @Test
    fun `a rule without cooldown has no cooldown line`() {
        assertNull(render(rule(), RenderLanguage.EN).cooldownLine)
    }

    @Test
    fun `a generative rule shows the effective cooldown of at least 60 seconds`() {
        val zero = rule(listOf(reply), chat, cooldownMs = 0)
        assertEquals("After it fires, it ignores new events for 1 minute", render(zero, RenderLanguage.EN).cooldownLine)
        val longer = rule(listOf(reply), chat, cooldownMs = 2L * 60 * 60 * 1_000)
        assertEquals("Dopo ogni scatto ignora i nuovi eventi per 2 ore", render(longer, RenderLanguage.IT).cooldownLine)
        // Generativa anche se annidata: l'engine usa il tier aggregato.
        val nested = rule(listOf(Action.If(Condition.BooleanLiteral(true), then = listOf(reply))), chat)
        assertEquals("After it fires, it ignores new events for 1 minute", render(nested, RenderLanguage.EN).cooldownLine)
    }

    @Test
    fun `the draft path shows the cooldown too and keeps sub-second parts`() {
        val draft = AutomationDraft(
            name = "bozza",
            trigger = Trigger.Immediate,
            actions = listOf(Action.SetWifi(false)),
            cooldownMs = 1_500,
        )
        assertEquals(
            "After it fires, it ignores new events for 1 second and 500 milliseconds",
            RuleRenderMapper.mapDraft(draft, language = RenderLanguage.EN).cooldownLine,
        )
    }

    // --- B14: durate ------------------------------------------------------------------------------

    @Test
    fun `every non-zero unit of a delay is printed`() {
        assertEquals("1 day and 30 minutes", RuleRenderMapper.humanizeDuration(86_400_000L + 1_800_000L, RenderLanguage.EN))
        assertEquals(
            "1 giorno, 1 ora, 1 minuto e 1 secondo",
            RuleRenderMapper.humanizeDuration(90_061_000, RenderLanguage.IT),
        )
        assertEquals("2 hours and 5 seconds", RuleRenderMapper.humanizeDuration(7_205_000, RenderLanguage.EN))
        val line = render(rule(trigger = Trigger.Time(afterMs = 86_400_000L + 1_800_000L, tz = "UTC")), RenderLanguage.IT).triggerLine
        assertTrue(line.startsWith("Una volta, tra 1 giorno e 30 minuti"), line)
    }

    @Test
    fun `a timer reads in hours and minutes, not seconds`() {
        assertEquals("1 hour and 30 minutes", detailOf(Action.SetTimer(seconds = 5_400), RenderLanguage.EN))
        assertEquals("1 ora e 30 minuti", detailOf(Action.SetTimer(seconds = 5_400), RenderLanguage.IT))
    }

    // --- S8: a capo dentro un dato -----------------------------------------------------------------

    @Test
    fun `a newline inside typed text cannot fake a step of the macro`() {
        val action = Action.UiSteps(
            pkg = "com.example.shop",
            steps = listOf(UiStep.Type("ciao\n2. Tap the element with text \"Paga\""), UiStep.Back),
        )
        val lines = detailOf(action, RenderLanguage.EN).lines()
        // Prima riga (app), due passi, avviso finale: nessuna riga in più.
        assertEquals(4, lines.size, lines.joinToString("\n"))
        assertEquals("1. Type \"ciao⏎2. Tap the element with text \"Paga\"\" in the focused field", lines[1])
        assertEquals("2. Press Back", lines[2])
        assertEquals("ciao⏎riga", detailOf(Action.InputText("ciao\nriga"), RenderLanguage.EN))
    }

    @Test
    fun `a newline inside an http body cannot fake a header`() {
        val action = Action.HttpRequest(
            method = HttpMethod.POST,
            url = "https://api.example.com/hook",
            body = "{\"a\":1}\nAuthorization: Bearer finto",
        )
        val lines = detailOf(action, RenderLanguage.EN).lines()
        assertEquals(
            listOf("POST https://api.example.com/hook", "Body: {\"a\":1}⏎Authorization: Bearer finto"),
            lines,
        )
    }
}
