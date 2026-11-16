package dev.argus.automation.telephony

import android.content.Context
import dev.argus.engine.model.TelephonyActionPolicy
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Invii recenti, persistiti: [sms] sono coppie (istante, impronta del numero), [calls] gli istanti delle
 * chiamate. Il numero non si salva mai in chiaro: l'impronta è un hash salato per installazione.
 */
data class TelephonyLedger(
    val sms: List<Pair<Long, String>> = emptyList(),
    val calls: List<Long> = emptyList(),
)

interface TelephonyLedgerStore {
    fun read(): TelephonyLedger
    fun write(ledger: TelephonyLedger)

    /** Impronta stabile di [number] per contare gli SMS verso lo stesso numero. */
    fun destinationKey(number: String): String
}

/** Esito di una richiesta di invio: concessa (e già contata) oppure fermata dal tetto [code]. */
sealed interface RateVerdict {
    data object Allowed : RateVerdict
    data class Limited(val code: String) : RateVerdict
}

/**
 * Tetti orari di tutta l'app (0.8): 10 SMS l'ora, 3 l'ora verso lo stesso numero, 3 chiamate l'ora. Il
 * conteggio è su finestra mobile di un'ora e sopravvive al riavvio del processo. Un invio si conta PRIMA di
 * partire: se poi fallisce resta contato (nel dubbio si conta di più, mai di meno). Tutto sotto un lock:
 * due regole che scattano insieme non superano il tetto.
 */
class TelephonyRateLimiter(
    private val store: TelephonyLedgerStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun trySms(number: String): RateVerdict = synchronized(lock) {
        val at = now()
        val ledger = pruned(store.read(), at)
        val key = store.destinationKey(number)
        when {
            ledger.sms.size >= TelephonyActionPolicy.SMS_PER_HOUR -> RateVerdict.Limited(SMS_RATE_LIMITED)
            ledger.sms.count { it.second == key } >= TelephonyActionPolicy.SMS_PER_DESTINATION_PER_HOUR ->
                RateVerdict.Limited(SMS_DESTINATION_RATE_LIMITED)
            else -> {
                store.write(ledger.copy(sms = ledger.sms + (at to key)))
                RateVerdict.Allowed
            }
        }
    }

    fun tryCall(): RateVerdict = synchronized(lock) {
        val at = now()
        val ledger = pruned(store.read(), at)
        if (ledger.calls.size >= TelephonyActionPolicy.CALLS_PER_HOUR) {
            RateVerdict.Limited(CALL_RATE_LIMITED)
        } else {
            store.write(ledger.copy(calls = ledger.calls + at))
            RateVerdict.Allowed
        }
    }

    /** Solo gli invii dell'ultima ora; un istante nel futuro (orologio spostato indietro) resta contato. */
    private fun pruned(ledger: TelephonyLedger, at: Long): TelephonyLedger = TelephonyLedger(
        sms = ledger.sms.filter { at - it.first < WINDOW_MILLIS },
        calls = ledger.calls.filter { at - it < WINDOW_MILLIS },
    )

    companion object {
        const val WINDOW_MILLIS = 60 * 60 * 1_000L
        const val SMS_RATE_LIMITED = "sms_rate_limited"
        const val SMS_DESTINATION_RATE_LIMITED = "sms_destination_rate_limited"
        const val CALL_RATE_LIMITED = "call_rate_limited"
    }
}

/** Registro su SharedPreferences, scritto con `commit()`: il conteggio deve esserci prima dell'invio. */
class PrefsTelephonyLedgerStore(context: Context) : TelephonyLedgerStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): TelephonyLedger = TelephonyLedger(
        sms = prefs.getString(KEY_SMS, null).orEmpty().split(',').mapNotNull { entry ->
            val at = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val key = entry.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            at to key
        },
        calls = prefs.getString(KEY_CALLS, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() },
    )

    override fun write(ledger: TelephonyLedger) {
        prefs.edit()
            .putString(KEY_SMS, ledger.sms.joinToString(",") { "${it.first}:${it.second}" })
            .putString(KEY_CALLS, ledger.calls.joinToString(","))
            .commit()
    }

    override fun destinationKey(number: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt() + number).toByteArray(Charsets.UTF_8))
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun salt(): String = prefs.getString(KEY_SALT, null) ?: synchronized(this) {
        prefs.getString(KEY_SALT, null) ?: ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString(KEY_SALT, it).commit() }
    }

    private companion object {
        const val PREFS_NAME = "argus_telephony_rate"
        const val KEY_SMS = "sms"
        const val KEY_CALLS = "calls"
        const val KEY_SALT = "salt"
        const val SALT_BYTES = 16
        const val KEY_BYTES = 12
    }
}
