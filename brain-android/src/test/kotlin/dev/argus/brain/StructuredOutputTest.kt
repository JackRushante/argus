package dev.argus.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.5.1 F3b: flag del catalogo, forma delle richieste e regole del ripiego sul protocollo base. */
class StructuredOutputTest {
    @Test
    fun `the catalog switches the structured output on per provider and leaves hermes alone`() {
        val expected = mapOf(
            ProviderId.HERMES to StructuredOutputMode.NONE,
            ProviderId.OPENAI to StructuredOutputMode.RESPONSE_FORMAT,
            ProviderId.GEMINI to StructuredOutputMode.RESPONSE_FORMAT,
            ProviderId.OPENROUTER to StructuredOutputMode.RESPONSE_FORMAT,
            ProviderId.CUSTOM_OPENAI_COMPAT to StructuredOutputMode.RESPONSE_FORMAT,
            ProviderId.ANTHROPIC to StructuredOutputMode.OUTPUT_CONFIG,
        )
        assertEquals(ProviderId.entries.toSet(), expected.keys, "ogni provider ha il suo flag")
        expected.forEach { (id, mode) ->
            assertEquals(mode, ProviderCatalog.spec(id).quirks.structuredOutput, id.name)
        }
        // Senza flag nessuno chiede l'output strutturato: il valore di partenza di una ProviderQuirks nuova.
        assertEquals(StructuredOutputMode.NONE, ProviderQuirks().structuredOutput)
    }

    @Test
    fun `levels go from the schema to the base protocol in that order`() {
        assertEquals(listOf(StructuredLevel.SCHEMA, StructuredLevel.OBJECT, StructuredLevel.NONE), StructuredLevel.entries)
        assertEquals(StructuredLevel.OBJECT, StructuredLevel.SCHEMA.next())
        assertEquals(StructuredLevel.NONE, StructuredLevel.OBJECT.next())
    }

    @Test
    fun `the response_format of each level`() {
        val schema = StructuredOutput.responseFormat(StructuredLevel.SCHEMA)!!
        assertEquals(setOf("type", "json_schema"), schema.keys)
        assertEquals("json_schema", schema.getValue("type").jsonPrimitive.content)
        val inner = schema.getValue("json_schema").jsonObject
        assertEquals(setOf("name", "strict", "schema"), inner.keys)
        assertEquals("argus_compile", inner.getValue("name").jsonPrimitive.content)
        assertEquals(StructuredOutput.ENVELOPE_SCHEMA, inner.getValue("schema"))

        val obj = StructuredOutput.responseFormat(StructuredLevel.OBJECT)!!
        assertEquals(setOf("type"), obj.keys)
        assertEquals("json_object", obj.getValue("type").jsonPrimitive.content)

        assertNull(StructuredOutput.responseFormat(StructuredLevel.NONE))
    }

    @Test
    fun `the envelope schema has the three fields and a free draft object`() {
        val schema = StructuredOutput.ENVELOPE_SCHEMA
        val properties = schema.getValue("properties").jsonObject
        assertEquals(setOf("reply", "draft", "error_code"), properties.keys)
        // La bozza e' un oggetto libero o null: la forma esatta la controlla il validatore dell'app.
        assertEquals(
            listOf("object", "null"),
            (properties.getValue("draft").jsonObject.getValue("type") as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals("false", schema.getValue("additionalProperties").toString())
    }

    @Test
    fun `the anthropic schema is closed and non recursive`() {
        val schema = StructuredOutput.ANTHROPIC_ENVELOPE_SCHEMA
        assertEquals(setOf("reply", "draft", "error_code"), schema.getValue("properties").jsonObject.keys)
        // Ogni oggetto dello schema e' chiuso: Anthropic rifiuta additionalProperties diverso da false.
        fun check(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if (element["type"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "object" } == true) {
                        assertEquals("false", element["additionalProperties"].toString(), element.toString())
                    }
                    assertFalse("\$ref" in element, "niente ricorsione")
                    element.values.forEach(::check)
                }
                is JsonArray -> element.forEach(::check)
                else -> Unit
            }
        }
        check(schema)
        // draft e' una stringa o null, non un oggetto libero.
        val draft = schema.getValue("properties").jsonObject.getValue("draft").jsonObject
        assertEquals(
            listOf("string", "null"),
            (draft.getValue("anyOf") as JsonArray).map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        // La forma dentro `output_config`: `format` di tipo json_schema.
        val format = StructuredOutput.ANTHROPIC_OUTPUT_CONFIG.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals(schema, format.getValue("schema"))
    }

    @Test
    fun `only a refusal of the request is a rejection of the parameter`() {
        fun http(status: Int) = TransportException(TransportErrorKind.HTTP, "Provider HTTP $status", statusCode = status)
        listOf(400, 404, 405, 415, 422, 500).forEach { assertTrue(StructuredOutput.isRejection(http(it)), "$it") }
        listOf(401, 402, 403, 408, 429, 502, 503, 529).forEach { assertFalse(StructuredOutput.isRejection(http(it)), "$it") }
        // Rete, timeout e credito (anche con stato 400) non dipendono dal formato.
        assertFalse(StructuredOutput.isRejection(TransportException(TransportErrorKind.TIMEOUT, "t")))
        assertFalse(StructuredOutput.isRejection(TransportException(TransportErrorKind.NETWORK, "n")))
        assertFalse(StructuredOutput.isRejection(TransportException(TransportErrorKind.BUDGET, "b", statusCode = 400)))
    }

    @Test
    fun `the structured rule only replaces the last line of rule 7 and the base prompt is untouched`() {
        val base = AgentMessageSupport.compileSystemText()
        assertEquals(base, AgentMessageSupport.compileSystemText(structuredRule = null))

        listOf(
            AgentMessageSupport.STRUCTURED_JSON_RULE,
            AgentMessageSupport.STRUCTURED_JSON_STRING_DRAFT_RULE,
        ).forEach { rule ->
            val structured = AgentMessageSupport.compileSystemText(structuredRule = rule)
            assertTrue(structured.startsWith(base), "le regole e gli schemi non cambiano")
            assertEquals(base + "\n\n" + rule, structured)
            assertTrue("reply" in rule && "draft" in rule && "error_code" in rule)
            assertTrue("JSON" in rule, "json_object esige la parola JSON nei messaggi")
            // Poche righe a ogni compile: la regola resta breve.
            assertTrue(rule.length < 700, "regola troppo lunga: ${rule.length}")
        }
    }
}
