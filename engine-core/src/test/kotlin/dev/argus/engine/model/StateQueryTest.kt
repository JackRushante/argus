package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StateQueryTest {
    @Test
    fun `every closed query family and typed condition round trips`() {
        val queries = listOf<StateQuery>(
            StateQuery.Builtin(StateKeys.BATTERY),
            StateQuery.Setting(SettingNamespace.GLOBAL, "airplane_mode_on"),
            StateQuery.SystemProperty("ro.build.version.release"),
            StateQuery.Sysfs("/sys/class/power_supply/battery/voltage_now"),
            StateQuery.DumpsysField("battery", "voltage"),
        )

        queries.forEach { query ->
            val encoded = ArgusJson.encodeToString(StateQuery.serializer(), query)
            assertEquals(query, ArgusJson.decodeFromString(StateQuery.serializer(), encoded))
            assertFalse("\"family\"" in encoded)
            assertFalse("canonicalId" in encoded)
        }
        val condition: Condition = Condition.StateCompare(
            query = queries.last(),
            valueType = StateValueType.NUMBER,
            op = CmpOp.GT,
            expected = "4000",
        )
        assertEquals(
            condition,
            ArgusJson.decodeFromString(
                Condition.serializer(),
                ArgusJson.encodeToString(Condition.serializer(), condition),
            ),
        )
    }

    @Test
    fun `canonical ids are stable family-scoped and parameter-sensitive`() {
        val a = StateQuery.Setting(SettingNamespace.GLOBAL, "a:b")
        val same = StateQuery.Setting(SettingNamespace.GLOBAL, "a:b")
        val differentNamespace = StateQuery.Setting(SettingNamespace.SECURE, "a:b")
        val differentSplit = StateQuery.Setting(SettingNamespace.GLOBAL, "a")

        assertEquals(a.canonicalId, same.canonicalId)
        assertNotEquals(a.canonicalId, differentNamespace.canonicalId)
        assertNotEquals(a.canonicalId, differentSplit.canonicalId)
        assertTrue(a.canonicalId.matches(Regex("^state\\.reader\\.setting\\.v1\\.[0-9a-f]{64}$")))
    }
}
