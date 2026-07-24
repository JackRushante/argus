package dev.argus.automation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
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
 */
class AndroidClipboardCopier(
    context: Context,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val expiryScheduler: ClipboardExpiryScheduler = MainClipboardExpiryScheduler,
) : ClipboardCopier {
    private val appContext = context.applicationContext

    init {
        require(expiryMillis >= 0L) { "expiryMillis must be non-negative" }
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
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(clip)
            expiryScheduler.schedule(expiryMillis) {
                clearIfStillOwned(manager, ownerLabel)
            }
            ActionResult.Success
        }.getOrElse { ActionResult.Failure("clipboard_write_failed") }
    }

    private fun clearIfStillOwned(manager: ClipboardManager, ownerLabel: String) {
        // Android può negare la lettura in background su alcuni OEM: in quel caso è più sicuro
        // lasciare scadere naturalmente la clipboard che cancellare un contenuto non verificato.
        runCatching {
            val currentLabel = manager.primaryClipDescription?.label?.toString()
            if (currentLabel == ownerLabel) manager.clearPrimaryClip()
        }
    }

    companion object {
        const val DEFAULT_EXPIRY_MILLIS = 60_000L
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
