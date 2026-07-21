package dev.argus.engine.runtime
import dev.argus.engine.model.*
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class ConditionEvaluatorTest {
    private fun clockAt(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test fun `state equals numeric and string`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val st = DeviceState(values = mapOf("battery" to "80", "ringer" to "normal"))
        assertTrue(ev.eval(Condition.StateEquals("battery", CmpOp.GT, "50"), st))
        assertFalse(ev.eval(Condition.StateEquals("ringer", CmpOp.EQ, "silent"), st))
    }
    @Test fun `time window crossing midnight`() {
        val st = DeviceState()
        // 23:30 Rome è dentro [23:00, 07:00]
        val ev = ConditionEvaluator(clockAt("2026-07-12T21:30:00Z")) // 23:30 CEST
        assertTrue(ev.eval(Condition.TimeWindow("23:00", "07:00", "Europe/Rome"), st))
        // 12:00 Rome è fuori
        val ev2 = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        assertFalse(ev2.eval(Condition.TimeWindow("23:00", "07:00", "Europe/Rome"), st))
    }
    @Test fun `and or not compose`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val st = DeviceState(foregroundApp = "com.x")
        val c = Condition.And(listOf(
            Condition.Not(Condition.AppInForeground("com.whatsapp")),
            Condition.Or(listOf(Condition.AppInForeground("com.x"), Condition.AppInForeground("com.y"))),
        ))
        assertTrue(ev.eval(c, st))
    }
    @Test fun `null condition is true`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        assertTrue(ev.eval(null, DeviceState()))
    }

    @Test fun `not cannot turn an unavailable state reader into a match`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val missing = DeviceState()

        assertFalse(
            ev.eval(
                Condition.Not(Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "on")),
                missing,
            ),
        )
        assertFalse(
            ev.eval(Condition.Not(Condition.AppInForeground("com.whatsapp")), missing),
        )
        assertFalse(
            ev.eval(
                Condition.Not(Condition.LocationIn(45.0, 9.0, 100.0)),
                missing,
            ),
        )
    }

    @Test fun `and or and not preserve unknown until the final fail closed boundary`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val state = DeviceState(values = mapOf(StateKeys.WIFI to "on"))
        val knownTrue = Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "on")
        val knownFalse = Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "off")
        val unavailable = Condition.AppInForeground("com.whatsapp")

        assertFalse(ev.eval(Condition.And(listOf(knownTrue, unavailable)), state))
        assertFalse(ev.eval(Condition.Or(listOf(knownFalse, unavailable)), state))
        assertTrue(ev.eval(Condition.Or(listOf(knownTrue, unavailable)), state))
        assertFalse(ev.eval(Condition.Not(unavailable), state))
    }

    @Test fun `typed query comparison does not fall back to lexicographic ordering`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val query = StateQuery.DumpsysField("battery", "voltage")
        val state = DeviceState(queryValues = mapOf(query.canonicalId to "4200"))

        assertTrue(
            ev.eval(
                Condition.StateCompare(query, StateValueType.NUMBER, CmpOp.GT, "900"),
                state,
            ),
        )
        assertFalse(
            ev.eval(
                Condition.StateCompare(query, StateValueType.NUMBER, CmpOp.GT, "not-a-number"),
                state,
            ),
        )
    }

    @Test fun `parity operators coerce the left operand to integer and fail closed`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val state = DeviceState()
        fun numberVar(value: String) = VarValue(
            value, VarType.NUMBER, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, setOf(ValueProvenance.LITERAL),
        )
        fun textVar(value: String) = VarValue(
            value, VarType.TEXT, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, setOf(ValueProvenance.LITERAL),
        )
        val values = mapOf(
            "four" to numberVar("4"),
            "six" to textVar("6"), // TEXT coercibile a intero, come GT/LT
            "five" to numberVar("5"),
            "word" to textVar("abc"),
            "half" to numberVar("4.5"),
        )
        // 4 e "6" sono pari; 5 è dispari.
        assertEquals(ConditionEvaluator.Result.MET, ev.flowResult(Condition.VarCompare("four", CmpOp.IS_EVEN), state, values::get))
        assertEquals(ConditionEvaluator.Result.MET, ev.flowResult(Condition.VarCompare("six", CmpOp.IS_EVEN), state, values::get))
        assertEquals(ConditionEvaluator.Result.MET, ev.flowResult(Condition.VarCompare("five", CmpOp.IS_ODD), state, values::get))
        assertEquals(ConditionEvaluator.Result.NOT_MET, ev.flowResult(Condition.VarCompare("four", CmpOp.IS_ODD), state, values::get))
        // "abc" non coercibile a intero -> fail-closed FALSE (NOT_MET), mai STATE_UNAVAILABLE.
        assertEquals(ConditionEvaluator.Result.NOT_MET, ev.flowResult(Condition.VarCompare("word", CmpOp.IS_EVEN), state, values::get))
        // 4.5 è finito ma NON intero -> fail-closed FALSE.
        assertEquals(ConditionEvaluator.Result.NOT_MET, ev.flowResult(Condition.VarCompare("half", CmpOp.IS_EVEN), state, values::get))
    }

    @Test fun `typed boolean values are normalized while missing remains unknown under not`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val query = StateQuery.Setting(SettingNamespace.GLOBAL, "airplane_mode_on")
        val condition = Condition.StateCompare(
            query,
            StateValueType.BOOLEAN,
            CmpOp.EQ,
            "true",
        )

        assertTrue(ev.eval(condition, DeviceState(queryValues = mapOf(query.canonicalId to "1"))))
        assertFalse(ev.eval(Condition.Not(condition), DeviceState()))
    }
}
