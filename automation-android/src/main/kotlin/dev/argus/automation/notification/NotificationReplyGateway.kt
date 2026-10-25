package dev.argus.automation.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.argus.engine.notification.ParsedNotification
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Richiesta effimera: il testo non entra mai in un data-class/log strutturato. */
class NotificationReplyRequest(
    val packageName: String,
    val notificationKey: String,
    val conversationId: String,
    val eventId: TriggerEventId,
    val text: String,
)

sealed interface NotificationReplyDelivery {
    data object Sent : NotificationReplyDelivery
    data class Failed(val code: String) : NotificationReplyDelivery
}

fun interface NotificationReplyGateway {
    fun send(request: NotificationReplyRequest): NotificationReplyDelivery
}

internal class NotificationReplyHandle(
    val packageName: String,
    val notificationKey: String,
    val conversationId: String,
    val eventId: TriggerEventId,
    val isGroup: Boolean?,
    val remoteInput: RemoteInput,
    val pendingIntent: PendingIntent,
    /** Testo del messaggio che ha portato questo handle: serve solo ad accorpare le raffiche (D8). */
    val messageText: String? = null,
)

/**
 * Registry process-local: nessun Parcelable viene persistito o promesso dopo process death.
 *
 * Tiene anche, per ogni conversazione, i testi degli ultimi messaggi della raffica corrente (solo
 * in RAM, limitati, azzerati quando la reply consuma l'handle o la notifica sparisce): è la
 * [ReplyBurstSource] con cui la lane accorpa più messaggi ravvicinati in una sola risposta (D8).
 */
class ActiveNotificationReplyRegistry : ReplyBurstSource {
    private val handles = ConcurrentHashMap<String, NotificationReplyHandle>()
    private val bursts = HashMap<String, ArrayDeque<BurstEntry>>()
    private val burstLock = Any()
    private val changes = MutableStateFlow(0L)

    private class BurstEntry(val conversationId: String, val message: BurstMessage)

    internal fun replace(handle: NotificationReplyHandle) {
        val previous = handles.put(handle.notificationKey, handle)
        synchronized(burstLock) {
            val log = bursts.getOrPut(handle.notificationKey) { ArrayDeque() }
            if (previous != null && previous.conversationId != handle.conversationId) log.clear()
            val text = handle.messageText
            if (text != null && log.lastOrNull()?.message?.eventId != handle.eventId) {
                log.addLast(BurstEntry(handle.conversationId, BurstMessage(handle.eventId, text)))
                while (log.size > MAX_BURST_MESSAGES) log.removeFirst()
            }
        }
        changes.update { it + 1 }
    }

    fun remove(notificationKey: String) {
        handles.remove(notificationKey)
        synchronized(burstLock) { bursts.remove(notificationKey) }
        changes.update { it + 1 }
    }

    fun clear() {
        handles.clear()
        synchronized(burstLock) { bursts.clear() }
        changes.update { it + 1 }
    }

    /**
     * Seleziona l'handle PIÙ RECENTE della stessa conversazione 1:1 (stesso package, stessa
     * notification key, stesso conversationId): se nel frattempo è arrivato un altro messaggio la
     * reply parte comunque sul canale vivo, non sull'event id esatto che ha avviato la generazione.
     * L'handle si consuma una volta sola.
     */
    internal fun takeMatching(request: NotificationReplyRequest): NotificationReplyHandle? {
        var selected: NotificationReplyHandle? = null
        handles.computeIfPresent(request.notificationKey) { _, current ->
            if (
                current.packageName == request.packageName &&
                current.conversationId == request.conversationId &&
                current.isGroup == false
            ) {
                selected = current
                null
            } else {
                current
            }
        }
        if (selected != null) {
            // La risposta chiude la raffica: i messaggi successivi ne aprono una nuova.
            synchronized(burstLock) { bursts.remove(request.notificationKey) }
            changes.update { it + 1 }
        }
        return selected
    }

    internal fun size(): Int = handles.size

    override fun burst(
        notificationKey: String,
        conversationId: String,
        firstEventId: TriggerEventId,
    ): ReplyBurst? = synchronized(burstLock) {
        val relevant = bursts[notificationKey].orEmpty().filter { it.conversationId == conversationId }
        if (relevant.isEmpty()) {
            null
        } else {
            val start = relevant.indexOfFirst { it.message.eventId == firstEventId }
                .takeIf { it >= 0 } ?: (relevant.size - 1)
            ReplyBurst(relevant.subList(start, relevant.size).map { it.message })
        }
    }

    override suspend fun awaitNewMessage(
        notificationKey: String,
        conversationId: String,
        seenEventId: TriggerEventId,
    ): TriggerEventId = changes
        .map { handles[notificationKey]?.takeIf { it.conversationId == conversationId }?.eventId }
        .first { it != null && it != seenEventId }!!

    private companion object {
        const val MAX_BURST_MESSAGES = 8
    }
}

/** Un singolo messaggio nel log della raffica non supera questo limite (il prompt ha un tetto suo). */
private const val MAX_BURST_MESSAGE_CHARS = 1_000

class NotificationReplyHandleFactory {
    internal fun from(
        notification: Notification,
        parsed: ParsedNotification,
    ): NotificationReplyHandle? {
        val event = parsed.envelope.event as? TriggerEvent.NotificationPosted ?: return null
        // I trigger Notification generici non hanno bisogno di un canale di reply: conservare
        // handle RemoteInput solo per i package che il gateway potrà davvero usare.
        if (event.pkg !in DraftValidator.WHATSAPP_PACKAGES) return null
        val key = event.notificationKey?.takeIf(String::isNotBlank) ?: return null
        val conversationId = event.conversationId?.takeIf(String::isNotBlank) ?: return null
        val candidate = notification.actions.orEmpty().asSequence().filterNotNull().mapNotNull { action ->
            val remoteInput = action.remoteInputs.orEmpty().asSequence().filterNotNull().firstOrNull {
                it.allowFreeFormInput && it.resultKey?.isNotBlank() == true
            } ?: return@mapNotNull null
            val pendingIntent = action.actionIntent ?: return@mapNotNull null
            remoteInput to pendingIntent
        }.firstOrNull() ?: return null
        return NotificationReplyHandle(
            packageName = event.pkg,
            notificationKey = key,
            conversationId = conversationId,
            eventId = parsed.envelope.id,
            isGroup = event.isGroup,
            remoteInput = candidate.first,
            pendingIntent = candidate.second,
            messageText = event.text?.takeIf(String::isNotBlank)?.take(MAX_BURST_MESSAGE_CHARS),
        )
    }
}

class AndroidNotificationReplyGateway(
    context: Context,
    private val registry: ActiveNotificationReplyRegistry,
) : NotificationReplyGateway {
    private val appContext = context.applicationContext

    override fun send(request: NotificationReplyRequest): NotificationReplyDelivery {
        if (request.packageName !in DraftValidator.WHATSAPP_PACKAGES) {
            return NotificationReplyDelivery.Failed("reply_package_untrusted")
        }
        if (!validMetadata(request) || !validText(request.text)) {
            return NotificationReplyDelivery.Failed("reply_request_invalid")
        }
        val handle = registry.takeMatching(request)
            ?: return NotificationReplyDelivery.Failed("reply_channel_unavailable")
        val fillInIntent = Intent()
        return try {
            val results = Bundle().apply {
                putCharSequence(handle.remoteInput.resultKey, request.text)
            }
            RemoteInput.addResultsToIntent(arrayOf(handle.remoteInput), fillInIntent, results)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            handle.pendingIntent.send(appContext, 0, fillInIntent)
            NotificationReplyDelivery.Sent
        } catch (_: PendingIntent.CanceledException) {
            NotificationReplyDelivery.Failed("channel_expired")
        } catch (_: SecurityException) {
            NotificationReplyDelivery.Failed("reply_send_rejected")
        } catch (_: RuntimeException) {
            NotificationReplyDelivery.Failed("reply_send_failed")
        }
    }

    private fun validMetadata(request: NotificationReplyRequest): Boolean =
        // La notification key è opaca e può contenere control chars (tag WhatsApp = Base64 con
        // newline finale); il conversationId invece è un nostro hash namespaced e resta severo.
        request.notificationKey.isNotBlank() &&
            request.notificationKey.length <= MAX_NOTIFICATION_KEY_CHARS &&
            request.conversationId.isNotBlank() &&
            request.conversationId.length <= MAX_CONVERSATION_ID_CHARS &&
            request.conversationId.none(Char::isISOControl)

    private fun validText(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_REPLY_CHARS &&
            text.all { !it.isISOControl() || it == '\n' || it == '\t' }

    private companion object {
        const val MAX_NOTIFICATION_KEY_CHARS = 1_024
        const val MAX_CONVERSATION_ID_CHARS = 512
        const val MAX_REPLY_CHARS = 4_096
    }
}
