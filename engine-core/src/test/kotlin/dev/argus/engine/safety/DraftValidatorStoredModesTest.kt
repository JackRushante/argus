package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Limiti di valori conservati e modalità nel validatore (0.8). */
class DraftValidatorStoredModesTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply"))

    private fun errors(
        trigger: Trigger = Trigger.Manual,
        actions: List<Action> = listOf(Action.Vibrate(100)),
        conditions: Condition? = null,
        vars: List<VarBinding> = emptyList(),
    ) = v.validate(
        AutomationDraft(name = "regola", trigger = trigger, actions = actions, conditions = conditions, vars = vars),
        emptySet(),
    ).filter { it.severity == Severity.ERROR }.map { it.code }

    @Test
    fun `well formed stored and mode rules have no errors`() {
        assertEquals(
            emptyList(),
            errors(
                trigger = Trigger.UserModeChanged("Casa"),
                conditions = Condition.And(
                    listOf(
                        Condition.StoredCompare("count", StateValueType.NUMBER, CmpOp.LT, "3"),
                        Condition.Not(Condition.UserModeIs("Notte")),
                    ),
                ),
                vars = listOf(VarBinding.Stored("n", "count", StateValueType.NUMBER, ConfidentialityLabel.PRIVATE)),
                actions = listOf(
                    Action.SetStored("count", StoredOp.INCREMENT),
                    Action.SetStored("count", StoredOp.INCREMENT, "-2"),
                    Action.SetStored("last", StoredOp.SET, "valore \${n}"),
                    Action.SetStored("flag", StoredOp.SET, "true", StateValueType.BOOLEAN),
                    Action.SetStored("flag", StoredOp.TOGGLE),
                    Action.SetStored("old", StoredOp.CLEAR),
                    Action.SetUserMode("Lavoro"),
                ),
            ),
        )
        assertEquals(emptyList(), errors(trigger = Trigger.UserModeChanged()))
    }

    @Test
    fun `stored keys follow the variable name rule`() {
        listOf("", "Count", "9x", "a".repeat(33), "a-b", "\${k}").forEach { key ->
            assertTrue("stored_key_invalid" in errors(actions = listOf(Action.SetStored(key, StoredOp.CLEAR))), key)
            assertTrue(
                "stored_key_invalid" in errors(conditions = Condition.StoredCompare(key, StateValueType.TEXT, CmpOp.EQ, "x")),
                key,
            )
            assertTrue(
                "stored_key_invalid" in errors(
                    vars = listOf(VarBinding.Stored("v", key, StateValueType.TEXT, ConfidentialityLabel.PRIVATE)),
                    actions = listOf(Action.ShowNotification("t", "\${v}")),
                ),
                key,
            )
        }
    }

    @Test
    fun `set_stored needs the right value for each operation`() {
        fun codes(action: Action.SetStored) = errors(actions = listOf(action))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.SET)))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.SET, "x".repeat(1_025))))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.SET, "tre", StateValueType.NUMBER)))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.INCREMENT, "1.5")))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.INCREMENT, "\${n}")))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.INCREMENT, valueType = StateValueType.NUMBER)))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.TOGGLE, "true")))
        assertTrue("set_stored_invalid" in codes(Action.SetStored("k", StoredOp.CLEAR, "x")))
        assertEquals(emptyList(), codes(Action.SetStored("k", StoredOp.INCREMENT, "-999999999")))
    }

    @Test
    fun `stored_compare needs an expected value of its type and an operator that applies`() {
        fun codes(c: Condition) = errors(conditions = c)
        assertTrue("stored_compare_invalid" in codes(Condition.StoredCompare("k", StateValueType.NUMBER, CmpOp.EQ, "tre")))
        assertTrue("stored_compare_invalid" in codes(Condition.StoredCompare("k", StateValueType.BOOLEAN, CmpOp.GT, "true")))
        assertTrue("stored_compare_invalid" in codes(Condition.StoredCompare("k", StateValueType.BOOLEAN, CmpOp.EQ, "si")))
        assertTrue("stored_compare_invalid" in codes(Condition.StoredCompare("k", StateValueType.TEXT, CmpOp.GT, "a")))
        assertTrue("stored_compare_invalid" in codes(Condition.StoredCompare("k", StateValueType.NUMBER, CmpOp.IS_EVEN, "1")))
        assertTrue("stored_compare_invalid" in codes(Condition.StoredCompare("k", StateValueType.TEXT, CmpOp.EQ, "x".repeat(1_025))))
        assertEquals(emptyList(), codes(Condition.StoredCompare("k", StateValueType.TEXT, CmpOp.CONTAINS, "pal")))
    }

    @Test
    fun `mode names are literal, 1 to 24 characters`() {
        listOf("", " Casa", "x".repeat(25), "\${m}", "a\nb").forEach { name ->
            assertTrue("user_mode_name_invalid" in errors(actions = listOf(Action.SetUserMode(name))), name)
            assertTrue("user_mode_name_invalid" in errors(conditions = Condition.UserModeIs(name)), name)
            assertTrue("user_mode_name_invalid" in errors(trigger = Trigger.UserModeChanged(name)), name)
        }
    }

    @Test
    fun `stored_compare and user_mode_is are allowed inside if and while`() {
        assertEquals(
            emptyList(),
            errors(
                actions = listOf(
                    Action.If(
                        Condition.Or(
                            listOf(
                                Condition.UserModeIs("Casa"),
                                Condition.StoredCompare("k", StateValueType.BOOLEAN, CmpOp.EQ, "false"),
                            ),
                        ),
                        then = listOf(Action.Vibrate(100)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a mode-changed rule cannot start the shell or a screen macro`() {
        val codes = errors(
            trigger = Trigger.UserModeChanged("Casa"),
            actions = listOf(Action.RunShell("echo ciao")),
        )
        assertTrue(codes.isNotEmpty(), "$codes")
    }
}
