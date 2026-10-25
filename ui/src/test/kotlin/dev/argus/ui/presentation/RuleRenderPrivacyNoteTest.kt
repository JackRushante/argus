package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.RuleRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2: la nota privacy dichiara SOLO i dati che lasciano davvero il telefono, in base a trigger,
 * contextSources, azione v2 e variabili nel goal. Ogni parte ha il suo caso, EN e IT.
 */
class RuleRenderPrivacyNoteTest {
    private val whatsapp = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
    private val timeTrigger = Trigger.Time(cron = "0 9 * * *", tz = "Europe/Rome")

    private fun llm(
        goal: String = "riassumi",
        contextSources: List<String> = emptyList(),
        deliver: GenerativeDeliverMode = GenerativeDeliverMode.LOCAL_NOTIFICATION,
        tools: List<String> = emptyList(),
    ) = Action.InvokeLlm(
        goal = goal,
        contextSources = contextSources,
        allowedTools = tools,
        replyTargetSender = false,
        deliver = deliver,
        notificationTitle = if (deliver == GenerativeDeliverMode.LOCAL_NOTIFICATION) "Titolo" else null,
        captureAs = if (deliver == GenerativeDeliverMode.CAPTURE_ONLY) "out" else null,
    )

    private fun v2(goal: String = "rispondi") = Action.InvokeLlmV2(
        goal = goal,
        stateContext = listOf(
            ApprovedStateContext(
                query = StateQuery.Builtin("battery.level"),
                valueType = StateValueType.NUMBER,
                policyVersion = StateQueryPolicy.VERSION,
                integrity = IntegrityLabel.CLEAN,
                confidentiality = ConfidentialityLabel.PRIVATE,
            ),
        ),
        allowedTools = listOf("whatsapp_reply"),
        replyTargetSender = true,
        timeoutMs = 60_000,
    )

    private fun render(trigger: Trigger, vararg actions: Action, language: RenderLanguage): RuleRender =
        RuleRenderMapper.map(
            Automation(
                AutomationId("p"), "privacy", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
                trigger, actions.toList(),
            ),
            language = language,
        )

    @Test fun `non generative rule has no privacy note`() {
        assertNull(render(timeTrigger, Action.SetWifi(true), language = RenderLanguage.EN).privacyNote)
    }

    @Test fun `reply sink with notification context keeps the historical sentence verbatim`() {
        val action = llm(
            contextSources = listOf("notification"),
            deliver = GenerativeDeliverMode.WHATSAPP_REPLY,
            tools = listOf("whatsapp_reply"),
        )
        assertEquals(
            "The notification text will be sent to the configured AI service to generate the reply.",
            render(whatsapp, action, language = RenderLanguage.EN).privacyNote,
        )
        assertEquals(
            "Il testo delle notifiche verrà inviato al servizio AI configurato per generare la risposta.",
            render(whatsapp, action, language = RenderLanguage.IT).privacyNote,
        )
    }

    @Test fun `notification text is not claimed without a notification trigger`() {
        // Sink locale da trigger a tempo: nessun testo di notifica esiste né viene inviato.
        val note = assertNotNull(
            render(timeTrigger, llm(contextSources = listOf("notification")), language = RenderLanguage.EN)
                .privacyNote,
        )
        assertFalse(note.contains("notification text"), note)
        assertEquals(
            "The goal of this rule will be sent to the configured AI service to generate the notification.",
            note,
        )
    }

    @Test fun `notification text requires the notification context source`() {
        val note = assertNotNull(
            render(whatsapp, llm(contextSources = emptyList()), language = RenderLanguage.EN).privacyNote,
        )
        assertFalse(note.contains("notification text"), note)
    }

    @Test fun `state context source discloses device state values`() {
        val en = assertNotNull(
            render(timeTrigger, llm(contextSources = listOf("state")), language = RenderLanguage.EN).privacyNote,
        )
        assertEquals(
            "Values from the device state readers will be sent to the configured AI service " +
                "to generate the notification.",
            en,
        )
        val it = assertNotNull(
            render(timeTrigger, llm(contextSources = listOf("state")), language = RenderLanguage.IT).privacyNote,
        )
        assertEquals(
            "I valori dei reader di stato del dispositivo verranno inviati al servizio AI configurato " +
                "per generare la notifica.",
            it,
        )
    }

    @Test fun `variables used in the goal are disclosed`() {
        val action = llm(goal = "riassumi \${testo}")
        val en = assertNotNull(render(timeTrigger, action, language = RenderLanguage.EN).privacyNote)
        assertEquals(
            "The values of the variables used in the goal will be sent to the configured AI service " +
                "to generate the notification.",
            en,
        )
        val it = assertNotNull(render(timeTrigger, action, language = RenderLanguage.IT).privacyNote)
        assertTrue(it.startsWith("I valori delle variabili usate nell'obiettivo verranno inviati"), it)
    }

    @Test fun `all three parts are listed in a stable order`() {
        val action = llm(
            goal = "rispondi a \${testo}",
            contextSources = listOf("state", "notification"),
            deliver = GenerativeDeliverMode.WHATSAPP_REPLY,
            tools = listOf("whatsapp_reply"),
        )
        assertEquals(
            "The notification text, values from the device state readers and the values of the " +
                "variables used in the goal will be sent to the configured AI service to generate the reply.",
            render(whatsapp, action, language = RenderLanguage.EN).privacyNote,
        )
        assertEquals(
            "Il testo delle notifiche, i valori dei reader di stato del dispositivo e i valori delle " +
                "variabili usate nell'obiettivo verranno inviati al servizio AI configurato per generare " +
                "la risposta.",
            render(whatsapp, action, language = RenderLanguage.IT).privacyNote,
        )
    }

    @Test fun `generative v2 always discloses state and the notification text on a notification trigger`() {
        assertEquals(
            "The notification text and values from the device state readers will be sent to the " +
                "configured AI service to generate the reply.",
            render(whatsapp, v2(), language = RenderLanguage.EN).privacyNote,
        )
        val fromTime = assertNotNull(render(timeTrigger, v2(), language = RenderLanguage.EN).privacyNote)
        assertFalse(fromTime.contains("notification text"), fromTime)
        assertTrue(fromTime.startsWith("Values from the device state readers"), fromTime)
    }

    @Test fun `capture only sink names the value as the result`() {
        val action = llm(
            goal = "calcola \${n}",
            deliver = GenerativeDeliverMode.CAPTURE_ONLY,
        )
        val note = assertNotNull(render(timeTrigger, action, language = RenderLanguage.EN).privacyNote)
        assertTrue(note.endsWith("to generate the value."), note)
    }

    @Test fun `mixed sinks fall back to a neutral result noun`() {
        val note = assertNotNull(
            render(
                timeTrigger,
                llm(deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION),
                llm(deliver = GenerativeDeliverMode.CAPTURE_ONLY),
                language = RenderLanguage.EN,
            ).privacyNote,
        )
        assertTrue(note.endsWith("to generate the result."), note)
    }

    @Test fun `generative actions nested in control flow are disclosed`() {
        val nested = Action.If(
            condition = Condition.BooleanLiteral(true),
            then = listOf(
                Action.While(
                    condition = Condition.BooleanLiteral(true),
                    body = listOf(llm(goal = "x \${a}", contextSources = listOf("state"))),
                    maxIterations = 1,
                ),
            ),
        )
        val note = assertNotNull(render(timeTrigger, nested, language = RenderLanguage.EN).privacyNote)
        assertEquals(
            "Values from the device state readers and the values of the variables used in the goal " +
                "will be sent to the configured AI service to generate the notification.",
            note,
        )
    }

    @Test fun `invoke llm detail lists the context sources and the sender binding`() {
        val action = Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification", "state"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        )
        val en = render(whatsapp, action, language = RenderLanguage.EN).actions.single().detail.orEmpty()
        assertTrue(en.contains("context: notification, state"), en)
        assertTrue(en.contains("reply bound to the sender"), en)
        val it = render(whatsapp, action, language = RenderLanguage.IT).actions.single().detail.orEmpty()
        assertTrue(it.contains("contesto: notification, state"), it)
        assertTrue(it.contains("risposta vincolata al mittente"), it)
    }
}
