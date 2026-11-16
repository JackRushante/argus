package dev.argus.engine.brain
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.CronSchedule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
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
    /** S9: la stessa tolleranza di sintassi di [json] (commenti, virgola finale), ma nessuna chiave sconosciuta. */
    private val strictJson = Json(json) { ignoreUnknownKeys = false }

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
            // S9: fuori dalla bozza l'envelope può portare altro (`reply` dell'output strutturato), che
            // si ignora; DENTRO la bozza ogni chiave sconosciuta è un errore. `"condition"` al posto di
            // `"conditions"` o `"else"` al posto di `"orElse"` farebbero sparire una guardia in silenzio
            // e la regola sarebbe più larga di quanto il modello dice.
            val envelope = (cleanObject as? JsonObject)?.filterKeys { it in ENVELOPE_FIELDS }
                ?.let(::JsonObject)
                ?: cleanObject
            (envelope as? JsonObject)?.get("draft")?.let { draft ->
                unknownDraftKey(draft)?.let { return CompileResult(prose, null, CompileMetaError.DRAFT_INVALID, detail = it) }
            }
            strictJson.decodeFromString(MetaEnvelope.serializer(), envelope.toString())
        } catch (error: Exception) {
            unknownKeyDetail(error)?.let { return CompileResult(prose, null, CompileMetaError.DRAFT_INVALID, detail = it) }
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

    /**
     * S9: la prima chiave della bozza che lo schema non conosce, come `unknown key "condition" at $.draft`
     * (il percorso è l'oggetto che la contiene). Visita l'albero seguendo i descrittori dei tipi: per le
     * gerarchie sealed sceglie la sottoclasse dal discriminatore (`type`, `op` per i passi delle macro).
     * Un discriminatore sconosciuto o un valore del tipo sbagliato non si giudicano qui: ci pensa la
     * decodifica, che resta comunque rigorosa. Nessun valore dell'input entra nel dettaglio, solo il nome
     * della chiave, ripulito e accorciato.
     */
    private fun unknownDraftKey(draft: JsonElement): String? {
        val found = findUnknownKey(draft, AutomationDraft.serializer().descriptor, "$.draft", null, 0)
            ?: return null
        return "unknown key \"${cleanKey(found.first)}\" at ${found.second}: use only the exact field names of the schema"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun findUnknownKey(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
        discriminator: String?,
        depth: Int,
    ): Pair<String, String>? {
        if (depth > MAX_KEY_SCAN_DEPTH) return null
        return when (descriptor.kind) {
            PolymorphicKind.SEALED -> {
                val obj = element as? JsonObject ?: return null
                val key = descriptor.annotations.filterIsInstance<JsonClassDiscriminator>().firstOrNull()?.discriminator
                    ?: json.configuration.classDiscriminator
                val type = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val subclasses = descriptor.getElementDescriptor(1)
                val sub = (0 until subclasses.elementsCount)
                    .map(subclasses::getElementDescriptor)
                    .firstOrNull { it.serialName == type }
                    ?: return null
                findUnknownKey(obj, sub, path, key, depth + 1)
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return null
                obj.keys.firstOrNull { it != discriminator && descriptor.getElementIndex(it) == CompositeDecoder.UNKNOWN_NAME }
                    ?.let { return it to path }
                obj.entries.firstNotNullOfOrNull { (name, value) ->
                    val index = descriptor.getElementIndex(name)
                    if (index == CompositeDecoder.UNKNOWN_NAME) return@firstNotNullOfOrNull null
                    findUnknownKey(value, descriptor.getElementDescriptor(index), "$path.$name", null, depth + 1)
                }
            }
            StructureKind.LIST -> {
                val array = element as? JsonArray ?: return null
                val item = descriptor.getElementDescriptor(0)
                array.withIndex().firstNotNullOfOrNull { (index, value) ->
                    findUnknownKey(value, item, "$path[$index]", null, depth + 1)
                }
            }
            StructureKind.MAP -> {
                val obj = element as? JsonObject ?: return null
                val value = descriptor.getElementDescriptor(1)
                obj.entries.firstNotNullOfOrNull { (name, item) ->
                    findUnknownKey(item, value, "$path[\"${cleanKey(name)}\"]", null, depth + 1)
                }
            }
            else -> null
        }
    }

    /** Ripiego: una chiave sconosciuta che la visita non ha trovato e che la decodifica rigorosa ha visto. */
    private fun unknownKeyDetail(error: Exception): String? {
        val key = UNKNOWN_KEY.find(error.message.orEmpty())?.groupValues?.get(1) ?: return null
        return "unknown key \"${cleanKey(key)}\" in the draft: use only the exact field names of the schema"
    }

    /** Il nome di una chiave scritta dal modello, senza caratteri strani e mai più lungo di 40 caratteri. */
    private fun cleanKey(key: String): String =
        key.map { if (it.isLetterOrDigit() && it.code < 0x80 || it == '_' || it == '-' || it == '.') it else '?' }
            .joinToString("")
            .take(MAX_KEY_CHARS)

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

        /** I soli campi dell'envelope che contano: gli altri (`reply`) restano fuori dalla decodifica rigorosa. */
        val ENVELOPE_FIELDS = setOf("draft", "error_code")

        /** Il nome della chiave nel messaggio di kotlinx (`Encountered an unknown key 'x'`). */
        val UNKNOWN_KEY = Regex("unknown key '([^']{1,200})'")

        /** Ben oltre il limite di annidamento del validatore: la visita delle chiavi non cresce senza fine. */
        const val MAX_KEY_SCAN_DEPTH = 64
        const val MAX_KEY_CHARS = 40

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
