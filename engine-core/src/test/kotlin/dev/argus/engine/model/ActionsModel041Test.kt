package dev.argus.engine.model

import dev.argus.engine.runtime.StateReadPlanner
import dev.argus.engine.runtime.journalType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Azioni 0.4.1 (speak, media_control, set_brightness, set_rotation, http_request): modello e wire. */
class ActionsModel041Test {
    private val newActions: List<Action> = listOf(
        Action.Speak("Ciao"),
        Action.MediaControl(MediaCommand.PLAY_PAUSE),
        Action.SetBrightness(level = 40),
        Action.SetBrightness(auto = true),
        Action.SetRotation(auto = false),
        Action.HttpRequest(
            method = HttpMethod.POST,
            url = "https://example.org/hook",
            headers = mapOf("Authorization" to "Bearer abcdefghijkl"),
            body = "{\"a\":1}",
            contentType = "application/json",
        ),
    )

    @Test
    fun `every new action round trips through the wire format`() {
        newActions.forEach { action ->
            val text = ArgusJson.encodeToString(Action.serializer(), action)
            assertEquals(action, ArgusJson.decodeFromString(Action.serializer(), text), text)
        }
    }

    @Test
    fun `the wire names are the registered ones`() {
        fun type(action: Action) = Json.parseToJsonElement(ArgusJson.encodeToString(Action.serializer(), action))
            .let { (it as kotlinx.serialization.json.JsonObject)["type"].toString().trim('"') }
        assertEquals("speak", type(newActions[0]))
        assertEquals("media_control", type(newActions[1]))
        assertEquals("set_brightness", type(newActions[2]))
        assertEquals("set_rotation", type(newActions[4]))
        assertEquals("http_request", type(newActions[5]))
        assertEquals(
            "{\"type\":\"media_control\",\"command\":\"PLAY_PAUSE\"}",
            ArgusJson.encodeToString(Action.serializer(), Action.MediaControl(MediaCommand.PLAY_PAUSE)),
        )
    }

    @Test
    fun `a model that writes the enums in lowercase is understood and the engine writes them uppercase`() {
        val http = ArgusJson.decodeFromString(
            Action.serializer(),
            "{\"type\":\"http_request\",\"method\":\"post\",\"url\":\"https://example.org/\"}",
        )
        assertEquals(HttpMethod.POST, (http as Action.HttpRequest).method)
        val media = ArgusJson.decodeFromString(
            Action.serializer(),
            "{\"type\":\"media_control\",\"command\":\"play_pause\"}",
        )
        assertEquals(MediaCommand.PLAY_PAUSE, (media as Action.MediaControl).command)
        assertTrue("\"method\":\"POST\"" in ArgusJson.encodeToString(Action.serializer(), http))

        assertFailsWith<Exception> {
            ArgusJson.decodeFromString(
                Action.serializer(),
                "{\"type\":\"http_request\",\"method\":\"PATCH\",\"url\":\"https://example.org/\"}",
            )
        }
        assertFailsWith<Exception> {
            ArgusJson.decodeFromString(
                Action.serializer(),
                "{\"type\":\"media_control\",\"command\":\"RECORD\"}",
            )
        }
    }

    @Test
    fun `brightness accepts a level or an auto flag and leaves the other absent`() {
        val level = ArgusJson.decodeFromString(
            Action.serializer(),
            "{\"type\":\"set_brightness\",\"level\":30}",
        ) as Action.SetBrightness
        assertEquals(30, level.level)
        assertEquals(null, level.auto)
        val auto = ArgusJson.decodeFromString(
            Action.serializer(),
            "{\"type\":\"set_brightness\",\"auto\":true,\"level\":null}",
        ) as Action.SetBrightness
        assertEquals(null, auto.level)
        assertEquals(true, auto.auto)
    }

    @Test
    fun `the new actions need no Shizuku and are deterministic`() {
        newActions.forEach { action ->
            assertEquals(ActionPrivilege.BASE, ActionPrivileges.of(action), action.toString())
            assertFalse(ActionPrivileges.requiresShizuku(action), action.toString())
            assertEquals(ActionTier.DETERMINISTIC, action.tier, action.toString())
        }
        // Dentro un if/while il massimo resta BASE.
        val nested = Action.While(
            condition = Condition.BooleanLiteral(true),
            body = listOf(Action.HttpRequest(HttpMethod.GET, "https://example.org/")),
            maxIterations = 3,
        )
        assertEquals(ActionPrivilege.BASE, ActionPrivileges.of(nested))
    }

    @Test
    fun `each new action requires its own capability`() {
        fun caps(action: Action) = CapabilityRequirements.derive(Trigger.Immediate, listOf(action))
        assertEquals(
            setOf(CapabilityIds.TRIGGER_IMMEDIATE, CapabilityIds.ACTION_SPEAK),
            caps(Action.Speak("x")),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_IMMEDIATE, CapabilityIds.ACTION_MEDIA_CONTROL),
            caps(Action.MediaControl(MediaCommand.NEXT)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_IMMEDIATE, CapabilityIds.ACTION_SET_BRIGHTNESS),
            caps(Action.SetBrightness(level = 10)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_IMMEDIATE, CapabilityIds.ACTION_SET_ROTATION),
            caps(Action.SetRotation(true)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_IMMEDIATE, CapabilityIds.ACTION_HTTP_REQUEST),
            caps(Action.HttpRequest(HttpMethod.GET, "https://example.org/")),
        )
        assertEquals("action.speak", CapabilityIds.ACTION_SPEAK)
        assertEquals("action.http_request", CapabilityIds.ACTION_HTTP_REQUEST)
    }

    @Test
    fun `the journal and the state planner know the new actions`() {
        assertEquals(
            listOf("speak", "media_control", "set_brightness", "set_brightness", "set_rotation", "http_request"),
            newActions.map { it.journalType() },
        )
        newActions.forEach { assertTrue(StateReadPlanner.forAction(it).isEmpty, it.toString()) }
    }

    @Test
    fun `speak interpolates only as a sink and http only in the body`() {
        val speak = InterpolationPolicy.textFields(Action.Speak("Letto: \${sms}"))
        assertEquals(listOf("text"), speak.map { it.label })
        assertEquals(InterpolationPolicy.FieldClass.SINK, speak.single().cls)

        val http = Action.HttpRequest(
            method = HttpMethod.POST,
            url = "https://example.org/hook",
            headers = mapOf("X-Note" to "plain"),
            body = "{\"t\":\"\${sms}\"}",
            contentType = "application/json",
        )
        val fields = InterpolationPolicy.textFields(http)
        assertEquals(listOf("body"), fields.map { it.label })
        assertEquals(InterpolationPolicy.FieldClass.SINK, fields.single().cls)
        assertEquals(InterpolationPolicy.ValueEncoding.JSON_STRING, fields.single().encoding)
        assertTrue(fields.single().remoteEgress)

        assertTrue(InterpolationPolicy.textFields(http.copy(body = null)).isEmpty())
        assertTrue(InterpolationPolicy.textFields(Action.MediaControl(MediaCommand.PLAY)).isEmpty())
        assertTrue(InterpolationPolicy.textFields(Action.SetBrightness(level = 1)).isEmpty())
        assertTrue(InterpolationPolicy.textFields(Action.SetRotation(true)).isEmpty())
    }
}
