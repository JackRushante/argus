package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
class ConditionSerializationTest {
    @Test fun `nested and-or round-trips`() {
        val c: Condition = Condition.And(listOf(
            Condition.TimeWindow(startLocal = "23:00", endLocal = "07:00", tz = "Europe/Rome"),
            Condition.Or(listOf(
                Condition.StateEquals("ringer", CmpOp.NEQ, "silent"),
                Condition.Not(Condition.AppInForeground("com.whatsapp")),
            )),
        ))
        val json = ArgusJson.encodeToString(Condition.serializer(), c)
        assertEquals(c, ArgusJson.decodeFromString(Condition.serializer(), json))
    }
}
