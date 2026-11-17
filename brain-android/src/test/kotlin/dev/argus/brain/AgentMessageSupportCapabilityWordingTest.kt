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

/**
 * 0.8 bench: the compile prompt must not talk the model out of a capability it has. A listed tool or trigger is
 * available now, the safety gates name exactly what they bind, and the manifest carries only draft vocabulary.
 */
class AgentMessageSupportCapabilityWordingTest {
    private val flat = AgentMessageSupport.compileSystemText().replace(Regex("\\s+"), " ")

    private fun manifest() = CapabilityManifest(
        deviceModel = "Pixel",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = emptyList(),
        availableTools = listOf("notify.show", "run_shell", "ui_steps", "web.search"),
        unavailableTools = mapOf(
            "shell.run" to "live confirmation not implemented",
            "screen.tap" to "UI action not available in this phase",
            "set_nfc" to "this phone has no NFC",
            "web.search" to "generative runtime not ready",
        ),
        whitelistedContacts = emptyList(),
    )

    private fun manifestOf(user: String) = Json.parseToJsonElement(
        user.substringAfter("===== UNTRUSTED STRUCTURED CONTEXT =====\n").substringBefore("\n===== END CONTEXT ====="),
    ).jsonObject["manifest"]!!.jsonObject

    @Test
    fun `raw dotted aliases never reach the compile, the draft vocabulary does`() {
        val context = manifestOf(AgentMessageSupport.compileUserText("x", manifest(), DeviceState()))
        assertEquals(
            listOf("run_shell", "ui_steps", "web.search"),
            context["available_tools"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(setOf("set_nfc", "web.search"), context["unavailable_tools"]!!.jsonObject.keys)
    }

    @Test
    fun `a listed trigger is armable now`() {
        assertTrue("every listed trigger IS armable right now, so never claim it is missing" in flat, flat)
        assertTrue("CONNECTED and DISCONNECTED" in flat)
    }

    @Test
    fun `the spoofable sender gate binds only shell and screen macros`() {
        assertTrue("Never with phone_state (SMS sender and caller ID are spoofable)" in flat)
        assertTrue("It binds ONLY run_shell and ui_steps" in flat)
    }

    @Test
    fun `a question carries the clarification code`() {
        assertTrue("ask a short question and return draft null with error_code \"clarification_required\"" in flat)
    }

    @Test
    fun `rate limits are enforced at run time, not by refusing`() {
        assertTrue("never refuse a rule for that" in flat)
        assertTrue("a scheduled call is fine" in flat)
    }

    @Test
    fun `no position fix never hides a listed geofence and a named place is asked`() {
        assertTrue("location_available false never makes a listed \"geofence\" unavailable" in flat)
        assertTrue("for a named place without coordinates (home, the office) ask where it is" in flat)
    }

    @Test
    fun `two fitting apps are asked even when one label is exact`() {
        assertTrue("not even the one whose label equals the word in the request" in flat)
    }

    @Test
    fun `the types the probe never publishes have no schema line to lure the model`() {
        assertFalse("{\"type\":\"tap\"" in flat)
        assertFalse("{\"type\":\"input_text\"" in flat)
    }

    @Test
    fun `a captured name is never declared twice`() {
        assertTrue("never also declare it in \"vars\"" in flat)
    }
}
