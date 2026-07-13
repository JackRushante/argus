package dev.argus.engine.runtime
import dev.argus.engine.model.*
import java.time.*
import kotlin.test.Test
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
}
