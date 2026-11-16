package dev.argus.automation.notification

import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import dev.argus.engine.model.Action
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.NotificationActionPolicy
import dev.argus.engine.runtime.ActionResult
import java.lang.ref.WeakReference

/** `notification_action` sulla notifica che ha fatto scattare la regola (0.8). */
fun interface NotificationActionGateway {
    fun perform(packageName: String, notificationKey: String, action: Action.NotificationAction): ActionResult
}

/** Un pulsante della notifica viva: testo visibile, se chiede di scrivere, e come premerlo. */
class LiveNotificationButton(
    val title: String?,
    val needsInput: Boolean,
    val press: () -> Unit,
)

class LiveNotification(val packageName: String, val buttons: List<LiveNotificationButton>)

/** Le notifiche attive, viste dal listener collegato. */
interface LiveNotifications {
    /** Il listener è collegato: senza, nessuna notifica è raggiungibile. */
    val connected: Boolean

    /** La notifica attiva con questa key, o null se non c'è più. */
    fun find(notificationKey: String): LiveNotification?
    fun dismiss(notificationKey: String)
    fun snooze(notificationKey: String, durationMillis: Long)
}

/**
 * Esegue l'azione sulla notifica viva, ripetendo allo scatto i controlli del validator: stessa app del
 * trigger, mai Argus, CLICK mai sulle app vietate alle macro, un solo pulsante col testo approvato e che
 * non chieda di scrivere. In ogni altro caso un codice chiuso e nient'altro premuto.
 */
class ListenerNotificationActionGateway(
    private val notifications: LiveNotifications,
) : NotificationActionGateway {
    override fun perform(
        packageName: String,
        notificationKey: String,
        action: Action.NotificationAction,
    ): ActionResult {
        if (!NotificationActionPolicy.isShapeValid(action)) return ActionResult.Failure("action_invalid")
        if (notificationKey.isBlank()) return failure(NotificationActionPolicy.CODE_EVENT_UNVERIFIED)
        if (isArgus(packageName) ||
            (action.op == NotificationActionOp.CLICK && NotificationActionPolicy.isForbiddenTarget(packageName))
        ) return failure(NotificationActionPolicy.CODE_TARGET_FORBIDDEN)
        if (!notifications.connected) return failure(NotificationActionPolicy.CODE_UNAVAILABLE)
        return try {
            val live = notifications.find(notificationKey)
                ?: return failure(NotificationActionPolicy.CODE_NOTIFICATION_GONE)
            if (live.packageName != packageName) return failure(NotificationActionPolicy.CODE_EVENT_UNVERIFIED)
            when (action.op) {
                NotificationActionOp.DISMISS -> notifications.dismiss(notificationKey)
                NotificationActionOp.SNOOZE -> notifications.snooze(
                    notificationKey,
                    requireNotNull(action.snoozeMinutes) * MILLIS_PER_MINUTE,
                )
                NotificationActionOp.CLICK -> {
                    val label = requireNotNull(action.label)
                    val matching = live.buttons.filter { NotificationActionPolicy.labelMatches(it.title, label) }
                    val button = when (matching.size) {
                        0 -> return failure(NotificationActionPolicy.CODE_BUTTON_NOT_FOUND)
                        1 -> matching.single()
                        else -> return failure(NotificationActionPolicy.CODE_BUTTON_AMBIGUOUS)
                    }
                    if (button.needsInput) return failure(NotificationActionPolicy.CODE_BUTTON_NEEDS_INPUT)
                    button.press()
                }
            }
            ActionResult.Success
        } catch (_: PendingIntent.CanceledException) {
            failure(NotificationActionPolicy.CODE_NOTIFICATION_GONE)
        } catch (_: SecurityException) {
            failure(NotificationActionPolicy.CODE_UNAVAILABLE)
        } catch (_: RuntimeException) {
            failure(NotificationActionPolicy.CODE_FAILED)
        }
    }

    private fun isArgus(pkg: String) = pkg == ARGUS_PACKAGE || pkg.startsWith("$ARGUS_PACKAGE.")

    private fun failure(code: String) = ActionResult.Failure(code)

    private companion object {
        const val ARGUS_PACKAGE = "dev.argus"
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Il listener delle notifiche collegato ORA in questo processo: lo registra il servizio quando si
 * collega e lo toglie quando si scollega. Riferimento debole: non tiene in vita il servizio.
 */
object ConnectedNotificationListener : LiveNotifications {
    @Volatile private var service: WeakReference<NotificationListenerService>? = null

    fun attach(listener: NotificationListenerService) {
        service = WeakReference(listener)
    }

    fun detach(listener: NotificationListenerService) {
        if (service?.get() === listener) service = null
    }

    private fun current(): NotificationListenerService? = service?.get()

    override val connected: Boolean get() = current() != null

    override fun find(notificationKey: String): LiveNotification? {
        val listener = current() ?: return null
        val posted = listener.getActiveNotifications(arrayOf(notificationKey))
            ?.firstOrNull { it?.key == notificationKey }
            ?: return null
        val buttons = posted.notification.actions.orEmpty().filterNotNull().map { button ->
            LiveNotificationButton(
                title = button.title?.toString(),
                // Qualsiasi RemoteInput, anche a sole scelte: per scrivere ci sono le risposte.
                needsInput = !button.remoteInputs.isNullOrEmpty() || !button.dataOnlyRemoteInputs.isNullOrEmpty(),
                press = { requireNotNull(button.actionIntent) { "pulsante senza intent" }.send() },
            )
        }
        return LiveNotification(posted.packageName, buttons)
    }

    override fun dismiss(notificationKey: String) {
        (current() ?: throw IllegalStateException("listener non collegato")).cancelNotification(notificationKey)
    }

    override fun snooze(notificationKey: String, durationMillis: Long) {
        (current() ?: throw IllegalStateException("listener non collegato"))
            .snoozeNotification(notificationKey, durationMillis)
    }
}
