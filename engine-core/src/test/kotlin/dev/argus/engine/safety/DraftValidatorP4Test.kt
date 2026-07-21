package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Copertura dei bound P4 §2.5 del DraftValidator: control-flow, variabili, interpolazione, budget. */
class DraftValidatorP4Test {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun errors(issues: List<ValidationIssue>) =
        issues.filter { it.severity == Severity.ERROR }.map { it.code }

    private val timeTrigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
    private fun draft(
        actions: List<Action>,
        vars: List<VarBinding> = emptyList(),
        trigger: Trigger = timeTrigger,
        conditions: Condition? = null,
    ) = AutomationDraft(name = "p4", trigger = trigger, actions = actions, vars = vars, conditions = conditions)

    private val flowCond = Condition.TimeWindow("22:00", "07:00", "Europe/Rome")
    private fun literal(
        name: String,
        value: String,
        type: VarType = VarType.TEXT,
        confidentiality: ConfidentialityLabel = ConfidentialityLabel.PUBLIC,
    ) = VarBinding.Literal(name, value, type, confidentiality)

    private fun payload(
        name: String,
        field: TriggerField,
        regex: String? = null,
        confidentiality: ConfidentialityLabel = ConfidentialityLabel.PRIVATE,
    ) = VarBinding.TriggerPayload(name, field, regex, confidentiality)

    private fun state(
        name: String,
        query: StateQuery,
        type: StateValueType = StateValueType.TEXT,
        policyVersion: Int = StateQueryPolicy.VERSION,
        confidentiality: ConfidentialityLabel = StateContextClassification.minimumConfidentiality(query),
    ) = VarBinding.State(name, query, type, policyVersion, confidentiality)

    // --- Regola P4 valida (torcia lampeggiante) ----------------------------------------------

    @Test fun `valid flashing torch while has no errors`() {
        val d = draft(
            actions = listOf(
                Action.While(
                    condition = Condition.StateCompare(
                        StateQuery.Builtin(StateKeys.BATTERY),
                        StateValueType.NUMBER,
                        CmpOp.LT,
                        "20",
                    ),
                    body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false)),
                    maxIterations = 20,
                    delayBetweenMs = 500,
                ),
            ),
        )
        assertEquals(emptyList(), errors(v.validate(d, emptySet())))
    }

    // --- Variabili: numero, nomi, unicità, forma ----------------------------------------------

    @Test fun `variable count is bounded at 16`() {
        fun vars(n: Int) = (1..n).map { literal("v$it", "1") }
        assertFalse("too_many_vars" in errors(v.validate(draft(listOf(Action.SetWifi(true)), vars(16)), emptySet())))
        assertTrue("too_many_vars" in errors(v.validate(draft(listOf(Action.SetWifi(true)), vars(17)), emptySet())))
    }

    @Test fun `variable names must match regex and be unique`() {
        assertTrue(
            "var_name_invalid" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), listOf(literal("Bad", "1"))), emptySet()),
            ),
        )
        assertTrue(
            "var_name_duplicate" in errors(
                v.validate(
                    draft(
                        listOf(Action.SetWifi(true)),
                        listOf(literal("x", "1"), literal("x", "2")),
                    ),
                    emptySet(),
                ),
            ),
        )
    }

    @Test fun `numeric literal must be numeric and state binding must be a valid reader`() {
        assertTrue(
            "var_literal_type_invalid" in errors(
                v.validate(
                    draft(listOf(Action.SetWifi(true)), listOf(literal("n", "abc", VarType.NUMBER))),
                    emptySet(),
                ),
            ),
        )
        assertTrue(
            "var_state_query_invalid" in errors(
                v.validate(
                    draft(listOf(Action.SetWifi(true)), listOf(state("s", StateQuery.Builtin("unknown")))),
                    emptySet(),
                ),
            ),
        )
    }

    @Test fun `trigger payload binding requires a trigger that carries a payload`() {
        val payloadVar = listOf<VarBinding>(payload("t", TriggerField.TEXT))
        // Su Time (nessun payload) → errore.
        assertTrue(
            "var_trigger_payload_unsupported" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), payloadVar), emptySet()),
            ),
        )
        // Su Notification → ammesso.
        val notif = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        assertFalse(
            "var_trigger_payload_unsupported" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), payloadVar, trigger = notif), emptySet()),
            ),
        )
    }

    @Test fun `trigger payload fields are checked against the concrete trigger family`() {
        val notification = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        val sms = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
        val call = Trigger.PhoneState(PhoneEvent.INCOMING_CALL)

        assertFalse(
            "var_trigger_field_unsupported" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), listOf(payload("title", TriggerField.TITLE)), notification), emptySet()),
            ),
        )
        assertTrue(
            "var_trigger_field_unsupported" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), listOf(payload("title", TriggerField.TITLE)), sms), emptySet()),
            ),
        )
        assertFalse(
            "var_trigger_field_unsupported" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), listOf(payload("number", TriggerField.NUMBER)), sms), emptySet()),
            ),
        )
        assertTrue(
            "var_trigger_field_unsupported" in errors(
                v.validate(draft(listOf(Action.SetWifi(true)), listOf(payload("text", TriggerField.TEXT)), call), emptySet()),
            ),
        )
    }

    @Test fun `binding classification and extraction regex fail closed`() {
        val battery = StateQuery.Builtin(StateKeys.BATTERY)
        val badState = listOf(
            state("wrong_type", battery, StateValueType.TEXT),
            state("old_policy", battery, StateValueType.NUMBER, policyVersion = StateQueryPolicy.VERSION - 1),
            state(
                "underclassified",
                StateQuery.SystemProperty("ro.build.version.sdk"),
                StateValueType.TEXT,
                confidentiality = ConfidentialityLabel.PUBLIC,
            ),
        )
        val stateErrors = errors(v.validate(draft(listOf(Action.SetWifi(true)), badState), emptySet()))
        assertTrue("var_state_type_invalid" in stateErrors)
        assertTrue("var_state_policy_incompatible" in stateErrors)
        assertTrue("var_state_underclassified" in stateErrors)

        val sms = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
        assertTrue(
            "var_extraction_regex_invalid" in errors(
                v.validate(
                    draft(listOf(Action.SetWifi(true)), listOf(payload("otp", TriggerField.TEXT, "(")), sms),
                    emptySet(),
                ),
            ),
        )
        assertTrue(
            "var_trigger_underclassified" in errors(
                v.validate(
                    draft(
                        listOf(Action.SetWifi(true)),
                        listOf(payload("otp", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PUBLIC)),
                        sms,
                    ),
                    emptySet(),
                ),
            ),
        )
    }

    // --- Nesting / conteggio azioni -----------------------------------------------------------

    private fun nestedIf(depth: Int): Action =
        if (depth == 0) Action.SetWifi(true)
        else Action.If(condition = flowCond, then = listOf(nestedIf(depth - 1)))

    @Test fun `if while nesting depth is bounded at 4`() {
        assertFalse("flow_too_deep" in errors(v.validate(draft(listOf(nestedIf(4))), emptySet())))
        assertTrue("flow_too_deep" in errors(v.validate(draft(listOf(nestedIf(5))), emptySet())))
    }

    @Test fun `total flattened action count is bounded at 64`() {
        fun whileWithBody(n: Int) = Action.While(flowCond, List(n) { Action.SetWifi(true) }, maxIterations = 1)
        // While + 63 body = 64 totali → ok. While + 64 = 65 → errore.
        assertFalse("too_many_actions_total" in errors(v.validate(draft(listOf(whileWithBody(63))), emptySet())))
        assertTrue("too_many_actions_total" in errors(v.validate(draft(listOf(whileWithBody(64))), emptySet())))
    }

    @Test fun `top level also allows 64 total actions`() {
        assertFalse(
            "too_many_actions" in errors(v.validate(draft(List(64) { Action.SetWifi(true) }), emptySet())),
        )
        assertTrue(
            "too_many_actions_total" in errors(v.validate(draft(List(65) { Action.SetWifi(true) }), emptySet())),
        )
    }

    @Test fun `flow conditions share the global condition budget`() {
        val actions = List(22) {
            Action.If(
                Condition.And(listOf(Condition.BooleanLiteral(true), Condition.BooleanLiteral(true))),
                listOf(Action.SetWifi(true)),
            )
        }
        val result = errors(v.validate(draft(actions, conditions = Condition.BooleanLiteral(true)), emptySet()))
        assertTrue("conditions_too_many" in result)
    }

    // --- Bound while --------------------------------------------------------------------------

    @Test fun `while iterations and delay are bounded`() {
        fun whileIter(n: Int) = draft(listOf(Action.While(flowCond, listOf(Action.SetWifi(true)), maxIterations = n)))
        assertFalse("while_iterations_invalid" in errors(v.validate(whileIter(1), emptySet())))
        assertFalse("while_iterations_invalid" in errors(v.validate(whileIter(1_000), emptySet())))
        assertTrue("while_iterations_invalid" in errors(v.validate(whileIter(0), emptySet())))
        assertTrue("while_iterations_invalid" in errors(v.validate(whileIter(1_001), emptySet())))

        fun whileDelay(ms: Long) =
            draft(listOf(Action.While(flowCond, listOf(Action.SetWifi(true)), maxIterations = 1, delayBetweenMs = ms)))
        assertFalse("while_delay_invalid" in errors(v.validate(whileDelay(3_600_000), emptySet())))
        assertTrue("while_delay_invalid" in errors(v.validate(whileDelay(3_600_001), emptySet())))
        assertTrue("while_delay_invalid" in errors(v.validate(whileDelay(-1), emptySet())))
    }

    @Test fun `wait makes timed body sequences explicit and is bounded`() {
        val flashing = draft(
            listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    listOf(Action.SetFlashlight(true), Action.Wait(500), Action.SetFlashlight(false)),
                    maxIterations = 20,
                    delayBetweenMs = 500,
                ),
            ),
        )
        assertFalse("wait_duration_invalid" in errors(v.validate(flashing, emptySet())))
        assertTrue(
            "wait_duration_invalid" in errors(
                v.validate(draft(listOf(Action.Wait(0))), emptySet()),
            ),
        )
        assertTrue(
            "wait_duration_invalid" in errors(
                v.validate(draft(listOf(Action.Wait(3_600_001))), emptySet()),
            ),
        )
    }

    @Test fun `empty control flow branches are rejected`() {
        assertTrue(
            "flow_empty_branch" in errors(
                v.validate(draft(listOf(Action.While(flowCond, emptyList(), maxIterations = 1))), emptySet()),
            ),
        )
        assertTrue(
            "flow_empty_branch" in errors(
                v.validate(draft(listOf(Action.If(flowCond, emptyList(), emptyList()))), emptySet()),
            ),
        )
    }

    // --- Budget tempo worst-case --------------------------------------------------------------

    @Test fun `worst case time budget is capped at 6h`() {
        val heavy = Action.While(flowCond, listOf(Action.SetWifi(true)), maxIterations = 1_000, delayBetweenMs = 3_600_000)
        assertTrue("time_budget_exceeded" in errors(v.validate(draft(listOf(heavy)), emptySet())))
        val light = Action.While(flowCond, listOf(Action.SetWifi(true)), maxIterations = 10, delayBetweenMs = 1_000)
        assertFalse("time_budget_exceeded" in errors(v.validate(draft(listOf(light)), emptySet())))
    }

    // --- VarCompare ---------------------------------------------------------------------------

    @Test fun `var compare must reference a declared variable`() {
        val d = draft(listOf(Action.If(Condition.VarCompare("missing", CmpOp.EQ, "x"), listOf(Action.SetWifi(true)))))
        assertTrue("var_compare_undeclared" in errors(v.validate(d, emptySet())))
    }

    @Test fun `numeric operator on a TEXT variable is allowed and coerced at runtime`() {
        val notif = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        // device-found: una TriggerPayload TEXT (o un output captureAs LLM, sempre TEXT) confrontata
        // con GT NON è più un errore: GT/LT su TEXT è ammesso e coercizzato numericamente a runtime
        // (fail-closed se non parsabile). Sblocca il pattern Tasker "chiedi un numero all'AI, poi >".
        val textVar = draft(
            actions = listOf(Action.If(Condition.VarCompare("t", CmpOp.GT, "5"), listOf(Action.SetWifi(true)))),
            vars = listOf(payload("t", TriggerField.TEXT)),
            trigger = notif,
        )
        assertFalse("var_compare_type_invalid" in errors(v.validate(textVar, emptySet())))

        // Literal NUMBER: GT ammesso esattamente come prima (invariato).
        val numVar = draft(
            actions = listOf(Action.If(Condition.VarCompare("n", CmpOp.GT, "5"), listOf(Action.SetWifi(true)))),
            vars = listOf(literal("n", "0", VarType.NUMBER)),
        )
        assertFalse("var_compare_type_invalid" in errors(v.validate(numVar, emptySet())))
    }

    @Test fun `var compare is rejected among trigger-time conditions`() {
        val d = draft(
            actions = listOf(Action.SetWifi(true)),
            conditions = Condition.VarCompare("x", CmpOp.EQ, "y"),
        )
        assertTrue("var_compare_outside_flow" in errors(v.validate(d, emptySet())))
    }

    @Test fun `var compare requires exactly one rhs and type compatible operators`() {
        val vars = listOf(
            literal("text", "abc"),
            literal("number", "1", VarType.NUMBER),
            literal("flag", "true", VarType.BOOLEAN),
        )
        fun validate(condition: Condition.VarCompare) = errors(
            v.validate(draft(listOf(Action.If(condition, listOf(Action.SetWifi(true)))), vars), emptySet()),
        )

        assertTrue("var_compare_rhs_invalid" in validate(Condition.VarCompare("text", CmpOp.EQ)))
        assertTrue(
            "var_compare_rhs_invalid" in validate(
                Condition.VarCompare("text", CmpOp.EQ, expected = "x", expectedVar = "text"),
            ),
        )
        // GT/LT su TEXT sono ammessi (coercizione numerica a runtime, device-found).
        assertFalse("var_compare_type_invalid" in validate(Condition.VarCompare("text", CmpOp.GT, expected = "1")))
        assertFalse("var_compare_type_invalid" in validate(Condition.VarCompare("text", CmpOp.LT, expected = "1")))
        // BOOLEAN resta escluso dagli operatori numerici.
        assertTrue("var_compare_type_invalid" in validate(Condition.VarCompare("flag", CmpOp.GT, expected = "true")))
        assertTrue("var_compare_type_invalid" in validate(Condition.VarCompare("flag", CmpOp.CONTAINS, expected = "true")))
        assertTrue("var_compare_type_invalid" in validate(Condition.VarCompare("number", CmpOp.EQ, expected = "NaN")))
        assertFalse("var_compare_type_invalid" in validate(Condition.VarCompare("flag", CmpOp.EQ, expected = "false")))
    }

    @Test fun `parity operators are unary, allowed on NUMBER and coercible TEXT, rejected with rhs or on BOOLEAN`() {
        val notif = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        val vars = listOf(
            literal("number", "1", VarType.NUMBER),
            literal("flag", "true", VarType.BOOLEAN),
        )
        fun validate(condition: Condition.VarCompare, bindings: List<VarBinding> = vars, trigger: Trigger = timeTrigger) =
            errors(v.validate(draft(listOf(Action.If(condition, listOf(Action.SetWifi(true)))), bindings, trigger), emptySet()))

        // NUMBER + IS_EVEN/IS_ODD senza RHS → valido.
        assertFalse("var_compare_type_invalid" in validate(Condition.VarCompare("number", CmpOp.IS_EVEN)))
        assertFalse("var_compare_rhs_invalid" in validate(Condition.VarCompare("number", CmpOp.IS_ODD)))

        // TEXT coercibile a intero a runtime (come GT/LT) → valido, nessun errore di tipo.
        val textPayload = listOf<VarBinding>(payload("t", TriggerField.TEXT))
        assertFalse(
            "var_compare_type_invalid" in validate(Condition.VarCompare("t", CmpOp.IS_EVEN), textPayload, notif),
        )

        // RHS presente (letterale o variabile) → invalido: gli operatori sono unari.
        assertTrue("var_compare_rhs_invalid" in validate(Condition.VarCompare("number", CmpOp.IS_EVEN, expected = "2")))
        assertTrue(
            "var_compare_rhs_invalid" in validate(Condition.VarCompare("number", CmpOp.IS_ODD, expectedVar = "number")),
        )

        // BOOLEAN → non coercibile a intero, escluso come per GT/LT.
        assertTrue("var_compare_type_invalid" in validate(Condition.VarCompare("flag", CmpOp.IS_EVEN)))
    }

    // --- Interpolazione -----------------------------------------------------------------------

    @Test fun `interpolation admits tainted values in sinks and, in aggressive posture, in authority`() {
        val notif = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        // SINK con var dichiarata → ok.
        val ok = draft(
            actions = listOf(Action.ShowNotification("Avviso", "Da: \${sender}")),
            vars = listOf(payload("sender", TriggerField.SENDER)),
            trigger = notif,
        )
        assertEquals(emptyList(), errors(v.validate(ok, emptySet())))

        // SINK con var NON dichiarata → errore.
        val undeclared = draft(actions = listOf(Action.ShowNotification("A", "Da: \${sender}")), trigger = notif)
        assertTrue("interpolation_undeclared_var" in errors(v.validate(undeclared, emptySet())))

        // Campo AUTHORITY: una var CLEAN approvata è ammessa (matrice P3 §4.2).
        val cleanAuthority = draft(
            actions = listOf(Action.RunShell("echo \${x}")),
            vars = listOf(literal("x", "1")),
        )
        assertFalse("interpolation_tainted_authority" in errors(v.validate(cleanAuthority, emptySet())))

        // Posture AGGRESSIVO (TaintPolicy.allowTaintedInAuthority()): lo stesso sink con payload
        // esterno TAINTED ora NON è più bloccato dal validator statico.
        val taintedAuthority = draft(
            actions = listOf(Action.RunShell("echo \${x}")),
            vars = listOf(payload("x", TriggerField.TEXT)),
            trigger = notif,
        )
        assertFalse("interpolation_tainted_authority" in errors(v.validate(taintedAuthority, emptySet())))

        // ${Malformato} in un SINK → malformed.
        val malformed = draft(
            actions = listOf(Action.ShowNotification("A", "x \${Bad}")),
            vars = listOf(literal("bad", "1")),
        )
        assertTrue("interpolation_malformed" in errors(v.validate(malformed, emptySet())))
    }

    @Test fun `captures are available only after definite assignment`() {
        val before = draft(
            actions = listOf(
                Action.ShowNotification("x", "\${out}"),
                Action.RunShell("id", captureAs = "out"),
            ),
        )
        assertTrue("var_not_definitely_assigned" in errors(v.validate(before, emptySet())))

        val after = draft(
            actions = listOf(
                Action.RunShell("id", captureAs = "out"),
                Action.ShowNotification("x", "\${out}"),
            ),
        )
        assertFalse("var_not_definitely_assigned" in errors(v.validate(after, emptySet())))

        val branchOnly = draft(
            actions = listOf(
                Action.If(
                    Condition.BooleanLiteral(true),
                    then = listOf(Action.RunShell("id", captureAs = "out")),
                ),
                Action.ShowNotification("x", "\${out}"),
            ),
        )
        assertTrue("var_not_definitely_assigned" in errors(v.validate(branchOnly, emptySet())))

        val loopOnly = draft(
            actions = listOf(
                Action.While(
                    Condition.BooleanLiteral(true),
                    body = listOf(Action.RunShell("id", captureAs = "out")),
                    maxIterations = 1,
                ),
                Action.ShowNotification("x", "\${out}"),
            ),
        )
        assertTrue("var_not_definitely_assigned" in errors(v.validate(loopOnly, emptySet())))
    }

    // --- captureAs ----------------------------------------------------------------------------

    @Test fun `capture names must be valid and unique across bindings`() {
        // Nome valido, nessuna collisione → ok.
        val ok = draft(actions = listOf(Action.RunShell("id", captureAs = "uid")))
        assertFalse("capture_name_invalid" in errors(v.validate(ok, emptySet())))
        assertFalse("capture_name_duplicate" in errors(v.validate(ok, emptySet())))

        // Nome fuori regex → errore.
        val bad = draft(actions = listOf(Action.RunShell("id", captureAs = "Bad-Name")))
        assertTrue("capture_name_invalid" in errors(v.validate(bad, emptySet())))

        // Collisione con un binding omonimo → duplicato.
        val collide = draft(
            actions = listOf(Action.RunShell("id", captureAs = "out")),
            vars = listOf(literal("out", "1")),
        )
        assertTrue("capture_name_duplicate" in errors(v.validate(collide, emptySet())))
    }

    @Test fun `binding plus captures share the sixteen variable limit`() {
        val bindings = (1..16).map { literal("v$it", "1") }
        val draft = draft(
            actions = listOf(Action.RunShell("id", captureAs = "out")),
            vars = bindings,
        )
        assertTrue("too_many_vars" in errors(v.validate(draft, emptySet())))
    }

    @Test fun `invoke llm v2 is a closed capture producer`() {
        val action = Action.InvokeLlmV2(
            goal = "g",
            stateContext = listOf(
                ApprovedStateContext(
                    StateQuery.Builtin(StateKeys.BATTERY),
                    StateValueType.NUMBER,
                    StateQueryPolicy.VERSION,
                    IntegrityLabel.CLEAN,
                    ConfidentialityLabel.PRIVATE,
                ),
            ),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
            captureAs = "answer",
        )
        val notification = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        assertFalse(
            "interpolation_undeclared_var" in errors(
                v.validate(
                    draft(
                        listOf(action, Action.ShowNotification("x", "\${answer}")),
                        trigger = notification,
                    ),
                    setOf("jid:1"),
                ),
            ),
        )
    }
}
