package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.8: the compile request teaches capture and vault secrets, and carries the secret NAMES only. */
class AgentMessageSupportHttp08Test {
    private fun manifest(secrets: List<String>) = CapabilityManifest(
        deviceModel = "Pixel",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = false,
        grantedPermissions = emptyList(),
        availableTools = listOf("http_request"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
        savedSecrets = secrets,
    )

    private fun contextOf(user: String) = Json.parseToJsonElement(
        user.substringAfter("===== UNTRUSTED STRUCTURED CONTEXT =====\n").substringBefore("\n===== END CONTEXT ====="),
    ).jsonObject

    @Test
    fun `the manifest lists the saved secret names`() {
        val user = AgentMessageSupport.compileUserText("chiama home assistant", manifest(listOf("ha_token", "meteo_api")), DeviceState())
        val names = contextOf(user)["manifest"]!!.jsonObject["saved_secrets"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ha_token", "meteo_api"), names)
        val none = AgentMessageSupport.compileUserText("x", manifest(emptyList()), DeviceState())
        assertEquals(0, contextOf(none)["manifest"]!!.jsonObject["saved_secrets"]!!.jsonArray.size)
    }

    @Test
    fun `the schema teaches secret references capture and the local network`() {
        val prompt = AgentMessageSupport.compileSystemText()
        listOf("secretHeaders", "secretQuery", "\"captureAs\"", "\"extract\"", "manifest.saved_secrets", "name.local")
            .forEach { assertTrue(it in prompt, it) }
        assertTrue("ONLY on run_shell, invoke_llm and http_request" in prompt)
    }
}
