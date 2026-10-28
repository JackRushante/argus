package dev.argus.engine.brain
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.CronSchedule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

private const val SENTINEL = "@@META@@"
private const val MAX_DETAIL_CHARS = 200

@Serializable
private data class MetaEnvelope(
    val draft: AutomationDraft? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

/**
 * Parser puro dell'output CliBridge (spec §2/§7). Fail-soft: mai crash, sempre reply + eventuale metaError.
 *
 * Tollerante al rumore tipico dei modelli (B7): blocchi di ragionamento `<think>…</think>` rimossi
 * prima della ricerca, ULTIMO sentinel come meta (un sentinel finto nel ragionamento o citato nella
 * prosa non vince), JSON con virgola finale, commenti e `null` al posto di un default (rationale).
 * Ripiego 1: senza sentinel, un blocco ```json il cui oggetto ha la chiave `draft`.
 * Ripiego 2: senza sentinel né blocco, l'ULTIMO marcatore `@@NOME@@` (il modello sbaglia il nome, es.
 * `@@AUTOMATION_DRAFT@@`) seguito da un oggetto JSON con la chiave `draft` o `error_code`; la prosa è il
 * testo prima del marcatore. Un marcatore il cui oggetto non ha quelle chiavi resta prosa.
 */
class CliBridgeParser(private val json: Json = lenient()) {
    fun parseCompile(raw: String): CompileResult {
        val cleaned = ThinkBlocks.strip(raw)
        val idx = cleaned.lastIndexOf(SENTINEL)
        if (idx < 0) {
            return parseFencedFallback(cleaned)
                ?: parseMisnamedSentinelFallback(cleaned)
                ?: CompileResult(cleaned.trim(), null, null)
        }
        return parseMeta(prose = cleaned.substring(0, idx).trim(), tail = cleaned.substring(idx + SENTINEL.length))
    }

    /**
     * Output strutturato nativo (0.5.1): la risposta è UN oggetto JSON `{"reply","draft","error_code"}`
     * (`response_format` del provider o `output_config` di Anthropic), non una frase con la riga
     * `@@META@@`. Riusa la decodifica di [parseMeta]: stesse normalizzazioni (cron, suonerie) e stesse
     * regole su `error_code`. `draft` può arrivare anche come stringa che contiene l'oggetto (schema
     * chiuso di Anthropic): in quel caso viene aperta.
     *
     * Torna `null` quando il testo NON è un envelope (contiene `@@META@@`, non ha oggetti JSON o
     * l'oggetto non porta né `draft` né `error_code`): il chiamante ripiega su [parseCompile], il
     * protocollo base. Un envelope che si apre ma non si chiude, o che non è JSON, è invece un errore di
     * formato del modello (`meta_json_missing`, `meta_parse_error`) e il ritentativo se ne occupa.
     */
    fun parseStructuredCompile(raw: String): CompileResult? {
        val cleaned = ThinkBlocks.strip(raw)
        if (cleaned.contains(SENTINEL)) return null
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        val body = extractJsonObject(cleaned.substring(start))
        if (body == null) {
            return if (ENVELOPE_KEY.containsMatchIn(cleaned)) CompileResult("", null, "meta_json_missing") else null
        }
        val envelope = try {
            json.parseToJsonElement(body).jsonObject
        } catch (error: Exception) {
            return CompileResult("", null, "meta_parse_error", detail = safeDetail(error))
        }
        if ("draft" !in envelope && "error_code" !in envelope) return null
        val reply = (envelope["reply"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        return parseMeta(prose = reply, tail = openStringDraft(envelope).toString())
    }

    /** `"draft":"{...}"` (oggetto serializzato in stringa) diventa l'oggetto; ogni altro valore resta com'è. */
    private fun openStringDraft(envelope: JsonObject): JsonObject {
        val draft = envelope["draft"] as? JsonPrimitive ?: return envelope
        if (!draft.isString) return envelope
        val inner = try {
            json.parseToJsonElement(draft.content)
        } catch (_: Exception) {
            return envelope
        }
        return if (inner is JsonObject) JsonObject(envelope + ("draft" to inner)) else envelope
    }

    private fun parseMeta(prose: String, tail: String): CompileResult {
        val obj = extractJsonObject(tail)
            ?: return CompileResult(prose, null, "meta_json_missing")
        val meta = try {
            // `null` al posto di un campo con default (es. `"rationale": null`) vale come campo
            // assente. Volutamente NON `coerceInputValues`: coercerebbe anche un valore enum
            // sconosciuto al default (es. un sink scritto male → WHATSAPP_REPLY) in silenzio.
            // Si decodifica dal testo (non dall'albero JSON): solo il decoder in streaming aggiunge
            // all'errore il percorso (`at path: $.draft.trigger`), che il ritentativo passa al modello.
            val cleanObject = stripNulls(json.parseToJsonElement(obj))
            json.decodeFromString(MetaEnvelope.serializer(), cleanObject.toString())
        } catch (error: Exception) {
            return CompileResult(prose, null, "meta_parse_error", detail = safeDetail(error))
        }
        val code = meta.errorCode
        if (code != null && !ERROR_CODE.matches(code)) {
            return CompileResult(prose, null, "meta_parse_error", detail = "error_code non valido")
        }
        val draft = meta.draft
        if (draft != null && code != null) {
            return CompileResult(prose, null, "meta_parse_error", detail = "draft e error_code non possono coesistere")
        }
        if (draft == null) return CompileResult(prose, null, code ?: "draft_missing")
        return CompileResult(prose, draft.withStandardCron().withStandardRingerModes(), null)
    }

    /**
     * GPT-6 Luna e Haiku 5.5 scrivono il cron alla Quartz, coi secondi in testa (`0 30 22 * * *`), che il
     * validatore rifiuta con `cron_invalid`. Qui, PRIMA della validazione, un cron `0 <5 campi validi>`
     * diventa i 5 campi: l'utente approva la forma standard. Tutto il resto resta com'è.
     */
    private fun AutomationDraft.withStandardCron(): AutomationDraft {
        val time = trigger as? Trigger.Time ?: return this
        val cron = time.cron ?: return this
        val standard = CronSchedule.dropLeadingZeroSeconds(cron)
        return if (standard == cron) this else copy(trigger = time.copy(cron = standard))
    }

    /**
     * Otto modelli su undici scrivono la suoneria in maiuscolo (`"VIBRATE"`, come i valori di `set_dnd`), che
     * il validatore rifiuta con `ringer_mode_invalid`. Qui, PRIMA della validazione, una modalità che in
     * minuscolo è valida diventa minuscola, anche dentro if/while. Interpolazioni e valori sconosciuti
     * restano come sono: decide il validatore.
     */
    private fun AutomationDraft.withStandardRingerModes(): AutomationDraft {
        val standard = standardRingerModes(actions, depth = 0)
        return if (standard == actions) this else copy(actions = standard)
    }

    private fun standardRingerModes(actions: List<Action>, depth: Int): List<Action> {
        // Oltre questa profondità il validatore rifiuta comunque la bozza (`flow_too_deep`): la visita si
        // ferma lì, così un albero non ancora validato non fa crescere la ricorsione.
        if (depth > MAX_NORMALIZE_DEPTH) return actions
        return actions.map { action ->
            when (action) {
                is Action.SetRinger -> {
                    val mode = action.mode.trim().lowercase(Locale.ROOT)
                    if (mode != action.mode && mode in RINGER_MODES) action.copy(mode = mode) else action
                }
                is Action.If -> action.copy(
                    then = standardRingerModes(action.then, depth + 1),
                    orElse = standardRingerModes(action.orElse, depth + 1),
                )
                is Action.While -> action.copy(body = standardRingerModes(action.body, depth + 1))
                else -> action
            }
        }
    }

    /** Senza sentinel: l'ultimo blocco ```json il cui oggetto ha la chiave `draft`. */
    private fun parseFencedFallback(text: String): CompileResult? {
        val fences = FENCE.findAll(text).toList().asReversed()
        for (fence in fences) {
            val body = extractJsonObject(fence.groupValues[1]) ?: continue
            val parsed = try {
                json.parseToJsonElement(body).jsonObject
            } catch (_: Exception) {
                continue
            }
            if (!hasDraftKey(parsed)) continue
            return parseMeta(prose = text.substring(0, fence.range.first).trim(), tail = body)
        }
        return null
    }

    /**
     * Senza `@@META@@` né blocco: l'ultimo marcatore `@@NOME@@` seguito da un oggetto JSON. È meta solo se
     * l'oggetto ha `draft` o `error_code`; altrimenti (o se l'oggetto non è JSON) il testo resta prosa.
     */
    private fun parseMisnamedSentinelFallback(text: String): CompileResult? {
        val marker = MISNAMED_SENTINEL.findAll(text)
            .lastOrNull { text.startsWith("{", startIndex = firstNonBlank(text, it.range.last + 1)) }
            ?: return null
        val body = extractJsonObject(text.substring(marker.range.last + 1)) ?: return null
        val parsed = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        if (!hasDraftKey(parsed) && "error_code" !in parsed) return null
        return parseMeta(prose = text.substring(0, marker.range.first).trim(), tail = body)
    }

    private fun firstNonBlank(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun hasDraftKey(obj: JsonObject): Boolean = "draft" in obj

    /** Toglie dagli oggetti le chiavi con valore `null`, ricorsivamente (gli array restano com'erano). */
    private fun stripNulls(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterValues { it !is JsonNull }.mapValues { (_, value) -> stripNulls(value) },
        )
        is JsonArray -> JsonArray(element.map(::stripNulls))
        else -> element
    }

    /** Solo percorso e tipo dell'errore kotlinx: mai il frammento di input (può contenere dati utente). */
    private fun safeDetail(error: Exception): String {
        val message = error.message.orEmpty()
        val withoutInput = message.substringBefore("JSON input:").trim()
        return withoutInput.replace(Regex("\\s+"), " ").take(MAX_DETAIL_CHARS)
            .ifBlank { error::class.simpleName.orEmpty() }
    }

    /**
     * Primo oggetto JSON BILANCIATO (graffe contate fuori da stringhe e commenti): robusto a prosa
     * dopo il meta.
     */
    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '/' && s.startsWith("//", i) -> {
                    val eol = s.indexOf('\n', i)
                    if (eol < 0) return null
                    i = eol
                }
                !inStr && c == '/' && s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) return null
                    i = end + 1
                }
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    private companion object {
        val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
        val FENCE = Regex("```[A-Za-z]*\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val MISNAMED_SENTINEL = Regex("@@[A-Z][A-Z0-9_]{1,40}@@")

        /** Le chiavi che distinguono un envelope strutturato da un oggetto JSON qualunque. */
        val ENVELOPE_KEY = Regex("\"(?:draft|error_code)\"\\s*:")

        /** Le modalità che il validatore accetta per `set_ringer`. */
        val RINGER_MODES = setOf("normal", "vibrate", "silent")

        /** Più profondo del limite if/while del validatore: ogni bozza armabile è normalizzata tutta. */
        const val MAX_NORMALIZE_DEPTH = 8

        @OptIn(ExperimentalSerializationApi::class)
        fun lenient() = Json(ArgusJson) {
            isLenient = true
            ignoreUnknownKeys = true
            allowTrailingComma = true
            allowComments = true
        }
    }
}
