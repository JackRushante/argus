package dev.argus.automation.notification

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.runtime.TriggerEventId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationReplyGatewayInstrumentedTest {
    private lateinit var context: Context
    private lateinit var receiver: BroadcastReceiver
    private val delivered = CountDownLatch(1)
    @Volatile private var receivedText: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                receivedText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RESULT_KEY)
                    ?.toString()
                delivered.countDown()
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
    fun sendsSyntheticFreeFormReplyOnlyToMatchingOneToOneHandle() {
        val registry = ActiveNotificationReplyRegistry()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            71,
            Intent(REPLY_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        registry.replace(
            NotificationReplyHandle(
                packageName = "com.whatsapp",
                notificationKey = NOTIFICATION_KEY,
                conversationId = CONVERSATION_ID,
                eventId = EVENT_ID,
                isGroup = false,
                remoteInput = RemoteInput.Builder(RESULT_KEY).setAllowFreeFormInput(true).build(),
                pendingIntent = pendingIntent,
            ),
        )
        val delivery = AndroidNotificationReplyGateway(context, registry).send(
            NotificationReplyRequest(
                packageName = "com.whatsapp",
                notificationKey = NOTIFICATION_KEY,
                conversationId = CONVERSATION_ID,
                eventId = EVENT_ID,
                text = "test sintetico",
            ),
        )

        assertEquals(NotificationReplyDelivery.Sent, delivery)
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals("test sintetico", receivedText)
        assertEquals(0, registry.size())
    }

    private companion object {
        const val REPLY_ACTION = "dev.argus.automation.test.REPLY"
        const val RESULT_KEY = "reply_text"
        const val NOTIFICATION_KEY = "sbn:instrumented"
        const val CONVERSATION_ID = "shortcut:com.whatsapp:instrumented"
        val EVENT_ID = TriggerEventId("notification:instrumented")
    }
}
