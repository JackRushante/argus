package dev.argus.automation.prompt

import android.content.Context
import android.content.SharedPreferences
import dev.argus.automation.DeferredReplyCipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/** Che cosa aspetta una risposta dell'utente (0.8). */
enum class PromptKind {
    /** «Eseguire «regola»?» di una regola con `confirm`: Esegui / Annulla. */
    CONFIRM,

    /** Una domanda `ask` dentro un programma: un pulsante per scelta. */
    ASK,
}

/**
 * Una domanda in attesa. Mai una data class e mai in un log: [envelope] (l'evento che ha fatto scattare
 * la regola) e [prompt] possono contenere il testo di un messaggio. [token] è il segreto dei pulsanti
 * della notifica; [id] è l'identità casuale della domanda (nome del file e della notifica).
 */
class PendingPrompt internal constructor(
    val kind: PromptKind,
    val id: String,
    val token: String,
    val automationId: String,
    val ruleName: String,
    val fingerprint: String,
    val executionId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    /** CONFIRM: l'evento in forma [dev.argus.engine.runtime.TriggerEnvelopeCodec]. */
    val envelope: String? = null,
    /** ASK: il testo risolto della domanda e le etichette approvate. */
    val prompt: String? = null,
    val choices: List<String> = emptyList(),
) {
    fun expired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    override fun toString(): String = "PendingPrompt(kind=$kind, id=$id, automationId=$automationId, <redacted>)"

    internal fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(VERSION),
                "kind" to JsonPrimitive(kind.name),
                "id" to JsonPrimitive(id),
                "token" to JsonPrimitive(token),
                "automationId" to JsonPrimitive(automationId),
                "ruleName" to JsonPrimitive(ruleName),
                "fingerprint" to JsonPrimitive(fingerprint),
                "executionId" to JsonPrimitive(executionId),
                "createdAt" to JsonPrimitive(createdAtMillis),
                "expiresAt" to JsonPrimitive(expiresAtMillis),
                "envelope" to (envelope?.let(::JsonPrimitive) ?: JsonNull),
                "prompt" to (prompt?.let(::JsonPrimitive) ?: JsonNull),
                "choices" to JsonArray(choices.map(::JsonPrimitive)),
            ),
        ),
    )

    internal companion object {
        const val VERSION = 1

        /** null se il blob non è una domanda di questa versione, mai una domanda parziale. */
        fun fromJson(text: String): PendingPrompt? {
            return try {
                parse(text)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun parse(text: String): PendingPrompt? {
            val o = Json.parseToJsonElement(text).jsonObject
            fun string(name: String): String? =
                o[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.takeIf { it.isString }?.content
            fun long(name: String): Long? = o[name]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull
            return if (long("v") != VERSION.toLong()) {
                null
            } else {
                PendingPrompt(
                    kind = PromptKind.entries.firstOrNull { it.name == string("kind") } ?: return null,
                    id = string("id")?.takeIf(PendingPromptStore::wellFormedId) ?: return null,
                    token = string("token")?.takeIf(PendingPromptStore::wellFormedToken) ?: return null,
                    automationId = string("automationId") ?: return null,
                    ruleName = string("ruleName") ?: return null,
                    fingerprint = string("fingerprint") ?: return null,
                    executionId = string("executionId") ?: return null,
                    createdAtMillis = long("createdAt") ?: return null,
                    expiresAtMillis = long("expiresAt") ?: return null,
                    envelope = string("envelope"),
                    prompt = string("prompt"),
                    choices = o["choices"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null,
                )
            }
        }
    }
}

/** Dove stanno i blob cifrati: preferenze private dell'app in produzione, una mappa nei test. */
interface PromptBlobs {
    fun all(): Map<String, String>
    fun put(key: String, value: String): Boolean
    fun remove(key: String)
}

class PrefsPromptBlobs(private val preferences: SharedPreferences) : PromptBlobs {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun all(): Map<String, String> =
        preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    // commit(): la domanda deve esserci davvero prima di mostrare la notifica.
    override fun put(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()

    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "argus_pending_prompts"
    }
}

/**
 * Le domande in attesa (0.8 `confirm` e `ask`), cifrate una per una con la chiave non esportabile di
 * Android Keystore delle risposte differite (AES-GCM). Nessuno schema Room: un file di preferenze
 * privato, al massimo [MAX_PENDING] domande, ognuna con la sua scadenza.
 */
class PendingPromptStore(
    private val blobs: PromptBlobs,
    private val cipher: DeferredReplyCipher,
    private val random: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
) {
    private val lock = Any()

    sealed interface Creation {
        class Created(val prompt: PendingPrompt) : Creation

        /** CONFIRM: la regola ha già una domanda aperta (una alla volta). */
        data object AlreadyPending : Creation

        /** Troppe domande aperte o archivio non scrivibile. */
        data object Failed : Creation
    }

    fun create(
        kind: PromptKind,
        automationId: String,
        ruleName: String,
        fingerprint: String,
        executionId: String,
        createdAtMillis: Long,
        expiresAtMillis: Long,
        envelope: String? = null,
        prompt: String? = null,
        choices: List<String> = emptyList(),
    ): Creation = synchronized(lock) {
        try {
            val open = readAll()
            if (kind == PromptKind.CONFIRM && open.any {
                    it.kind == PromptKind.CONFIRM && it.automationId == automationId && !it.expired(createdAtMillis)
                }
            ) {
                return@synchronized Creation.AlreadyPending
            }
            if (open.size >= MAX_PENDING) return@synchronized Creation.Failed
            val created = PendingPrompt(
                kind = kind,
                id = hex(random(ID_BYTES)),
                token = hex(random(TOKEN_BYTES)),
                automationId = automationId,
                ruleName = ruleName,
                fingerprint = fingerprint,
                executionId = executionId,
                createdAtMillis = createdAtMillis,
                expiresAtMillis = expiresAtMillis,
                envelope = envelope,
                prompt = prompt,
                choices = choices,
            )
            if (blobs.put(KEY_PREFIX + created.id, cipher.encrypt(created.toJson()))) {
                Creation.Created(created)
            } else {
                Creation.Failed
            }
        } catch (_: Exception) {
            Creation.Failed
        }
    }

    fun get(id: String): PendingPrompt? = synchronized(lock) { read(id) }

    fun all(): List<PendingPrompt> = synchronized(lock) { readAll() }

    /**
     * Toglie e restituisce la domanda solo se [token] è il suo (confronto a tempo costante), non è
     * scaduta e [accepts] la accetta. Un token sbagliato non la tocca. Una sola consumazione: un secondo
     * tocco trova null.
     */
    fun consume(
        id: String,
        token: String?,
        nowMillis: Long,
        accepts: (PendingPrompt) -> Boolean = { true },
    ): PendingPrompt? = synchronized(lock) {
        if (!wellFormedId(id) || token == null || !wellFormedToken(token)) return@synchronized null
        val prompt = read(id) ?: return@synchronized null
        if (!MessageDigest.isEqual(prompt.token.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))) {
            return@synchronized null
        }
        if (prompt.expired(nowMillis) || !accepts(prompt)) return@synchronized null
        blobs.remove(KEY_PREFIX + id)
        prompt
    }

    /** true se c'era. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val present = (KEY_PREFIX + id) in blobs.all()
        blobs.remove(KEY_PREFIX + id)
        present
    }

    /** Revoca della privacy o reset: nessuna domanda resta. */
    fun clear() = synchronized(lock) {
        blobs.all().keys.filter { it.startsWith(KEY_PREFIX) }.forEach(blobs::remove)
    }

    private fun read(id: String): PendingPrompt? {
        if (!wellFormedId(id)) return null
        val blob = blobs.all()[KEY_PREFIX + id] ?: return null
        return decode(KEY_PREFIX + id, blob)
    }

    private fun readAll(): List<PendingPrompt> =
        blobs.all().filterKeys { it.startsWith(KEY_PREFIX) }.mapNotNull { (key, blob) -> decode(key, blob) }

    /** Un blob illeggibile (chiave cambiata, file manomesso) si butta: mai una domanda a metà. */
    private fun decode(key: String, blob: String): PendingPrompt? {
        val prompt = try {
            PendingPrompt.fromJson(cipher.decrypt(blob))
        } catch (_: Exception) {
            null
        }
        if (prompt == null || KEY_PREFIX + prompt.id != key) {
            blobs.remove(key)
            return null
        }
        return prompt
    }

    companion object {
        const val MAX_PENDING = 32
        private const val KEY_PREFIX = "p."
        private const val ID_BYTES = 16
        private const val TOKEN_BYTES = 32
        private const val HEX = "0123456789abcdef"

        fun wellFormedId(id: String): Boolean = id.length == ID_BYTES * 2 && id.all { it in HEX }

        fun wellFormedToken(token: String): Boolean = token.length == TOKEN_BYTES * 2 && token.all { it in HEX }

        private fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
            }
            return out.toString()
        }
    }
}
