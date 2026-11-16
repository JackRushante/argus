package dev.argus.automation.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.core.content.IntentCompat
import dev.argus.automation.triggers.DeviceEventDispatcher
import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.NfcTagPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Da dove viene una scansione (0.8). L'activity che riceve i tag è esportata per forza (i filtri NFC del
 * sistema), quindi qualunque app può avviarla con un id inventato: una scansione è **verificata** solo se il
 * chiamante iniziale dell'activity è il servizio NFC del sistema. Android lo dice solo dalla 15 (API 35,
 * `Activity.getInitialCaller()`); prima una scansione non si può verificare e resta non verificata.
 */
object NfcScanVerification {
    /** Uid (app id) del servizio NFC di sistema: `android.uid.nfc`, `Process.NFC_UID`. */
    const val NFC_SERVICE_APP_ID = 1027

    /** Il primo livello di Android che dice chi ha avviato l'activity. */
    const val FIRST_VERIFIABLE_API = 35

    private const val PER_USER_RANGE = 100_000

    /** [initialCallerUid] `null` o non valido (chiamante che non condivide l'identità) = non verificata. */
    fun verified(apiLevel: Int, initialCallerUid: Int?): Boolean =
        apiLevel >= FIRST_VERIFIABLE_API &&
            initialCallerUid != null && initialCallerUid >= 0 &&
            initialCallerUid % PER_USER_RANGE == NFC_SERVICE_APP_ID
}

/** L'id del tag in un intent di scoperta NFC, nella forma canonica; `null` se manca o è malformato. */
object NfcTagIntents {
    fun tagId(intent: Intent?): String? {
        intent ?: return null
        val bytes = runCatching {
            IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)?.id
        }.getOrNull() ?: runCatching { intent.getByteArrayExtra(NfcAdapter.EXTRA_ID) }.getOrNull()
        return bytes?.let(NfcTagPolicy::format)
    }

    val DISCOVERY_ACTIONS: Set<String> = setOf(
        NfcAdapter.ACTION_NDEF_DISCOVERED,
        NfcAdapter.ACTION_TECH_DISCOVERED,
        NfcAdapter.ACTION_TAG_DISCOVERED,
    )
}

/**
 * Consegna una scansione al motore, senza decidere nulla: il matcher confronta il tag con le regole armate,
 * `StaticShellSafety` e la FirePolicy decidono shell e macro in base a [verified]. L'id del tag non entra
 * mai nei log né in chiaro nell'id dell'evento.
 */
class NfcScanIngress(
    private val parser: DeviceEventParser,
    private val dispatcher: DeviceEventDispatcher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** `false` se l'id non è un id di tag (nessun evento). */
    suspend fun onScan(tagId: String, verified: Boolean): Boolean {
        val canonical = NfcTagPolicy.normalize(tagId) ?: return false
        val envelope = parser.nfcTag(canonical, verified, nowMillis()) ?: return false
        dispatcher.dispatch(envelope)
        return true
    }
}

/** Esito della lettura di un tag all'attivazione di una regola. */
sealed interface NfcTagReadResult {
    data class Read(val tagId: String) : NfcTagReadResult
    data object NoNfc : NfcTagReadResult
    data object NfcOff : NfcTagReadResult

    /** Argus non è in primo piano: la lettura si fa solo con la schermata aperta. */
    data object NotInForeground : NfcTagReadResult
    data object Cancelled : NfcTagReadResult
    data object TimedOut : NfcTagReadResult
}

/** Legge un tag in primo piano (modalità lettore) quando si arma una regola `nfc_tag` con `resolveTag`. */
fun interface NfcTagReader {
    suspend fun read(): NfcTagReadResult
}

/** Senza lettore collegato (test, caller non Android): nessun tag, la regola non si arma. */
object NoNfcTagReader : NfcTagReader {
    override suspend fun read(): NfcTagReadResult = NfcTagReadResult.NoNfc
}

/**
 * Lettore in primo piano: l'activity principale si collega in `onResume` e si scollega in `onPause`; durante
 * la lettura [prompting] è vero e l'interfaccia mostra «Avvicina il tag» con Annulla. La modalità lettore
 * tiene il tag lontano dal dispatch del sistema, quindi la lettura non fa scattare nessuna regola.
 * Una lettura per volta; scade dopo [timeoutMillis]. Lasciare la schermata la annulla.
 */
class AndroidNfcTagReader(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : NfcTagReader {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var activity: WeakReference<Activity>? = null
    private var pending: CompletableDeferred<NfcTagReadResult>? = null
    private val prompt = MutableStateFlow(false)

    /** `true` mentre si aspetta il tag: l'interfaccia mostra la richiesta. */
    val prompting: StateFlow<Boolean> = prompt.asStateFlow()

    fun attach(activity: Activity) {
        synchronized(lock) { this.activity = WeakReference(activity) }
    }

    fun detach(activity: Activity) {
        synchronized(lock) {
            if (this.activity?.get() !== activity) return
            this.activity = null
            pending?.complete(NfcTagReadResult.Cancelled)
        }
    }

    /** Il tasto Annulla della richiesta. */
    fun cancel() {
        synchronized(lock) { pending?.complete(NfcTagReadResult.Cancelled) }
    }

    override suspend fun read(): NfcTagReadResult = withContext(Dispatchers.Main) {
        val adapter = NfcAdapter.getDefaultAdapter(appContext) ?: return@withContext NfcTagReadResult.NoNfc
        if (!adapter.isEnabled) return@withContext NfcTagReadResult.NfcOff
        val result = CompletableDeferred<NfcTagReadResult>()
        val host = synchronized(lock) {
            val current = activity?.get()?.takeUnless { it.isFinishing }
            if (current == null || pending != null) return@synchronized null
            pending = result
            current
        } ?: return@withContext NfcTagReadResult.NotInForeground
        prompt.value = true
        try {
            adapter.enableReaderMode(
                host,
                { tag -> NfcTagPolicy.format(tag.id)?.let { result.complete(NfcTagReadResult.Read(it)) } },
                READER_FLAGS,
                null,
            )
            withTimeoutOrNull(timeoutMillis) { result.await() } ?: NfcTagReadResult.TimedOut
        } catch (_: IllegalStateException) {
            // L'activity non è più in primo piano.
            NfcTagReadResult.NotInForeground
        } finally {
            withContext(NonCancellable) {
                runCatching { adapter.disableReaderMode(host) }
                synchronized(lock) { if (pending === result) pending = null }
                prompt.value = false
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}
