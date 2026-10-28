package dev.argus.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Formato dei messaggi dell'aggancio. Dalla shell di adb passano più livelli di virgolette (ssh, shell
 * dell'host, shell del telefono): l'argomento viaggia quindi in base64 (solo `A-Za-z0-9+/=`), e il
 * risultato torna come base64 in un'unica chiave `r`. Per le prove a mano si accetta anche un JSON
 * grezzo, riconosciuto dalla graffa iniziale.
 */
object E2eWire {
    const val RESULT_KEY = "r"

    private val json = Json { ignoreUnknownKeys = false }

    /** L'argomento della chiamata come oggetto JSON; vuoto o assente = oggetto vuoto. */
    fun decodeRequest(arg: String?): JsonObject {
        val text = arg?.trim().orEmpty()
        if (text.isEmpty()) return JsonObject(emptyMap())
        val raw = if (text.startsWith("{")) text else String(Base64.getDecoder().decode(text), Charsets.UTF_8)
        return json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("la richiesta non è un oggetto JSON")
    }

    fun encodeResult(result: JsonObject): String =
        Base64.getEncoder().encodeToString(result.toString().toByteArray(Charsets.UTF_8))

    fun decodeResult(encoded: String): JsonObject =
        json.parseToJsonElement(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)) as JsonObject
}
