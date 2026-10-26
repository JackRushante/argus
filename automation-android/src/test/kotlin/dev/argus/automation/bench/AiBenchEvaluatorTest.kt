package dev.argus.automation.bench

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.Condition
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Il valutatore del banco contro bozze costruite a mano: se sbaglia lui, ogni percentuale e' falsa. */
class AiBenchEvaluatorTest {
    private val today: LocalDate get() = LocalDate.now(world.zone)

    @Test
    fun `a correct draft passes and every expectation is checked`() {
        val case = caseOf(
            """{"id":"e.ok","category":"c","lang":"it","text":"Spegni subito il non disturbare",
               "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["set_dnd"],
                         "actions_exclude":["run_shell"],"params":{"set_dnd.mode":"OFF"}}}""",
        )
        assertTrue(eval(case, draft(Trigger.Immediate, Action.SetDnd(DndMode.OFF))).passed)

        val wrongParam = eval(case, draft(Trigger.Immediate, Action.SetDnd(DndMode.PRIORITY)))
        assertEquals(listOf(FailureCategory.WRONG_PARAM), wrongParam.failures.map { it.category })

        val wrongAction = eval(case, draft(Trigger.Immediate, Action.SetWifi(false)))
        assertTrue(FailureCategory.WRONG_ACTION in wrongAction.failures.map { it.category })

        val forbidden = eval(case, draft(Trigger.Immediate, Action.SetDnd(DndMode.OFF), Action.RunShell("ls")))
        assertTrue(forbidden.failures.any { it.category == FailureCategory.WRONG_ACTION && "run_shell" in it.message })

        val wrongTrigger = eval(case, draft(Trigger.Time(afterMs = 5_000, tz = ZONE), Action.SetDnd(DndMode.OFF)))
        assertEquals(listOf(FailureCategory.WRONG_TRIGGER), wrongTrigger.failures.map { it.category })
    }

    @Test
    fun `validator errors and unavailable capabilities fail the draft`() {
        val case = caseOf(
            """{"id":"e.val","category":"c","lang":"it","text":"Fra poco accendi il Wi-Fi",
               "expect":{"outcome":"draft","trigger":{"type":"time"},"actions_include":["set_wifi"]}}""",
        )
        // afterMs sotto il minimo di 1 s: errore del DraftValidator.
        val tooShort = eval(case, draft(Trigger.Time(afterMs = 500, tz = ZONE), Action.SetWifi(true)))
        assertTrue(tooShort.failures.any { it.category == FailureCategory.VALIDATOR && "after_ms_invalid" in it.message })

        // Senza Shizuku set_wifi non e' disponibile: la revisione dell'app lo segnalerebbe ERRORE.
        val noShizuku = caseOf(
            """{"id":"e.cap","category":"c","lang":"it","text":"Fra poco accendi il Wi-Fi","manifest":"no_shizuku",
               "expect":{"outcome":"draft","trigger":{"type":"time"},"actions_include":["set_wifi"]}}""",
        )
        val good = draft(Trigger.Time(afterMs = 5_000, tz = ZONE), Action.SetWifi(true))
        assertTrue(eval(case, good).passed)
        val unavailable = eval(noShizuku, good)
        assertEquals(listOf(FailureCategory.CAPABILITY), unavailable.failures.map { it.category })
    }

    @Test
    fun `outcome mismatches are classified`() {
        val clarification = caseOf(
            """{"id":"e.clar","category":"c","lang":"it","text":"Imposta una sveglia","expect":{"outcome":"clarification"}}""",
        )
        val anyRefusal = caseOf(
            """{"id":"e.none","category":"c","lang":"it","text":"Rispondi a Laura","expect":{"outcome":"no_draft"}}""",
        )
        val draftCase = caseOf(
            """{"id":"e.draft","category":"c","lang":"it","text":"Accendi la torcia",
               "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["set_flashlight"]}}""",
        )
        val aDraft = draft(Trigger.Immediate, Action.SetFlashlight(true))
        assertEquals(
            listOf("unexpected_draft"),
            eval(clarification, aDraft).failures.map { it.category },
        )
        assertTrue(eval(clarification, CompileResult("Quale ora?", null, "clarification_required")).passed)
        assertEquals(
            listOf("unexpected_clarification"),
            eval(draftCase, CompileResult("Quale?", null, "clarification_required")).failures.map { it.category },
        )
        assertEquals(
            listOf("unexpected_unsupported"),
            eval(draftCase, CompileResult("No", null, "unsupported_capability")).failures.map { it.category },
        )
        assertEquals(
            listOf("unexpected_limit"),
            eval(clarification, CompileResult("Troppo", null, "limit_exceeded")).failures.map { it.category },
        )
        // no_draft accetta ogni risposta senza bozza, compreso un rifiuto motivato del modello.
        assertTrue(eval(anyRefusal, CompileResult("Non in whitelist", null, "contact_not_whitelisted")).passed)
        assertTrue(eval(anyRefusal, CompileResult("Quale?", null, "clarification_required")).passed)
        assertFalse(eval(anyRefusal, aDraft).passed)
    }

    @Test
    fun `format and transport errors are never mistaken for answers`() {
        val case = caseOf(
            """{"id":"e.fmt","category":"c","lang":"it","text":"Imposta una sveglia","expect":{"outcome":"clarification"}}""",
        )
        listOf("draft_missing", "draft_invalid", "meta_parse_error", "meta_json_missing", "output_truncated", "empty_response")
            .forEach { code ->
                val evaluation = eval(case, CompileResult("", null, code))
                assertEquals(listOf(FailureCategory.FORMAT), evaluation.failures.map { it.category }, code)
                assertEquals("format_error:$code", evaluation.actual)
            }
        listOf("bridge_rate_limit", "bridge_network", "bridge_http_503", "budget_exceeded").forEach { code ->
            assertEquals(
                listOf(FailureCategory.TRANSPORT),
                eval(case, CompileResult("", null, code)).failures.map { it.category },
                code,
            )
        }
        assertEquals(ActualKind.FORMAT_ERROR, CompileEvaluator.classify(CompileResult("x", null, null)))
        assertEquals(ActualKind.REFUSAL, CompileEvaluator.classify(CompileResult("x", null, "contact_not_whitelisted")))
    }

    @Test
    fun `cron expectations are checked by firing times not by string`() {
        val case = caseOf(
            """{"id":"e.cron","category":"c","lang":"it","text":"Ogni giorno alle 22:30 metti il non disturbare",
               "expect":{"outcome":"draft","trigger":{"type":"time","cron":{"present":true}},
                         "cron_fires":["2026-11-02T22:30","2026-11-03T22:30"],
                         "cron_silent":["2026-11-02T10:30","2026-11-02T22:31"],"actions_include":["set_dnd"]}}""",
        )
        val dnd = Action.SetDnd(DndMode.PRIORITY)
        assertTrue(eval(case, draft(Trigger.Time(cron = "30 22 * * *", tz = ZONE), dnd)).passed)
        // Altra scrittura dello stesso orario: accettata.
        assertTrue(eval(case, draft(Trigger.Time(cron = "30 22 */1 * 0-6", tz = ZONE), dnd)).passed)
        val wrong = eval(case, draft(Trigger.Time(cron = "30 10 * * *", tz = ZONE), dnd))
        assertEquals(3, wrong.failures.size, wrong.failures.toString())
        assertTrue(wrong.failures.all { it.category == FailureCategory.WRONG_TRIGGER })
        // Il fuso deve essere quello del telefono.
        val utc = eval(case, draft(Trigger.Time(cron = "30 22 * * *", tz = "UTC"), dnd))
        assertTrue(utc.failures.any { "tz" in it.message })
        // Un at al posto del cron.
        val at = eval(case, draft(Trigger.Time(at = LocalDateTime.now().plusDays(1).toString(), tz = ZONE), dnd))
        assertTrue(at.failures.isNotEmpty())
    }

    @Test
    fun `absolute instants are checked against the calendar and must be in the future`() {
        val case = caseOf(
            """{"id":"e.at","category":"c","lang":"it","text":"Domani alle 15 apri il sito",
               "expect":{"outcome":"draft","trigger":{"type":"time","at":{"present":true}},
                         "at_datetime":{"days_from_today":1,"time":"15:00"},"actions_include":["open_url"]}}""",
        )
        val url = Action.OpenUrl("https://example.org")
        val tomorrow = today.plusDays(1).atTime(15, 0).toString()
        assertTrue(eval(case, draft(Trigger.Time(at = tomorrow, tz = ZONE), url)).passed)
        val wrongTime = today.plusDays(1).atTime(16, 30).toString()
        assertTrue(eval(case, draft(Trigger.Time(at = wrongTime, tz = ZONE), url)).failures.any { "ora attesa" in it.message })
        val wrongDay = today.plusDays(2).atTime(15, 0).toString()
        assertTrue(eval(case, draft(Trigger.Time(at = wrongDay, tz = ZONE), url)).failures.any { "giorno atteso" in it.message })
        val past = today.minusDays(1).atTime(15, 0).toString()
        assertTrue(eval(case, draft(Trigger.Time(at = past, tz = ZONE), url)).failures.any { "gia' passato" in it.message })
    }

    @Test
    fun `contacts are matched by their real id after alias restoration`() {
        val case = caseOf(
            """{"id":"e.contact","category":"c","lang":"it","text":"Quando Mamma mi scrive vibra",
               "expect":{"outcome":"draft","trigger":{"type":"notification","pkg":["com.whatsapp","com.whatsapp.w4b"]},
                         "contact":"Mamma","actions_include":["vibrate"]}}""",
        )
        val mamma = world.contactId("Mamma")!!
        val ok = draft(Trigger.Notification(pkg = "com.whatsapp", conversationId = mamma), Action.Vibrate(500))
        assertTrue(eval(case, ok).passed)
        val wrongContact = draft(
            Trigger.Notification(pkg = "com.whatsapp", conversationId = world.contactId("Giulia")),
            Action.Vibrate(500),
        )
        assertTrue(eval(case, wrongContact).failures.any { "Mamma" in it.message })
        val byName = draft(Trigger.Notification(pkg = "com.whatsapp", conversationId = "Mamma"), Action.Vibrate(500))
        assertFalse(eval(case, byName).passed, "un nome al posto dell'alias non e' un id")
        val wrongPkg = draft(Trigger.Notification(pkg = "org.telegram.messenger", conversationId = mamma), Action.Vibrate(500))
        assertTrue(eval(case, wrongPkg).failures.any { it.category == FailureCategory.WRONG_TRIGGER })
    }

    @Test
    fun `conditions variables and control flow are checked`() {
        val case = caseOf(
            """{"id":"e.cond","category":"c","lang":"it","text":"Quando collego il caricabatterie, se la batteria e' sotto il 20%, vibra",
               "expect":{"outcome":"draft","trigger":{"type":"connectivity","medium":"POWER","state":"CONNECTED"},
                         "actions_include":["vibrate"],"conditions_include":["state_equals|state_compare"],
                         "conditions_text":["battery","\\b20\\b"]}}""",
        )
        val trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED)
        val low = Condition.StateEquals("battery", dev.argus.engine.model.CmpOp.LT, "20")
        assertTrue(eval(case, draft(trigger, Action.Vibrate(200), conditions = low)).passed)
        val high = Condition.StateEquals("battery", dev.argus.engine.model.CmpOp.LT, "50")
        assertTrue(eval(case, draft(trigger, Action.Vibrate(200), conditions = high)).failures.any {
            it.category == FailureCategory.WRONG_CONDITION
        })
        assertTrue(eval(case, draft(trigger, Action.Vibrate(200))).failures.any {
            it.category == FailureCategory.WRONG_CONDITION
        })

        val flow = caseOf(
            """{"id":"e.flow","category":"c","lang":"en","text":"Flip a coin",
               "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["vibrate"],
                         "control_flow":["if"],"vars":["random_int"]}}""",
        )
        val flat = eval(flow, draft(Trigger.Immediate, Action.Vibrate(200)))
        assertEquals(
            setOf(FailureCategory.MISSING_FLOW, FailureCategory.MISSING_VARS),
            flat.failures.map { it.category }.toSet(),
        )
    }

    @Test
    fun `matchers support every documented operator`() {
        fun m(json: String) = Json.parseToJsonElement(json)
        fun matches(actual: String?, matcher: String) =
            JsonMatch.matches(actual?.let { Json.parseToJsonElement(it) }, m(matcher))

        assertTrue(matches("\"OFF\"", "\"off\""))
        assertTrue(matches("\"Perché\"", "\"perche\""))
        assertTrue(matches("30000", "30000"))
        assertTrue(matches("\"30000\"", "30000"))
        assertTrue(matches("true", "true"))
        assertFalse(matches("false", "true"))
        assertTrue(matches("\"a\"", "[\"b\", \"a\"]"))
        assertTrue(matches(null, "null"))
        assertTrue(matches("null", "null"))
        assertTrue(matches("\"x\"", "{\"present\": true}"))
        assertFalse(matches(null, "{\"present\": true}"))
        assertFalse(matches("null", "{\"present\": true}"))
        assertTrue(matches(null, "{\"present\": false}"))
        assertTrue(matches("\"Wi-Fi CasaMia\"", "{\"contains\": \"casamia\"}"))
        assertTrue(matches("[\"web.search\",\"whatsapp_reply\"]", "{\"contains\": [\"whatsapp_reply\", \"web.search\"]}"))
        assertFalse(matches("[\"whatsapp_reply\"]", "{\"contains\": \"web.search\"}"))
        assertTrue(matches("\"abc\"", "{\"contains_any\": [\"x\", \"b\"]}"))
        assertTrue(matches("15", "{\"range\": [10, 20]}"))
        assertFalse(matches("21", "{\"range\": [10, 20]}"))
        assertTrue(matches("\"Id-42\"", "{\"regex\": \"id-\\\\d+\"}"))
        assertTrue(matches("\"abc\"", "{\"not\": {\"contains\": \"zzz\"}}"))
        assertTrue(matches("\"abc\"", "{\"any\": [\"zzz\", {\"contains\": \"b\"}]}"))
        assertFailsWith<BenchException> { JsonMatch.validate(m("{\"range\": [3, 1]}"), "t") }
        assertFailsWith<BenchException> { JsonMatch.validate(m("{\"regex\": \"(\"}"), "t") }
        assertFailsWith<BenchException> { JsonMatch.validate(m("{\"contains\": 3}"), "t") }
        assertFailsWith<BenchException> { JsonMatch.validate(m("{\"present\": \"yes\"}"), "t") }
        assertFailsWith<BenchException> { JsonMatch.validate(m("{\"present\": true, \"contains\": \"x\"}"), "t") }
        assertFailsWith<BenchException> { JsonMatch.validate(m("[]"), "t") }
        assertTrue(JsonMatch.matches(JsonPrimitive("x"), JsonPrimitive("X")))
        assertFalse(JsonMatch.matches(JsonNull, JsonPrimitive("x")))
    }

    @Test
    fun `generative answers are checked for language links numbers and content`() {
        val price = act("it", "Dimmi il prezzo del bitcoin in euro", number = true)
        fun check(case: ActCase, text: String?, code: String? = null) =
            ActEvaluator.evaluate(case, if (text != null) ActResult(text, null) else ActResult(null, code))

        assertTrue(check(price, "Il bitcoin vale circa 61.000 euro oggi.").passed)
        assertEquals(
            listOf(FailureCategory.ACT_NUMBER),
            check(price, "Il bitcoin oggi vale molto.").failures.map { it.category },
        )
        assertEquals(
            listOf(FailureCategory.ACT_LANGUAGE),
            check(price, "Bitcoin is currently worth about 61,000 dollars today and the trend is up.").failures.map { it.category },
        )
        assertEquals(
            listOf(FailureCategory.ACT_LINK),
            check(price, "Il bitcoin vale 61.000 euro [fonte](https://example.com).").failures.map { it.category },
        )
        assertTrue(check(price, "Il bitcoin vale 61.000 euro, vedi https://example.com").failures.any { it.category == FailureCategory.ACT_LINK })
        // Un prezzo nudo non permette di stabilire la lingua: passa.
        assertTrue(check(price, "61.000 €").passed)
        val english = act("en", "Tell me the current price of gold", number = true)
        assertTrue(check(english, "Gold is trading at about 2,400 dollars an ounce right now.").passed)
        assertEquals(
            listOf(FailureCategory.ACT_LANGUAGE),
            check(english, "L'oro vale circa 2.400 dollari all'oncia, il prezzo è alto oggi.").failures.map { it.category },
        )
        val battery = act("it", "Dimmi la batteria", mustContain = listOf("73"))
        assertTrue(check(battery, "La batteria è al 73%, il telefono è carico.").passed)
        assertEquals(
            listOf(FailureCategory.ACT_CONTENT),
            check(battery, "La batteria è quasi carica, il telefono è a posto.").failures.map { it.category },
        )
        assertEquals(listOf(FailureCategory.TRANSPORT), check(price, null, "bridge_network").failures.map { it.category })
        assertEquals(listOf(FailureCategory.ACT_EMPTY), check(price, null, "empty_response").failures.map { it.category })
    }

    // --- supporti -------------------------------------------------------------------------------

    private fun caseOf(json: String): BenchCase = BenchCases.parseCompile("[$json]").single()

    private fun draft(
        trigger: Trigger,
        vararg actions: Action,
        conditions: Condition? = null,
    ) = AutomationDraft(name = "regola", trigger = trigger, actions = actions.toList(), conditions = conditions)

    private fun eval(case: BenchCase, draft: AutomationDraft): Evaluation =
        eval(case, CompileResult("ok", draft, null))

    private fun eval(case: BenchCase, result: CompileResult): Evaluation =
        CompileEvaluator.evaluate(case, result, world, today)

    private fun act(lang: String, goal: String, number: Boolean = false, mustContain: List<String> = emptyList()) =
        ActCase("a.$lang", lang, goal, web = false, contextSources = emptyList(), expectNumber = number,
            mustContainAny = mustContain, note = null)

    private companion object {
        const val ZONE = BenchWorld.ZONE_ID
        val world: BenchWorld by lazy { runBlocking { BenchWorld.create() } }
    }
}
