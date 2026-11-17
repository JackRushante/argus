package dev.argus.automation.telephony

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.argus.engine.model.TelephonyActionPolicy
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Quando è avvenuto un invio: l'ora di parete, il numero d'avvio del telefono e il tempo dall'avvio. Nello
 * stesso avvio conta il tempo dall'avvio, che non si sposta con l'orologio; dopo un riavvio l'ora di parete.
 */
data class ClockReading(val wallMillis: Long, val bootCount: Int, val elapsedMillis: Long)

/** L'orologio dei tetti orari (finto nei test). */
fun interface TelephonyClock {
    fun now(): ClockReading
}

/** Orologio del telefono: `currentTimeMillis`, `Settings.Global.BOOT_COUNT` (-1 se non si legge), `elapsedRealtime`. */
class AndroidTelephonyClock(context: Context) : TelephonyClock {
    private val resolver = context.applicationContext.contentResolver

    override fun now(): ClockReading = ClockReading(
        wallMillis = System.currentTimeMillis(),
        bootCount = runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT) }
            .getOrDefault(UNKNOWN_BOOT),
        elapsedMillis = SystemClock.elapsedRealtime(),
    )
}

/** Avvio non noto: l'invio si giudica solo con l'ora di parete. */
const val UNKNOWN_BOOT = -1

/** Un invio contato; [destination] è l'impronta del numero (solo per gli SMS), mai il numero in chiaro. */
data class TelephonySend(val at: ClockReading, val destination: String? = null)

/** Invii recenti, persistiti: [sms] con l'impronta del destinatario, [calls] senza. */
data class TelephonyLedger(
    val sms: List<TelephonySend> = emptyList(),
    val calls: List<TelephonySend> = emptyList(),
)

interface TelephonyLedgerStore {
    fun read(): TelephonyLedger

    /** Scrive in modo durevole; false se non ci è riuscito (l'invio allora non parte). */
    fun write(ledger: TelephonyLedger): Boolean

    /** Impronta stabile di [number] per contare gli SMS verso lo stesso numero. */
    fun destinationKey(number: String): String
}

/** Esito di una richiesta di invio: concessa (e già contata) oppure fermata dal tetto [code]. */
sealed interface RateVerdict {
    data object Allowed : RateVerdict
    data class Limited(val code: String) : RateVerdict
}

/**
 * Tetti orari di tutta l'app (0.8): 10 SMS l'ora, 3 l'ora verso lo stesso numero, 3 chiamate l'ora, su una
 * finestra mobile di un'ora che sopravvive alla morte del processo e al riavvio.
 *
 *  - Controllo e conteggio sono un'unica operazione sotto un lock di processo (il filtro chiamate e il motore
 *    girano nello stesso processo): due regole che scattano insieme non prendono entrambe l'ultimo posto.
 *  - Un invio si conta PRIMA di partire e su disco: se il registro non si scrive l'invio non parte; se l'invio
 *    poi fallisce resta contato (nel dubbio si conta di più, mai di meno).
 *  - Orologio: nello stesso avvio vale il tempo dall'avvio, quindi spostare l'ora avanti o indietro non libera
 *    posti. Dopo un riavvio vale l'ora di parete, e un invio «nel futuro» (orologio tornato indietro) si
 *    riporta ad adesso: resta contato per un'ora intera e poi scade, invece di bloccare per sempre.
 *  - Un registro illeggibile conta come un invio appena fatto.
 */
class TelephonyRateLimiter(
    private val store: TelephonyLedgerStore,
    private val clock: TelephonyClock,
) {
    fun trySms(number: String): RateVerdict = guarded {
        val at = clock.now()
        val stored = store.read()
        val ledger = pruned(stored, at)
        val key = store.destinationKey(number)
        when {
            ledger.sms.size >= TelephonyActionPolicy.SMS_PER_HOUR -> limited(stored, ledger, SMS_RATE_LIMITED)
            ledger.sms.count { it.destination == key } >= TelephonyActionPolicy.SMS_PER_DESTINATION_PER_HOUR ->
                limited(stored, ledger, SMS_DESTINATION_RATE_LIMITED)
            else -> commit(ledger.copy(sms = ledger.sms + TelephonySend(at, key)))
        }
    }

    fun tryCall(): RateVerdict = guarded {
        val at = clock.now()
        val stored = store.read()
        val ledger = pruned(stored, at)
        if (ledger.calls.size >= TelephonyActionPolicy.CALLS_PER_HOUR) {
            limited(stored, ledger, CALL_RATE_LIMITED)
        } else {
            commit(ledger.copy(calls = ledger.calls + TelephonySend(at)))
        }
    }

    private fun commit(ledger: TelephonyLedger): RateVerdict =
        if (store.write(ledger)) RateVerdict.Allowed else RateVerdict.Limited(RATE_LEDGER_UNAVAILABLE)

    /** Fermato: si salva comunque il registro ripulito, così un invio «nel futuro» riportato ad adesso scade. */
    private fun limited(stored: TelephonyLedger, pruned: TelephonyLedger, code: String): RateVerdict {
        if (pruned != stored) store.write(pruned)
        return RateVerdict.Limited(code)
    }

    private inline fun guarded(block: () -> RateVerdict): RateVerdict = synchronized(LOCK) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            RateVerdict.Limited(RATE_LEDGER_UNAVAILABLE)
        }
    }

    /** Solo gli invii dell'ultima ora, con quelli «nel futuro» riportati ad adesso. */
    private fun pruned(ledger: TelephonyLedger, now: ClockReading): TelephonyLedger = TelephonyLedger(
        sms = ledger.sms.mapNotNull { recent(it, now) },
        calls = ledger.calls.mapNotNull { recent(it, now) },
    )

    private fun recent(send: TelephonySend, now: ClockReading): TelephonySend? {
        val then = send.at
        val sameBoot = then.bootCount != UNKNOWN_BOOT && then.bootCount == now.bootCount &&
            now.elapsedMillis >= then.elapsedMillis
        val age = if (sameBoot) now.elapsedMillis - then.elapsedMillis else now.wallMillis - then.wallMillis
        if (age >= WINDOW_MILLIS) return null
        return if (then.wallMillis > now.wallMillis) send.copy(at = then.copy(wallMillis = now.wallMillis)) else send
    }

    companion object {
        const val WINDOW_MILLIS = 60 * 60 * 1_000L
        const val SMS_RATE_LIMITED = "sms_rate_limited"
        const val SMS_DESTINATION_RATE_LIMITED = "sms_destination_rate_limited"
        const val CALL_RATE_LIMITED = "call_rate_limited"

        /** Il registro dei tetti non si legge o non si scrive: nel dubbio non si invia. */
        const val RATE_LEDGER_UNAVAILABLE = "telephony_rate_ledger_unavailable"

        /** Un solo lock per processo, anche se per errore ci fossero due limitatori sullo stesso registro. */
        private val LOCK = Any()
    }
}

/**
 * Registro su SharedPreferences, scritto con `commit()`: il conteggio è su disco prima dell'invio. Ogni voce
 * è `parete:avvio:dall'avvio[:impronta]`. Il numero non si salva mai: l'impronta è un hash salato per
 * installazione delle ultime 10 cifre (così `+39 333…` e `333…` sono lo stesso destinatario; due numeri
 * diversi con le stesse ultime 10 cifre contano insieme, cioè di più, mai di meno).
 */
class PrefsTelephonyLedgerStore(
    context: Context,
    private val clock: TelephonyClock = AndroidTelephonyClock(context),
) : TelephonyLedgerStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): TelephonyLedger = TelephonyLedger(
        sms = entries(KEY_SMS, withDestination = true),
        calls = entries(KEY_CALLS, withDestination = false),
    )

    private fun entries(key: String, withDestination: Boolean): List<TelephonySend> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map { entry ->
            parse(entry, withDestination) ?: TelephonySend(
                // Voce illeggibile: conta come un invio appena fatto, verso un destinatario a sé.
                clock.now().copy(bootCount = UNKNOWN_BOOT),
                if (withDestination) UNREADABLE_DESTINATION else null,
            )
        }
    }

    private fun parse(entry: String, withDestination: Boolean): TelephonySend? {
        val parts = entry.split(':')
        if (parts.size != if (withDestination) 4 else 3) return null
        val wall = parts[0].toLongOrNull() ?: return null
        val boot = parts[1].toIntOrNull() ?: return null
        val elapsed = parts[2].toLongOrNull() ?: return null
        val destination = if (withDestination) {
            parts[3].takeIf { HEX.matches(it) } ?: return null
        } else {
            null
        }
        return TelephonySend(ClockReading(wall, boot, elapsed), destination)
    }

    override fun write(ledger: TelephonyLedger): Boolean = prefs.edit()
        .putString(KEY_SMS, ledger.sms.joinToString(",") { format(it) + ":" + it.destination })
        .putString(KEY_CALLS, ledger.calls.joinToString(",") { format(it) })
        .commit()

    private fun format(send: TelephonySend): String =
        "${send.at.wallMillis}:${send.at.bootCount}:${send.at.elapsedMillis}"

    override fun destinationKey(number: String): String {
        val digits = number.filter { it in '0'..'9' }.takeLast(DESTINATION_DIGITS)
        val digest = MessageDigest.getInstance("SHA-256").digest((salt() + digits).toByteArray(Charsets.UTF_8))
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** Il sale dell'installazione; se non si riesce a salvarlo si lancia (e l'invio non parte). */
    private fun salt(): String = prefs.getString(KEY_SALT, null) ?: synchronized(SALT_LOCK) {
        prefs.getString(KEY_SALT, null) ?: ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
            .also { check(prefs.edit().putString(KEY_SALT, it).commit()) { "telephony salt not saved" } }
    }

    private companion object {
        const val PREFS_NAME = "argus_telephony_rate"
        const val KEY_SMS = "sms_v2"
        const val KEY_CALLS = "calls_v2"
        const val KEY_SALT = "salt"
        const val SALT_BYTES = 16
        const val KEY_BYTES = 12
        const val DESTINATION_DIGITS = 10
        const val UNREADABLE_DESTINATION = "0"
        val HEX = Regex("^[0-9a-f]{1,64}$")
        val SALT_LOCK = Any()
    }
}
