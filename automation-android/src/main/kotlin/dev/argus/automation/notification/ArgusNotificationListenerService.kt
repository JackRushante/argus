package dev.argus.automation.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.automation.NotificationEventDispatcher
import dev.argus.automation.di.ApplicationScope
import dev.argus.engine.notification.NotificationEventParser
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.notification.ParsedNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Prepara l'evento in modo sincrono, così il reply handle esiste prima della lane asincrona. */
class NotificationIngress(
    private val snapshotFactory: AndroidNotificationSnapshotFactory,
    private val parser: NotificationEventParser,
    private val handleFactory: NotificationReplyHandleFactory,
    private val registry: ActiveNotificationReplyRegistry,
    private val observedConversations: ObservedConversationStore,
    private val dispatcher: NotificationEventDispatcher,
) {
    fun registerPosted(notification: StatusBarNotification): ParsedNotification? {
        val parsed = parse(notification)
        if (parsed == null) {
            registry.remove(notification.key)
            return null
        }
        val handle = handleFactory.from(notification.notification, parsed)
        if (handle == null) {
            registry.remove(notification.key)
        } else {
            registry.replace(handle)
        }
        return parsed
    }

    /** Ricostruisce soltanto i canali attivi: nessun evento preesistente viene ridispatchato. */
    fun rehydrate(activeNotifications: Iterable<StatusBarNotification>) {
        registry.clear()
        activeNotifications.forEach { notification ->
            val parsed = parse(notification) ?: return@forEach
            handleFactory.from(notification.notification, parsed)?.let(registry::replace)
        }
    }

    fun remove(notificationKey: String) {
        registry.remove(notificationKey)
    }

    fun clear() {
        registry.clear()
    }

    suspend fun persistAndDispatch(parsed: ParsedNotification) {
        parsed.observedConversation?.let { conversation ->
            try {
                observedConversations.record(conversation)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Il picker è best-effort: un errore locale non deve perdere il trigger.
            }
        }
        dispatcher.dispatch(parsed.envelope)
    }

    private fun parse(notification: StatusBarNotification): ParsedNotification? = try {
        parser.parse(snapshotFactory.from(notification))
    } catch (_: RuntimeException) {
        null
    }
}

@AndroidEntryPoint
class ArgusNotificationListenerService : NotificationListenerService() {
    @Inject lateinit var ingress: NotificationIngress
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onListenerConnected() {
        super.onListenerConnected()
        val active = try {
            activeNotifications?.toList() ?: emptyList<StatusBarNotification>()
        } catch (_: RuntimeException) {
            emptyList()
        }
        ingress.rehydrate(active)
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val parsed = ingress.registerPosted(notification) ?: return
        applicationScope.launch {
            try {
                ingress.persistAndDispatch(parsed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Supervisor scope: il listener resta vivo; Engine/audit sono fail-closed.
            }
        }
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        ingress.remove(notification.key)
    }

    override fun onListenerDisconnected() {
        ingress.clear()
        super.onListenerDisconnected()
    }
}
