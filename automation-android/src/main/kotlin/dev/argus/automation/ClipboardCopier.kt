package dev.argus.automation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.SafeExtractionRegex
import java.util.UUID

/** Boundary dell'azione clipboard: l'executor deterministico resta testabile in JVM puro. */
interface ClipboardCopier {
    /** copy_to_clipboard: estrae il payload dal trigger (SMS/notifica) e lo scrive negli appunti. */
    fun copy(event: TriggerEvent, extractionRegex: String?): ActionResult

    /** copy_text: scrive negli appunti una stringa LETTERALE già risolta (nessun trigger richiesto). */
    fun copyLiteral(text: String): ActionResult
}

/**
 * Esecuzione reale di Action.CopyToClipboard (P2-3, OTP): estrae il payload testuale dal
 * trigger (SMS o notifica), applica la regex deterministica del draft (primo capture group,
 * o match intero senza gruppi) e scrive negli appunti con EXTRA_IS_SENSITIVE — il codice non
 * appare nelle anteprime di sistema. Il contenuto viene cancellato dopo un TTL solo se Argus
 * possiede ancora la clipboard; una copia successiva dell'utente non viene mai rimossa.
 * Fallimenti onesti, clipboard intatta: `otp_not_found` senza match,
 * `clipboard_source_missing` senza testo.
 * La scrittura senza focus è verificata sul device reale (spike P2-0).
 *
 * **Pulizia che sopravvive al background (S7).** Da background o a telefono bloccato Android nega
 * la lettura della clip (la description è `null`): il timer in-process allora non può verificare
 * il possesso e lascerebbe il codice negli appunti. Per questo alla scrittura si persiste una
 * «pulizia in sospeso» (label univoca + istante di scadenza a orologio di parete, mai il
 * contenuto): se il timer riesce la rimuove; altrimenti al prossimo ON_START del processo si
 * esegue il compare-and-clear (svuota solo se la clip ha ancora la label di Argus). Il timer
 * `Handler` resta, ma la scadenza vera è quella persistita.
 */
class AndroidClipboardCopier(
    context: Context,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val expiryScheduler: ClipboardExpiryScheduler = MainClipboardExpiryScheduler,
    private val clipboard: ClipboardPort = SystemClipboardPort(context),
    private val cleanupStore: PendingClipboardCleanupStore = PrefsPendingClipboardCleanupStore(context),
    processStarts: ProcessStartSource = ProcessLifecycleStartSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClipboardCopier {
    init {
        require(expiryMillis >= 0L) { "expiryMillis must be non-negative" }
        processStarts.register(::onProcessStarted)
    }

    override fun copy(event: TriggerEvent, extractionRegex: String?): ActionResult {
        val payload = when (event) {
            is TriggerEvent.PhoneStateChanged ->
                event.smsText.takeIf { event.event == PhoneEvent.SMS_RECEIVED }
            is TriggerEvent.NotificationPosted -> event.text
            else -> null
        }?.takeIf(String::isNotBlank)
            ?: return ActionResult.Failure("clipboard_source_missing")

        val value = if (extractionRegex == null) {
            payload
        } else {
            when (val result = SafeExtractionRegex.extract(extractionRegex, payload)) {
                SafeExtractionRegex.Result.InvalidPattern ->
                    return ActionResult.Failure("extraction_regex_invalid")
                SafeExtractionRegex.Result.NoMatch ->
                    return ActionResult.Failure("otp_not_found")
                is SafeExtractionRegex.Result.Match -> result.value
            }
        }

        return writeClip(value)
    }

    override fun copyLiteral(text: String): ActionResult {
        // La stringa arriva già risolta (letterale approvato o ${'$'}{var} interpolato dall'engine):
        // nessuna estrazione, nessuna dipendenza dal trigger. Vuota = niente da copiare.
        val value = text.takeIf(String::isNotBlank)
            ?: return ActionResult.Failure("clipboard_source_missing")
        return writeClip(value)
    }

    /** Scrittura condivisa: stesso ClipData.newPlainText + EXTRA_IS_SENSITIVE dei due percorsi. */
    private fun writeClip(value: String): ActionResult {
        val ownerLabel = "argus:${UUID.randomUUID()}"
        val clip = ClipData.newPlainText(ownerLabel, value).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching {
            clipboard.write(clip)
            // Persistita subito: copre anche la morte del processo prima che il timer scatti.
            runCatching {
                cleanupStore.save(PendingClipboardCleanup(ownerLabel, clock() + expiryMillis))
            }
            expiryScheduler.schedule(expiryMillis) { onExpiry(ownerLabel) }
            ActionResult.Success
        }.getOrElse { ActionResult.Failure("clipboard_write_failed") }
    }

    /** Scadenza del timer in-process. */
    private fun onExpiry(ownerLabel: String) {
        // Se la clip non è leggibile (background/schermo bloccato) la pulizia resta in sospeso e
        // la riprende il prossimo ON_START; non si cancella mai un contenuto non verificato.
        if (compareAndClear(ownerLabel) != CleanupOutcome.UNREADABLE) {
            runCatching { cleanupStore.clear(ownerLabel) }
        }
    }

    /** ON_START del processo: porta a termine una pulizia rimasta in sospeso. */
    fun onProcessStarted() {
        val pending = runCatching { cleanupStore.load() }.getOrNull() ?: return
        val now = clock()
        when {
            now - pending.expiresAtMillis > MAX_PENDING_AGE_MILLIS ->
                runCatching { cleanupStore.clear(pending.label) }
            // TTL non ancora scaduto: il timer in-process può essere andato perso con il processo.
            now < pending.expiresAtMillis ->
                expiryScheduler.schedule(pending.expiresAtMillis - now) { cleanupAfterStart(pending.label, 0) }
            else -> cleanupAfterStart(pending.label, 0)
        }
    }

    private fun cleanupAfterStart(ownerLabel: String, attempt: Int) {
        if (compareAndClear(ownerLabel) != CleanupOutcome.UNREADABLE) {
            runCatching { cleanupStore.clear(ownerLabel) }
            return
        }
        // Al ON_START la finestra può non avere ancora il focus: Android nega la lettura finché non
        // lo ottiene. Pochi tentativi ravvicinati; poi la pulizia resta in sospeso per il prossimo.
        if (attempt < START_RETRY_DELAYS_MILLIS.size) {
            expiryScheduler.schedule(START_RETRY_DELAYS_MILLIS[attempt]) {
                cleanupAfterStart(ownerLabel, attempt + 1)
            }
        }
    }

    private fun compareAndClear(ownerLabel: String): CleanupOutcome = try {
        when (val current = clipboard.currentLabel()) {
            null -> CleanupOutcome.UNREADABLE
            ownerLabel -> {
                clipboard.clear()
                CleanupOutcome.CLEARED
            }
            // Etichetta di un'altra app o dell'utente: non toccare.
            else -> CleanupOutcome.NOT_OURS
        }
    } catch (_: Exception) {
        CleanupOutcome.UNREADABLE
    }

    private enum class CleanupOutcome { CLEARED, NOT_OURS, UNREADABLE }

    companion object {
        const val DEFAULT_EXPIRY_MILLIS = 60_000L
        private const val MAX_PENDING_AGE_MILLIS = 24L * 60 * 60 * 1000
        private val START_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_500L, 4_000L)
    }
}

/** Accesso alla clipboard di sistema, astratto per testare la pulizia senza i vincoli di focus. */
interface ClipboardPort {
    fun write(clip: ClipData)

    /** Label della clip corrente, oppure `null` se non leggibile (background, bloccato) o vuota. */
    fun currentLabel(): String?

    fun clear()
}

class SystemClipboardPort(context: Context) : ClipboardPort {
    private val manager =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun write(clip: ClipData) = manager.setPrimaryClip(clip)

    override fun currentLabel(): String? = manager.primaryClipDescription?.label?.toString()

    override fun clear() = manager.clearPrimaryClip()
}

/** Pulizia in sospeso: solo label univoca e scadenza (orologio di parete), mai il contenuto. */
data class PendingClipboardCleanup(val label: String, val expiresAtMillis: Long)

interface PendingClipboardCleanupStore {
    fun save(pending: PendingClipboardCleanup)
    fun load(): PendingClipboardCleanup?

    /** Rimuove la pulizia solo se è ancora quella di [label] (una scrittura più recente vince). */
    fun clear(label: String)
}

/** `commit()` sincrono: il processo può morire subito dopo la scrittura negli appunti. */
class PrefsPendingClipboardCleanupStore(context: Context) : PendingClipboardCleanupStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(pending: PendingClipboardCleanup) {
        check(
            prefs.edit()
                .putString(KEY_LABEL, pending.label)
                .putLong(KEY_EXPIRES_AT, pending.expiresAtMillis)
                .commit(),
        ) { "clipboard_cleanup_persist_failed" }
    }

    override fun load(): PendingClipboardCleanup? {
        val label = prefs.getString(KEY_LABEL, null) ?: return null
        if (!prefs.contains(KEY_EXPIRES_AT)) return null
        return PendingClipboardCleanup(label, prefs.getLong(KEY_EXPIRES_AT, 0L))
    }

    override fun clear(label: String) {
        if (prefs.getString(KEY_LABEL, null) != label) return
        prefs.edit().remove(KEY_LABEL).remove(KEY_EXPIRES_AT).commit()
    }

    private companion object {
        const val PREFS_NAME = "argus_clipboard_cleanup"
        const val KEY_LABEL = "label"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}

/** Notifica l'ON_START del processo (il primo Activity visibile), astratta per i test. */
fun interface ProcessStartSource {
    fun register(onStart: () -> Unit)
}

object ProcessLifecycleStartSource : ProcessStartSource {
    override fun register(onStart: () -> Unit) {
        // `addObserver` va chiamato dal main thread: il copier è costruito dal grafo DI.
        Handler(Looper.getMainLooper()).post {
            runCatching {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            onStart.invoke()
                        }
                    },
                )
            }
        }
    }
}

fun interface ClipboardExpiryScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit)
}

private object MainClipboardExpiryScheduler : ClipboardExpiryScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMillis: Long, block: () -> Unit) {
        handler.postDelayed(block, delayMillis)
    }
}
