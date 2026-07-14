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
)

/** Registry process-local: nessun Parcelable viene persistito o promesso dopo process death. */
class ActiveNotificationReplyRegistry {
    private val handles = ConcurrentHashMap<String, NotificationReplyHandle>()

    internal fun replace(handle: NotificationReplyHandle) {
        handles[handle.notificationKey] = handle
    }

    fun remove(notificationKey: String) {
        handles.remove(notificationKey)
    }

    fun clear() {
        handles.clear()
    }

    internal fun takeMatching(request: NotificationReplyRequest): NotificationReplyHandle? {
        var selected: NotificationReplyHandle? = null
        handles.computeIfPresent(request.notificationKey) { _, current ->
            if (
                current.packageName == request.packageName &&
                current.conversationId == request.conversationId &&
                current.eventId == request.eventId &&
                current.isGroup == false
            ) {
                selected = current
                null
            } else {
                current
            }
        }
        return selected
    }

    internal fun size(): Int = handles.size
}

class NotificationReplyHandleFactory {
    internal fun from(
        notification: Notification,
        parsed: ParsedNotification,
    ): NotificationReplyHandle? {
        val event = parsed.envelope.event as? TriggerEvent.NotificationPosted ?: return null
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
        request.notificationKey.isNotBlank() &&
            request.notificationKey.length <= MAX_NOTIFICATION_KEY_CHARS &&
            request.notificationKey.none(Char::isISOControl) &&
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
