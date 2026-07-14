package dev.argus.automation.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.NotificationEventDispatcher
import dev.argus.engine.notification.NotificationEventParser
import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationReplyGatewayTest {
    private lateinit var context: Context
    private lateinit var receiver: BroadcastReceiver
    private var receivedIntent: Intent? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                receivedIntent = intent
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(REPLY_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
    }

    @Test
    fun `gateway binds reply to exact active target and consumes handle once`() {
        val registry = ActiveNotificationReplyRegistry()
        registry.replace(handle(pendingIntent(), isGroup = false))
        val gateway = AndroidNotificationReplyGateway(context, registry)

        val wrongTarget = gateway.send(request(conversationId = "shortcut:com.whatsapp:wrong"))
        assertEquals("reply_channel_unavailable", assertIs<NotificationReplyDelivery.Failed>(wrongTarget).code)
        assertEquals(1, registry.size())

        val staleEvent = gateway.send(request(eventId = TriggerEventId("notification:stale")))
        assertEquals("reply_channel_unavailable", assertIs<NotificationReplyDelivery.Failed>(staleEvent).code)
        assertEquals(1, registry.size())

        assertIs<NotificationReplyDelivery.Sent>(gateway.send(request()))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val result = RemoteInput.getResultsFromIntent(assertNotNull(receivedIntent))
        assertEquals("risposta sicura", result?.getCharSequence(RESULT_KEY)?.toString())
        assertEquals(0, registry.size())
        assertEquals(
            "reply_channel_unavailable",
            assertIs<NotificationReplyDelivery.Failed>(gateway.send(request())).code,
        )
    }

    @Test
    fun `group invalid text and canceled channels fail closed`() {
        val registry = ActiveNotificationReplyRegistry()
        val gateway = AndroidNotificationReplyGateway(context, registry)
        registry.replace(handle(pendingIntent(), isGroup = true))
        assertEquals(
            "reply_channel_unavailable",
            assertIs<NotificationReplyDelivery.Failed>(gateway.send(request())).code,
        )

        registry.replace(handle(pendingIntent(), isGroup = false))
        assertEquals(
            "reply_request_invalid",
            assertIs<NotificationReplyDelivery.Failed>(gateway.send(request(text = "\u0000"))).code,
        )
        assertEquals(1, registry.size())

        val canceled = pendingIntent(requestCode = 2).also(PendingIntent::cancel)
        registry.replace(handle(canceled, isGroup = false))
        assertEquals(
            "channel_expired",
            assertIs<NotificationReplyDelivery.Failed>(gateway.send(request())).code,
        )
        assertEquals(0, registry.size())
    }

    @Test
    fun `ingress registers before dispatch and rehydrate never redispatches old events`() = runTest {
        val registry = ActiveNotificationReplyRegistry()
        val conversations = FakeObservedConversationStore()
        val dispatched = mutableListOf<String>()
        val ingress = NotificationIngress(
            snapshotFactory = AndroidNotificationSnapshotFactory(),
            parser = NotificationEventParser(),
            handleFactory = NotificationReplyHandleFactory(),
            registry = registry,
            observedConversations = conversations,
            dispatcher = NotificationEventDispatcher { dispatched += it.id.value },
        )
        val notification = statusBarNotification(pendingIntent())

        val parsed = assertNotNull(ingress.registerPosted(notification))
        assertEquals(1, registry.size())
        assertEquals(emptyList(), dispatched)
        ingress.persistAndDispatch(parsed)
        assertEquals(1, conversations.values.value.size)
        assertEquals(listOf(parsed.envelope.id.value), dispatched)

        val updatedNotification = statusBarNotification(pendingIntent(requestCode = 3), "nuovo messaggio")
        val updated = assertNotNull(ingress.registerPosted(updatedNotification))
        assertEquals(notification.key, updatedNotification.key)
        assertNotEquals(parsed.envelope.id, updated.envelope.id)
        val updatedEvent = updated.envelope.event as TriggerEvent.NotificationPosted
        val gateway = AndroidNotificationReplyGateway(context, registry)
        assertEquals(
            "reply_channel_unavailable",
            assertIs<NotificationReplyDelivery.Failed>(
                gateway.send(
                    NotificationReplyRequest(
                        packageName = updatedEvent.pkg,
                        notificationKey = updatedEvent.notificationKey.orEmpty(),
                        conversationId = updatedEvent.conversationId.orEmpty(),
                        eventId = parsed.envelope.id,
                        text = "stale",
                    ),
                ),
            ).code,
        )
        assertIs<NotificationReplyDelivery.Sent>(
            gateway.send(
                NotificationReplyRequest(
                    packageName = updatedEvent.pkg,
                    notificationKey = updatedEvent.notificationKey.orEmpty(),
                    conversationId = updatedEvent.conversationId.orEmpty(),
                    eventId = updated.envelope.id,
                    text = "corrente",
                ),
            ),
        )

        ingress.rehydrate(listOf(notification))
        assertEquals(1, registry.size())
        assertEquals(1, conversations.values.value.size)
        assertEquals(1, dispatched.size)
        ingress.remove(notification.key)
        assertEquals(0, registry.size())
    }

    private fun statusBarNotification(
        replyIntent: PendingIntent,
        message: String = "messaggio",
    ): StatusBarNotification {
        val sender = Person.Builder().setName("Moglie").setUri("tel:+39000").build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setShortcutId("shortcut-42")
            .setStyle(
                Notification.MessagingStyle(Person.Builder().setName("Io").build())
                    .addMessage(message, 10L, sender),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Rispondi",
                    replyIntent,
                ).addRemoteInput(remoteInput()).build(),
            )
            .build()
            .also { it.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) }
        return StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            42,
            "tag",
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            10L,
        )
    }

    private fun handle(
        pendingIntent: PendingIntent,
        isGroup: Boolean?,
    ) = NotificationReplyHandle(
        packageName = "com.whatsapp",
        notificationKey = NOTIFICATION_KEY,
        conversationId = CONVERSATION_ID,
        eventId = EVENT_ID,
        isGroup = isGroup,
        remoteInput = remoteInput(),
        pendingIntent = pendingIntent,
    )

    private fun request(
        conversationId: String = CONVERSATION_ID,
        eventId: TriggerEventId = EVENT_ID,
        text: String = "risposta sicura",
    ) = NotificationReplyRequest(
        packageName = "com.whatsapp",
        notificationKey = NOTIFICATION_KEY,
        conversationId = conversationId,
        eventId = eventId,
        text = text,
    )

    private fun remoteInput(): RemoteInput = RemoteInput.Builder(RESULT_KEY)
        .setAllowFreeFormInput(true)
        .build()

    private fun pendingIntent(requestCode: Int = 1): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(REPLY_ACTION).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private class FakeObservedConversationStore : ObservedConversationStore {
        val values = MutableStateFlow<List<ObservedConversation>>(emptyList())

        override suspend fun recent(limit: Int): List<ObservedConversation> = values.value.take(limit)

        override fun observeRecent(limit: Int): Flow<List<ObservedConversation>> = values

        override suspend fun record(conversation: ObservedConversation) {
            values.value = listOf(conversation)
        }
    }

    private companion object {
        const val REPLY_ACTION = "dev.argus.test.REPLY"
        const val RESULT_KEY = "reply_text"
        const val NOTIFICATION_KEY = "sbn:test"
        const val CONVERSATION_ID = "shortcut:com.whatsapp:hash"
        val EVENT_ID = TriggerEventId("notification:test")
    }
}
