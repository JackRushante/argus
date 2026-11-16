package dev.argus.engine.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Capture of a web response into a program variable (0.8, [FeatureGate.HTTP_CAPTURE]): `captureAs` plus an
 * optional `extract`, a JSON pointer (RFC 6901). Pure: the executor gives it the body it read (already
 * within [HttpRequestPolicy.MAX_RESPONSE_BYTES]) and gets back the text or a closed failure code. The text
 * never reaches the journal: the Log keeps the status code only.
 *
 * Rules: the pointed value must be a JSON string, number or boolean (rendered as text); an object, an
 * array, null or a missing path fail. Without a pointer the whole body is the value. After trimming (and
 * CRLF read as LF) the value is at most [MAX_CAPTURE_CHARS] characters with no control characters but
 * newline and tab: a longer value fails rather than being cut.
 */
object HttpCapturePolicy {
    const val MAX_CAPTURE_CHARS = 4_096
    const val MAX_POINTER_CHARS = 256

    const val NOT_JSON = "http_capture_not_json"
    const val PATH_MISSING = "http_capture_path_missing"
    const val NOT_SCALAR = "http_capture_not_scalar"
    const val TOO_LONG = "http_capture_too_long"
    const val INVALID_CHARS = "http_capture_invalid_chars"
    const val RESPONSE_TOO_LARGE = "http_capture_response_too_large"

    sealed interface Outcome {
        class Captured(val text: String) : Outcome {
            override fun toString(): String = "Captured(text=<redacted>)"
        }

        data class Failed(val code: String) : Outcome
    }

    private val ARRAY_INDEX = Regex("^(0|[1-9][0-9]{0,8})$")
    private val parser = Json { isLenient = false }

    /**
     * A literal pointer: starts with `/`, at most [MAX_POINTER_CHARS] characters, every `~` followed by `0`
     * or `1`, no control characters and no `${'$'}{…}` (the pointer is approved as written).
     */
    fun isPointerValid(pointer: String): Boolean {
        if (pointer.isEmpty() || pointer.length > MAX_POINTER_CHARS || pointer[0] != '/') return false
        if (pointer.any { it.isISOControl() }) return false
        if (InterpolationPolicy.containsInterpolation(pointer)) return false
        pointer.forEachIndexed { index, c ->
            if (c == '~' && (index + 1 >= pointer.length || pointer[index + 1] !in "01")) return false
        }
        return true
    }

    /** The reference tokens of a valid pointer, unescaped (`~1` = `/`, `~0` = `~`, in this order). */
    fun tokensOf(pointer: String): List<String> =
        pointer.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }

    /** The value [body] gives under [pointer] (null = the whole body), checked and trimmed. */
    fun capture(body: String, pointer: String?): Outcome {
        val raw = if (pointer == null) body else when (val pointed = pointed(body, pointer)) {
            is Outcome.Captured -> pointed.text
            is Outcome.Failed -> return pointed
        }
        return checked(raw)
    }

    private fun pointed(body: String, pointer: String): Outcome {
        if (!isPointerValid(pointer)) return Outcome.Failed(PATH_MISSING)
        // A pointer always goes into an object or an array. The parser reads some bare text as a string
        // even when strict, so a body that does not open a JSON container is simply not JSON.
        val start = body.trimStart()
        if (!start.startsWith('{') && !start.startsWith('[')) return Outcome.Failed(NOT_JSON)
        val root: JsonElement = try {
            parser.parseToJsonElement(body)
        } catch (_: Exception) {
            return Outcome.Failed(NOT_JSON)
        } catch (_: StackOverflowError) {
            return Outcome.Failed(NOT_JSON)
        }
        var current: JsonElement = root
        for (token in tokensOf(pointer)) {
            current = when (val node = current) {
                is JsonObject -> node[token] ?: return Outcome.Failed(PATH_MISSING)
                is JsonArray -> {
                    if (!ARRAY_INDEX.matches(token)) return Outcome.Failed(PATH_MISSING)
                    node.getOrNull(token.toInt()) ?: return Outcome.Failed(PATH_MISSING)
                }
                else -> return Outcome.Failed(PATH_MISSING)
            }
        }
        return when (val value = current) {
            is JsonNull -> Outcome.Failed(NOT_SCALAR)
            is JsonPrimitive -> Outcome.Captured(value.content)
            is JsonObject, is JsonArray -> Outcome.Failed(NOT_SCALAR)
        }
    }

    private fun checked(raw: String): Outcome {
        val text = raw.replace("\r\n", "\n").trim()
        if (text.length > MAX_CAPTURE_CHARS) return Outcome.Failed(TOO_LONG)
        if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) return Outcome.Failed(INVALID_CHARS)
        return Outcome.Captured(text)
    }
}
