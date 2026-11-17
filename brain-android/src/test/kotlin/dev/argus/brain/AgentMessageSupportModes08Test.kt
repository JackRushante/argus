package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: the compile request carries the NAMES of the user's modes, and omits them when there are none. */
class AgentMessageSupportModes08Test {
    private fun manifest(modes: List<String>) = CapabilityManifest(
        deviceModel = "Pixel",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = false,
        grantedPermissions = emptyList(),
        availableTools = listOf("set_user_mode"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
        userModes = modes,
    )

    private fun manifestOf(user: String) = Json.parseToJsonElement(
        user.substringAfter("===== UNTRUSTED STRUCTURED CONTEXT =====\n").substringBefore("\n===== END CONTEXT ====="),
    ).jsonObject["manifest"]!!.jsonObject

    @Test
    fun `the manifest lists the mode names, and leaves the key out when there are none`() {
        val user = AgentMessageSupport.compileUserText("metti la modalità casa", manifest(listOf("Casa", "Lavoro")), DeviceState())
        assertEquals(listOf("Casa", "Lavoro"), manifestOf(user)["user_modes"]!!.jsonArray.map { it.jsonPrimitive.content })

        val none = AgentMessageSupport.compileUserText("x", manifest(emptyList()), DeviceState())
        assertFalse("user_modes" in manifestOf(none))
    }

    @Test
    fun `the schema tells the model to use only those names`() {
        assertTrue("manifest.user_modes" in AgentMessageSupport.compileSystemText())
    }
}
