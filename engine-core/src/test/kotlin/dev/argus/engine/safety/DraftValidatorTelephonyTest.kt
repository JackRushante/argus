package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validazione di send_sms, call_number, send_intent e screen_call (0.8). */
class DraftValidatorTelephonyTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))

    private fun issues(
        vararg actions: Action,
        trigger: Trigger = Trigger.Manual,
        vars: List<VarBinding> = emptyList(),
        conditions: Condition? = null,
    ) = v.validate(
        AutomationDraft(name = "r", trigger = trigger, actions = actions.toList(), vars = vars, conditions = conditions),
        emptySet(),
    )

    private fun errors(list: List<ValidationIssue>) = list.filter { it.severity == Severity.ERROR }.map { it.code }
    private fun warnings(list: List<ValidationIssue>) = list.filter { it.severity == Severity.WARNING }.map { it.code }

    private val missed = Trigger.PhoneState(PhoneEvent.MISSED_CALL)
    private val incoming = Trigger.PhoneState(PhoneEvent.INCOMING_CALL)
    private val sms = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
    private fun payload(name: String, field: TriggerField, regex: String? = null) =
        VarBinding.TriggerPayload(name, field, regex, ConfidentialityLabel.PRIVATE)

    // --- send_sms --------------------------------------------------------------------------------

    @Test
    fun `an sms to a literal number is valid and warns about the cost`() {
        val list = issues(Action.SendSms("+393331112222", "Sono in riunione"))
        assertEquals(emptyList(), errors(list))
        assertEquals(listOf("sms_cost"), warnings(list))
    }

    @Test
    fun `texting back whoever called is valid with the stronger warning`() {
        listOf(missed to TriggerField.NUMBER, sms to TriggerField.NUMBER).forEach { (trigger, field) ->
            val list = issues(
                Action.SendSms("\${caller}", "Ti richiamo più tardi"),
                trigger = trigger,
                vars = listOf(payload("caller", field)),
            )
            assertEquals(emptyList(), errors(list), trigger.toString())
            assertEquals(listOf("sms_cost", "sms_to_caller"), warnings(list), trigger.toString())
        }
    }

    @Test
    fun `the destination cannot be any other variable or a mix of text and variables`() {
        assertTrue(
            "sms_number_not_sender" in errors(
                issues(Action.SendSms("\${body}", "x"), trigger = sms, vars = listOf(payload("body", TriggerField.TEXT))),
            ),
        )
        assertTrue(
            "sms_number_not_sender" in errors(
                issues(
                    Action.SendSms("\${caller}", "x"),
                    trigger = sms,
                    vars = listOf(payload("caller", TriggerField.NUMBER, regex = "(\\d+)")),
                ),
            ),
        )
        val literal = VarBinding.Literal("caller", "+393331112222", VarType.TEXT, ConfidentialityLabel.PRIVATE)
        assertTrue("sms_number_not_sender" in errors(issues(Action.SendSms("\${caller}", "x"), vars = listOf(literal))))
        assertTrue(
            "sms_number_template_invalid" in errors(
                issues(Action.SendSms("+39\${caller}", "x"), trigger = sms, vars = listOf(payload("caller", TriggerField.NUMBER))),
            ),
        )
    }

    @Test
    fun `bad numbers, empty or long texts and the stripped export value are refused`() {
        listOf("", "4242", "*21*3331112222#", "+39 333 1112222", "#31#").forEach { number ->
            assertTrue("sms_number_invalid" in errors(issues(Action.SendSms(number, "x"))), number)
        }
        assertTrue("sms_text_invalid" in errors(issues(Action.SendSms("+393331112222", " "))))
        assertTrue("sms_text_invalid" in errors(issues(Action.SendSms("+393331112222", "x".repeat(481)))))
        assertEquals(emptyList(), errors(issues(Action.SendSms("+393331112222", "x".repeat(480)))))
    }

    // --- call_number -----------------------------------------------------------------------------

    @Test
    fun `a call takes a literal number only and warns`() {
        val list = issues(Action.CallNumber("+393331112222"))
        assertEquals(emptyList(), errors(list))
        assertEquals(listOf("call_cost"), warnings(list))
        listOf("", "*#06#", "*21*3331112222#", "3331112222,1", "\${caller}", "112").forEach { number ->
            assertTrue("call_number_invalid" in errors(issues(Action.CallNumber(number))), number)
        }
    }

    // --- send_intent -----------------------------------------------------------------------------

    @Test
    fun `a broadcast with a literal action is valid and silent`() {
        val list = issues(Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING"))
        assertEquals(emptyList(), errors(list))
        assertEquals(emptyList(), warnings(list))
    }

    @Test
    fun `every intent problem becomes an error`() {
        val cases = mapOf(
            Action.SendIntent(IntentKind.BROADCAST) to "intent_target_missing",
            Action.SendIntent(IntentKind.SERVICE, action = "a.B") to "intent_service_needs_component",
            Action.SendIntent(IntentKind.BROADCAST, action = "a b") to "intent_name_invalid",
            Action.SendIntent(IntentKind.ACTIVITY, pkg = "dev.argus", cls = "dev.argus.MainActivity") to "intent_target_forbidden",
            Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.CALL") to "intent_action_forbidden",
            Action.SendIntent(IntentKind.ACTIVITY, action = "a.B", data = "") to "intent_data_invalid",
            Action.SendIntent(IntentKind.ACTIVITY, action = "a.B", data = "javascript:x") to "intent_data_scheme_forbidden",
            Action.SendIntent(
                IntentKind.BROADCAST,
                action = "a.B",
                extras = List(17) { IntentExtra("k$it", IntentExtraType.BOOLEAN, "true") },
            ) to "intent_too_many_extras",
            Action.SendIntent(
                IntentKind.BROADCAST,
                action = "a.B",
                extras = listOf(IntentExtra("k", IntentExtraType.STRING, "")),
            ) to "intent_extra_invalid",
        )
        cases.forEach { (intent, code) -> assertTrue(code in errors(issues(intent)), "$code: $intent") }
    }

    @Test
    fun `a string extra may carry external data with a warning, other extras stay literal`() {
        val intent = Action.SendIntent(
            IntentKind.BROADCAST,
            action = "com.example.SMS",
            extras = listOf(IntentExtra("body", IntentExtraType.STRING, "\${body}")),
        )
        val list = issues(intent, trigger = sms, vars = listOf(payload("body", TriggerField.TEXT)))
        assertEquals(emptyList(), errors(list))
        assertEquals(listOf("intent_extra_external_value"), warnings(list))
        val literal = VarBinding.Literal("tag", "casa", VarType.TEXT, ConfidentialityLabel.PUBLIC)
        val clean = issues(
            intent.copy(extras = listOf(IntentExtra("tag", IntentExtraType.STRING, "\${tag}"))),
            vars = listOf(literal),
        )
        assertEquals(emptyList(), errors(clean) + warnings(clean))
    }

    // --- screen_call -----------------------------------------------------------------------------

    @Test
    fun `screening an incoming call with clock conditions is valid`() {
        val list = issues(
            Action.ScreenCall(ScreenCallOp.REJECT, onlyUnknownCallers = true),
            Action.SendSms("\${caller}", "Non rispondo agli sconosciuti di notte"),
            trigger = incoming,
            vars = listOf(payload("caller", TriggerField.NUMBER)),
            conditions = Condition.TimeWindow("22:00", "07:00", "Europe/Rome"),
        )
        assertEquals(emptyList(), errors(list))
        assertEquals(emptyList(), errors(issues(Action.ScreenCall(ScreenCallOp.SILENCE), trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+393331112222"))))
    }

    @Test
    fun `screen call needs the incoming call trigger without a sim filter`() {
        listOf(Trigger.Manual, missed, sms, Trigger.PhoneState(PhoneEvent.CALL_ENDED)).forEach { trigger ->
            assertTrue("screen_call_trigger" in errors(issues(Action.ScreenCall(ScreenCallOp.REJECT), trigger = trigger)), "$trigger")
        }
        assertTrue(
            "screen_call_sim_filter" in errors(
                issues(Action.ScreenCall(ScreenCallOp.REJECT), trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, sim = 1)),
            ),
        )
    }

    @Test
    fun `screen call is one top level action`() {
        val nested = Action.If(Condition.BooleanLiteral(true), then = listOf(Action.ScreenCall(ScreenCallOp.REJECT)))
        assertTrue("screen_call_nested" in errors(issues(nested, trigger = incoming)))
        assertTrue(
            "screen_call_duplicate" in errors(
                issues(Action.ScreenCall(ScreenCallOp.REJECT), Action.ScreenCall(ScreenCallOp.SILENCE), trigger = incoming),
            ),
        )
    }

    @Test
    fun `screen call refuses conditions that need a slow reader`() {
        listOf(
            Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "silent"),
            Condition.And(listOf(Condition.TimeWindow("08:00", "18:00", "Europe/Rome"), Condition.LocationIn(45.0, 9.0, 200.0))),
            Condition.AppInForeground("com.example.app"),
        ).forEach { condition ->
            assertTrue(
                "screen_call_condition_slow" in errors(issues(Action.ScreenCall(ScreenCallOp.SILENCE), trigger = incoming, conditions = condition)),
                condition.toString(),
            )
        }
    }

    @Test
    fun `screen call cannot wait for a confirmation or span an interval`() {
        val draft = AutomationDraft(
            name = "r",
            trigger = incoming,
            actions = listOf(Action.ScreenCall(ScreenCallOp.REJECT)),
            confirm = ConfirmSpec(),
        )
        assertTrue("screen_call_confirm_exit" in errors(v.validate(draft, emptySet())))
    }
}
