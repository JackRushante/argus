package dev.argus.engine.brain
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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

    private fun parseMeta(prose: String, tail: String): CompileResult {
        val obj = extractJsonObject(tail)
            ?: return CompileResult(prose, null, "meta_json_missing")
        val meta = try {
            // `null` al posto di un campo con default (es. `"rationale": null`) vale come campo
            // assente. Volutamente NON `coerceInputValues`: coercerebbe anche un valore enum
            // sconosciuto al default (es. un sink scritto male → WHATSAPP_REPLY) in silenzio.
            json.decodeFromJsonElement(MetaEnvelope.serializer(), stripNulls(json.parseToJsonElement(obj)))
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
        return CompileResult(prose, draft, null)
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

        @OptIn(ExperimentalSerializationApi::class)
        fun lenient() = Json(ArgusJson) {
            isLenient = true
            ignoreUnknownKeys = true
            allowTrailingComma = true
            allowComments = true
        }
    }
}
