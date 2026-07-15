package dev.argus.automation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.SafeExtractionRegex

/** Boundary dell'azione clipboard: l'executor deterministico resta testabile in JVM puro. */
fun interface ClipboardCopier {
    fun copy(event: TriggerEvent, extractionRegex: String?): ActionResult
}

/**
 * Esecuzione reale di Action.CopyToClipboard (P2-3, OTP): estrae il payload testuale dal
 * trigger (SMS o notifica), applica la regex deterministica del draft (primo capture group,
 * o match intero senza gruppi) e scrive negli appunti con EXTRA_IS_SENSITIVE — il codice non
 * appare nelle anteprime di sistema e non lascia MAI il telefono. Fallimenti onesti, clipboard
 * intatta: `otp_not_found` senza match, `clipboard_source_missing` senza testo.
 * La scrittura senza focus è verificata sul device reale (spike P2-0).
 */
class AndroidClipboardCopier(context: Context) : ClipboardCopier {
    private val appContext = context.applicationContext

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

        val clip = ClipData.newPlainText("argus", value).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching {
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(clip)
            ActionResult.Success
        }.getOrElse { ActionResult.Failure("clipboard_write_failed") }
    }
}
