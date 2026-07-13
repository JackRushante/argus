package dev.argus.engine.brain
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SENTINEL = "@@META@@"

@Serializable private data class MetaEnvelope(val draft: AutomationDraft? = null)

/** Parser puro dell'output CliBridge (spec §2/§7). Fail-soft: mai crash, sempre reply + eventuale metaError. */
class CliBridgeParser(private val json: Json = lenient()) {
    fun parseCompile(raw: String): CompileResult {
        val idx = raw.indexOf(SENTINEL)
        if (idx < 0) return CompileResult(raw.trim(), null, null)
        val prose = raw.substring(0, idx).trim()
        val obj = extractJsonObject(raw.substring(idx + SENTINEL.length))
            ?: return CompileResult(prose, null, "nessun oggetto JSON dopo il sentinel")
        return try {
            val draft = json.decodeFromString(MetaEnvelope.serializer(), obj).draft
            if (draft == null) CompileResult(prose, null, "meta presente ma senza campo 'draft'")
            else CompileResult(prose, draft, null)
        } catch (e: Exception) {
            CompileResult(prose, null, e.message ?: "meta parse error")
        }
    }

    /** Primo oggetto JSON BILANCIATO (graffe contate fuori dalle stringhe): robusto a prosa dopo il meta. */
    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }

    private companion object { fun lenient() = Json(ArgusJson) { isLenient = true; ignoreUnknownKeys = true } }
}
