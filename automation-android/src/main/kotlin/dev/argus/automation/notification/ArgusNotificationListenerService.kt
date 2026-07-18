package dev.argus.automation.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.automation.NotificationEventDispatcher
import dev.argus.automation.foreground.ReceiverWorkLauncher
import dev.argus.engine.notification.NotificationEventParser
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.notification.ParsedNotification
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Prepara l'evento in modo sincrono, così il reply handle esiste prima della lane asincrona. */
class NotificationIngress(
    private val snapshotFactory: AndroidNotificationSnapshotFactory,
    private val parser: NotificationEventParser,
    private val handleFactory: NotificationReplyHandleFactory,
    private val registry: ActiveNotificationReplyRegistry,
    private val observedConversations: ObservedConversationStore,
    private val dispatcher: NotificationEventDispatcher,
    /** Gate sincrono sul consenso: letto da StateFlow.value, mai con I/O sul callback thread. */
    private val privacyAccepted: () -> Boolean,
) {
    fun registerPosted(notification: StatusBarNotification): ParsedNotification? {
        // Diagnostica privacy-safe: solo package ed esiti booleani, mai testo/identità.
        if (!privacyAccepted()) {
            Log.d(TAG, "posted scartata (privacy off): pkg=${notification.packageName}")
            registry.remove(notification.key)
            return null
        }
        val parsed = parse(notification)
        if (parsed == null) {
            Log.d(TAG, "posted non parsabile: pkg=${notification.packageName}")
            registry.remove(notification.key)
            return null
        }
        val handle = handleFactory.from(notification.notification, parsed)
        if (handle == null) {
            registry.remove(notification.key)
        } else {
            registry.replace(handle)
        }
        Log.d(
            TAG,
            "posted accettata: pkg=${notification.packageName} " +
                "conv=${parsed.observedConversation != null} handle=${handle != null}",
        )
        return parsed
    }

    /** Ricostruisce soltanto i canali attivi: nessun evento preesistente viene ridispatchato. */
    fun rehydrate(activeNotifications: Iterable<StatusBarNotification>) {
        registry.clear()
        if (!privacyAccepted()) return
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
        // Ricontrollo del gate: la revoca può avvenire tra il callback main thread e questa coroutine.
        if (!privacyAccepted()) return
        parsed.observedConversation
            ?.takeIf { it.packageName in DraftValidator.WHATSAPP_PACKAGES }
            ?.let { conversation ->
                try {
                    observedConversations.record(conversation)
                    Log.d(TAG, "conversazione osservata registrata: pkg=${conversation.packageName}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Il picker è best-effort: un errore locale non deve perdere il trigger.
                    Log.w(TAG, "record osservata fallita: ${error::class.java.simpleName}")
                }
            }
        dispatcher.dispatch(parsed.envelope)
    }

    private companion object {
        const val TAG = "ArgusIngress"
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
    @Inject lateinit var workLauncher: ReceiverWorkLauncher

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
        // Un programma P4 può durare ore: il callback del listener non possiede il processo.
        // La lease condivisa lo trasferisce al medesimo FGS usato dai receiver e dai sensori.
        workLauncher.launch("notification") {
            ingress.persistAndDispatch(parsed)
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
