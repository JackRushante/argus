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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

        // D8: l'event id non vincola più la reply, vale l'handle più recente della conversazione.
        assertIs<NotificationReplyDelivery.Sent>(
            gateway.send(request(eventId = TriggerEventId("notification:older"))),
        )
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
    fun `reply uses the most recent handle of the same conversation but never another conversation or a group`() {
        val registry = ActiveNotificationReplyRegistry()
        val gateway = AndroidNotificationReplyGateway(context, registry)
        registry.replace(handle(pendingIntent(), isGroup = false, eventId = TriggerEventId("notification:1")))
        registry.replace(
            handle(pendingIntent(requestCode = 2), isGroup = false, eventId = TriggerEventId("notification:2")),
        )

        // Sempre chat 1:1 e stessa conversazione: un'altra conversazione o un gruppo restano esclusi.
        assertEquals(
            "reply_channel_unavailable",
            assertIs<NotificationReplyDelivery.Failed>(
                gateway.send(request(conversationId = "shortcut:com.whatsapp:other")),
            ).code,
        )
        registry.replace(
            handle(pendingIntent(requestCode = 3), isGroup = true, eventId = TriggerEventId("notification:3")),
        )
        assertEquals(
            "reply_channel_unavailable",
            assertIs<NotificationReplyDelivery.Failed>(
                gateway.send(request(eventId = TriggerEventId("notification:1"))),
            ).code,
        )
        assertEquals(1, registry.size())
    }

    @Test
    fun `registry keeps the burst of a conversation and closes it when the reply consumes the handle`() {
        val registry = ActiveNotificationReplyRegistry()
        val first = TriggerEventId("notification:1")
        val second = TriggerEventId("notification:2")
        val third = TriggerEventId("notification:3")
        registry.replace(handle(pendingIntent(), false, eventId = first, messageText = "uno"))
        registry.replace(handle(pendingIntent(requestCode = 2), false, eventId = second, messageText = "due"))
        registry.replace(handle(pendingIntent(requestCode = 3), false, eventId = third, messageText = "tre"))

        val burst = assertNotNull(registry.burst(NOTIFICATION_KEY, CONVERSATION_ID, first))
        assertEquals(listOf("uno", "due", "tre"), burst.messages.map { it.text })
        assertEquals(third, burst.latestEventId)
        // Dal secondo evento in poi; un evento sconosciuto restituisce solo l'ultimo messaggio.
        assertEquals(
            listOf("due", "tre"),
            assertNotNull(registry.burst(NOTIFICATION_KEY, CONVERSATION_ID, second)).messages.map { it.text },
        )
        assertEquals(
            listOf("tre"),
            assertNotNull(registry.burst(NOTIFICATION_KEY, CONVERSATION_ID, TriggerEventId("notification:x")))
                .messages.map { it.text },
        )
        assertNull(registry.burst(NOTIFICATION_KEY, "shortcut:com.whatsapp:other", first))
        assertFalse("uno" in burst.toString() || "uno" in burst.messages.first().toString())

        // La reply consuma l'handle e chiude la raffica: i messaggi dopo ne aprono una nuova.
        val gateway = AndroidNotificationReplyGateway(context, registry)
        assertIs<NotificationReplyDelivery.Sent>(gateway.send(request(eventId = first)))
        assertNull(registry.burst(NOTIFICATION_KEY, CONVERSATION_ID, first))
    }

    @Test
    fun `burst log is bounded cleared on removal and reset when the conversation changes`() {
        val registry = ActiveNotificationReplyRegistry()
        repeat(12) { index ->
            registry.replace(
                handle(
                    pendingIntent(requestCode = index + 1),
                    false,
                    eventId = TriggerEventId("notification:$index"),
                    messageText = "m$index",
                ),
            )
        }
        val burst = assertNotNull(registry.burst(NOTIFICATION_KEY, CONVERSATION_ID, TriggerEventId("notification:4")))
        assertEquals((4..11).map { "m$it" }, burst.messages.map { it.text })

        registry.replace(
            handle(
                pendingIntent(requestCode = 20),
                false,
                eventId = TriggerEventId("notification:other"),
                conversationId = "shortcut:com.whatsapp:other",
                messageText = "altro",
            ),
        )
        assertNull(registry.burst(NOTIFICATION_KEY, CONVERSATION_ID, TriggerEventId("notification:11")))

        registry.remove(NOTIFICATION_KEY)
        assertNull(registry.burst(NOTIFICATION_KEY, "shortcut:com.whatsapp:other", TriggerEventId("notification:other")))
    }

    @Test
    fun `awaitNewMessage resumes only on a newer event of the same conversation`() = runTest {
        val registry = ActiveNotificationReplyRegistry()
        val first = TriggerEventId("notification:1")
        registry.replace(handle(pendingIntent(), false, eventId = first, messageText = "uno"))

        val waiting = async { registry.awaitNewMessage(NOTIFICATION_KEY, CONVERSATION_ID, first) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        // Stesso evento riregistrato (rehydrate): non è un nuovo messaggio.
        registry.replace(handle(pendingIntent(requestCode = 2), false, eventId = first, messageText = "uno"))
        runCurrent()
        assertFalse(waiting.isCompleted)

        val second = TriggerEventId("notification:2")
        registry.replace(handle(pendingIntent(requestCode = 3), false, eventId = second, messageText = "due"))
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertEquals(second, waiting.await())
    }

    @Test
    fun `ingress stores the message text in the handle so a burst can be merged`() = runTest {
        val registry = ActiveNotificationReplyRegistry()
        val ingress = NotificationIngress(
            snapshotFactory = AndroidNotificationSnapshotFactory(),
            parser = NotificationEventParser(),
            handleFactory = NotificationReplyHandleFactory(),
            registry = registry,
            observedConversations = FakeObservedConversationStore(),
            dispatcher = NotificationEventDispatcher { },
            privacyAccepted = { true },
        )
        val first = assertNotNull(ingress.registerPosted(statusBarNotification(pendingIntent(), "ci sei?")))
        val second = assertNotNull(
            ingress.registerPosted(statusBarNotification(pendingIntent(requestCode = 2), "rispondi")),
        )
        val event = first.envelope.event as TriggerEvent.NotificationPosted

        val burst = assertNotNull(
            registry.burst(event.notificationKey.orEmpty(), event.conversationId.orEmpty(), first.envelope.id),
        )

        assertEquals(listOf("ci sei?", "rispondi"), burst.messages.map { it.text })
        assertEquals(second.envelope.id, burst.latestEventId)
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
            privacyAccepted = { true },
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
        // D8: una reply nata dal PRIMO evento parte sull'handle più recente della conversazione.
        assertIs<NotificationReplyDelivery.Sent>(
            gateway.send(
                NotificationReplyRequest(
                    packageName = updatedEvent.pkg,
                    notificationKey = updatedEvent.notificationKey.orEmpty(),
                    conversationId = updatedEvent.conversationId.orEmpty(),
                    eventId = parsed.envelope.id,
                    text = "dal primo evento",
                ),
            ),
        )
        // L'handle si consuma una volta sola.
        assertEquals(
            "reply_channel_unavailable",
            assertIs<NotificationReplyDelivery.Failed>(
                gateway.send(
                    NotificationReplyRequest(
                        packageName = updatedEvent.pkg,
                        notificationKey = updatedEvent.notificationKey.orEmpty(),
                        conversationId = updatedEvent.conversationId.orEmpty(),
                        eventId = updated.envelope.id,
                        text = "gia consumato",
                    ),
                ),
            ).code,
        )

        ingress.rehydrate(listOf(notification))
        assertEquals(1, registry.size())
        assertEquals(1, conversations.values.value.size)
        assertEquals(1, dispatched.size)
        ingress.remove(notification.key)
        assertEquals(0, registry.size())
    }

    @Test
    fun `real whatsapp keys with trailing newline in the tag flow end to end`() {
        // Il tag WhatsApp è Base64.DEFAULT con newline finale: la key di sistema lo contiene.
        val whatsappKey = "0|com.whatsapp|1|50V0XCj31HQ4wDorL8SwIw3yYoKJHj2Q6L42gBAmoIw=\n|10390"
        val registry = ActiveNotificationReplyRegistry()
        registry.replace(
            NotificationReplyHandle(
                packageName = "com.whatsapp",
                notificationKey = whatsappKey,
                conversationId = CONVERSATION_ID,
                eventId = EVENT_ID,
                isGroup = false,
                remoteInput = remoteInput(),
                pendingIntent = pendingIntent(requestCode = 7),
            ),
        )
        val gateway = AndroidNotificationReplyGateway(context, registry)

        assertIs<NotificationReplyDelivery.Sent>(
            gateway.send(
                NotificationReplyRequest(
                    packageName = "com.whatsapp",
                    notificationKey = whatsappKey,
                    conversationId = CONVERSATION_ID,
                    eventId = EVENT_ID,
                    text = "risposta sicura",
                ),
            ),
        )
        assertEquals(0, registry.size())
    }

    @Test
    fun `privacy gate blocks handles observed rows and dispatch`() = runTest {
        val registry = ActiveNotificationReplyRegistry()
        val conversations = FakeObservedConversationStore()
        val dispatched = mutableListOf<String>()
        var privacy = false
        val ingress = NotificationIngress(
            snapshotFactory = AndroidNotificationSnapshotFactory(),
            parser = NotificationEventParser(),
            handleFactory = NotificationReplyHandleFactory(),
            registry = registry,
            observedConversations = conversations,
            dispatcher = NotificationEventDispatcher { dispatched += it.id.value },
            privacyAccepted = { privacy },
        )
        val notification = statusBarNotification(pendingIntent())

        assertEquals(null, ingress.registerPosted(notification))
        assertEquals(0, registry.size())

        ingress.rehydrate(listOf(notification))
        assertEquals(0, registry.size())

        // La revoca può avvenire tra il callback main thread e la coroutine di dispatch.
        privacy = true
        val parsed = assertNotNull(ingress.registerPosted(notification))
        assertEquals(1, registry.size())
        privacy = false
        ingress.persistAndDispatch(parsed)
        assertEquals(0, conversations.values.value.size)
        assertEquals(emptyList(), dispatched)
    }

    @Test
    fun `non whatsapp packages dispatch generic events without reply handles or picker rows`() = runTest {
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
            privacyAccepted = { true },
        )
        val notification = statusBarNotification(pendingIntent(), packageName = "com.example.chat")

        val parsed = assertNotNull(ingress.registerPosted(notification))
        assertEquals(0, registry.size(), "handle RemoteInput solo per package WhatsApp trusted")

        ingress.persistAndDispatch(parsed)
        assertEquals(0, conversations.values.value.size, "il picker P1 osserva solo WhatsApp")
        assertEquals(listOf(parsed.envelope.id.value), dispatched)

        ingress.rehydrate(listOf(notification))
        assertEquals(0, registry.size())
    }

    private fun statusBarNotification(
        replyIntent: PendingIntent,
        message: String = "messaggio",
        packageName: String = "com.whatsapp",
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
            packageName,
            packageName,
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
        eventId: TriggerEventId = EVENT_ID,
        conversationId: String = CONVERSATION_ID,
        messageText: String? = null,
    ) = NotificationReplyHandle(
        packageName = "com.whatsapp",
        notificationKey = NOTIFICATION_KEY,
        conversationId = conversationId,
        eventId = eventId,
        isGroup = isGroup,
        remoteInput = remoteInput(),
        pendingIntent = pendingIntent,
        messageText = messageText,
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

        override suspend fun clear() {
            values.value = emptyList()
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
