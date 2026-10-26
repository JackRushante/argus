package dev.argus.automation.manual

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.AutomationId
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Token casuale per regola (0.4.1, F8). Le scorciatoie sulla Home e i widget portano nell'intent
 * l'id della regola E questo token: un intent costruito da un'altra app, che l'id può al massimo
 * indovinarlo ma il token no, non lancia nessuna regola. Il token vive solo nelle preferenze
 * dell'app, non esce mai dal dispositivo e non entra nel Log.
 */
interface ManualTokenStore {
    /** Il token della regola; lo crea alla prima richiesta e poi resta stabile (le scorciatoie già fissate valgono). */
    fun tokenFor(id: AutomationId): String

    /** true solo se [token] è quello della regola. Confronto a tempo costante; un token assente o vuoto non vale mai. */
    fun isValid(id: AutomationId, token: String?): Boolean

    /** Dimentica il token (regola eliminata): le scorciatoie rimaste smettono di funzionare. */
    fun forget(id: AutomationId)
}

class PrefsManualTokenStore internal constructor(
    private val preferences: SharedPreferences,
    private val random: () -> ByteArray = { SecureRandom().let { rng -> ByteArray(TOKEN_BYTES).also(rng::nextBytes) } },
) : ManualTokenStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    override fun tokenFor(id: AutomationId): String = synchronized(lock) {
        val key = key(id)
        preferences.getString(key, null)?.takeIf(::wellFormed)
            ?: hex(random()).also { created ->
                // commit(): il token finisce in una scorciatoia fissata; se il processo muore prima
                // della scrittura la scorciatoia non varrebbe più.
                preferences.edit().putString(key, created).commit()
            }
    }

    override fun isValid(id: AutomationId, token: String?): Boolean {
        if (token.isNullOrEmpty() || !wellFormed(token)) return false
        val stored = synchronized(lock) { preferences.getString(key(id), null) } ?: return false
        return MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
    }

    override fun forget(id: AutomationId) {
        synchronized(lock) { preferences.edit().remove(key(id)).commit() }
    }

    private fun key(id: AutomationId): String = KEY_PREFIX + id.value

    private fun wellFormed(token: String): Boolean = token.length == TOKEN_BYTES * 2 && token.all { it in HEX }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    companion object {
        const val TOKEN_BYTES = 16
        private const val PREFERENCES_NAME = "argus_manual_tokens"
        private const val KEY_PREFIX = "token."
        private const val HEX = "0123456789abcdef"
    }
}
