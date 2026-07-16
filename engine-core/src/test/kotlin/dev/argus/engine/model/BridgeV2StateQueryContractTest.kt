package dev.argus.engine.model

import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.engine.brain.StateReaderLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeV2StateQueryContractTest {
    private val strictJson = Json(ArgusJson) {
        ignoreUnknownKeys = false
        coerceInputValues = false
        isLenient = false
    }

    @Test
    fun `shared bridge v2 fixtures agree with Kotlin decoder validator and capabilities`() {
        val root = fixture()
        assertEquals(2, root.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals(StateQueryPolicy.VERSION, root.getValue("policy_version").jsonPrimitive.content.toInt())
        val limits = root.getValue("limits").jsonObject
        assertEquals(
            mapOf(
                "max_query_name_length" to StateReaderLimits().maxQueryNameLength.toLong(),
                "max_sysfs_path_length" to StateReaderLimits().maxSysfsPathLength.toLong(),
                "max_expected_length" to StateReaderLimits().maxExpectedLength.toLong(),
                "timeout_millis" to StateReaderLimits().timeoutMillis,
                "max_output_bytes" to StateReaderLimits().maxOutputBytes.toLong(),
                "max_scalar_chars" to StateReaderLimits().maxScalarChars.toLong(),
            ),
            limits.mapValues { it.value.jsonPrimitive.long },
        )
        val allFamilies = root.getValue("all_families").jsonArray.strings()
        assertEquals(StateQueryFamily.entries.map(StateQueryFamily::wireName), allFamilies)

        root.getValue("cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val expected = case.getValue("accepted").jsonPrimitive.boolean
            val availableFamilies = case["available_families"]?.jsonArray?.strings()
                ?: allFamilies
            val accepted = runCatching {
                strictJson.decodeFromJsonElement(
                    Condition.serializer(),
                    case.getValue("condition"),
                )
            }.map { condition ->
                val draft = AutomationDraft(
                    name = "fixture-$name",
                    trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
                    actions = listOf(Action.ShowNotification("Argus", "fixture")),
                    conditions = condition,
                )
                val validDomain = DraftValidator(emptySet()).validate(draft, emptySet())
                    .none { it.severity == Severity.ERROR }
                val available = condition.stateComparisons().all {
                    it.query.family.wireName in availableFamilies
                }
                validDomain && available
            }.getOrDefault(false)
            assertEquals(expected, accepted, name)
        }
    }

    private fun fixture(): JsonObject {
        val stream = requireNotNull(
            javaClass.classLoader.getResourceAsStream("state_query_contract_v2.json"),
        ) { "Fixture bridge v2 non inclusa nelle test resources" }
        return stream.bufferedReader().use { reader ->
            ArgusJson.parseToJsonElement(reader.readText()).jsonObject
        }
    }

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }
}
