package dev.argus.brain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Livelli dell'output strutturato di un server OpenAI-compatibile, dal più preciso al protocollo base.
 * Scendere di un livello è la risposta a un rifiuto del server: [SCHEMA] (`json_schema`), [OBJECT]
 * (`json_object`), [NONE] (nessun `response_format`, protocollo `@@META@@`).
 */
internal enum class StructuredLevel {
    SCHEMA,
    OBJECT,
    NONE,
    ;

    /** Il livello sotto; non si chiama su [NONE], che è già il protocollo base. */
    fun next(): StructuredLevel = entries[ordinal + 1]
}

/**
 * Richieste dell'output strutturato nativo del compile (0.5.1, F3b). L'envelope è sempre
 * `{reply, draft, error_code}`: la stessa frase, la stessa bozza e lo stesso codice della riga `@@META@@`,
 * che resta la base e il ripiego. Nessun segreto qui, solo forme di richiesta.
 */
internal object StructuredOutput {
    /** Nome dello schema per `json_schema`. */
    const val SCHEMA_NAME = "argus_compile"

    /**
     * Stati con cui un server rifiuta una richiesta che porta un parametro che non conosce (o uno schema che
     * non digerisce): richiesta non valida, non trovato, metodo/tipo non ammessi, entità non elaborabile, e
     * il 500 con cui alcuni server locali (llama.cpp) rispondono a una grammatica non valida. Non entrano
     * 401/402/403/429 (chiave, credito, limiti) né i 5xx dei gateway: quelli non dipendono dal formato.
     */
    private val REJECTION_STATUSES = setOf(400, 404, 405, 415, 422, 500)

    /** true se [error] è un rifiuto del parametro da cui si può scendere al livello sotto. */
    fun isRejection(error: TransportException): Boolean =
        error.kind == TransportErrorKind.HTTP && error.statusCode in REJECTION_STATUSES

    private fun nullable(type: String): JsonObject = buildJsonObject {
        putJsonArray("type") {
            add(JsonPrimitive(type))
            add(JsonPrimitive("null"))
        }
    }

    private fun anyOfNullable(type: String): JsonObject = buildJsonObject {
        putJsonArray("anyOf") {
            add(buildJsonObject { put("type", type) })
            add(buildJsonObject { put("type", "null") })
        }
    }

    /**
     * Schema dell'envelope per i server OpenAI-compatibili: `draft` è un oggetto libero (la forma esatta
     * della bozza la controlla il validatore dell'app, non il server) oppure null.
     */
    val ENVELOPE_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply") { put("type", "string") }
            put("draft", nullable("object"))
            put("error_code", nullable("string"))
        }
        putJsonArray("required") {
            add(JsonPrimitive("reply"))
            add(JsonPrimitive("draft"))
            add(JsonPrimitive("error_code"))
        }
        put("additionalProperties", false)
    }

    /**
     * Schema dell'envelope per Anthropic: lì ogni oggetto deve essere chiuso (`additionalProperties:false`) e
     * lo schema non può essere ricorsivo, quindi una bozza libera non è esprimibile. `draft` è una stringa che
     * contiene l'oggetto serializzato (o null); [dev.argus.engine.brain.CliBridgeParser.parseStructuredCompile]
     * la riapre.
     */
    val ANTHROPIC_ENVELOPE_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply") { put("type", "string") }
            put("draft", anyOfNullable("string"))
            put("error_code", anyOfNullable("string"))
        }
        putJsonArray("required") {
            add(JsonPrimitive("reply"))
            add(JsonPrimitive("draft"))
            add(JsonPrimitive("error_code"))
        }
        put("additionalProperties", false)
    }

    /** `response_format` del livello [level], o null per il protocollo base. */
    fun responseFormat(level: StructuredLevel): JsonObject? = when (level) {
        StructuredLevel.SCHEMA -> buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", SCHEMA_NAME)
                // Non rigido: la bozza è un oggetto libero, e il modo rigido li vieta.
                put("strict", false)
                put("schema", ENVELOPE_SCHEMA)
            }
        }
        StructuredLevel.OBJECT -> buildJsonObject { put("type", "json_object") }
        StructuredLevel.NONE -> null
    }

    /** `output_config` della Messages API di Anthropic: `format` di tipo `json_schema`, GA e senza header beta. */
    val ANTHROPIC_OUTPUT_CONFIG: JsonObject = buildJsonObject {
        putJsonObject("format") {
            put("type", "json_schema")
            put("schema", ANTHROPIC_ENVELOPE_SCHEMA)
        }
    }
}
