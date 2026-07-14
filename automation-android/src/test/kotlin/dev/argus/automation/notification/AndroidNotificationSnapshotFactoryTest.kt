package dev.argus.automation.notification

import android.app.Notification
import android.app.Person
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationSnapshotFactoryTest {
    private lateinit var context: android.content.Context
    private val factory = AndroidNotificationSnapshotFactory()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `messaging style uses latest message and preserves trusted identity candidates`() {
        val localUser = Person.Builder().setName("Io").build()
        val oldSender = Person.Builder().setName("Vecchio").setUri("tel:+39001").build()
        val latestSender = Person.Builder().setName("Moglie").setUri("tel:+39002").build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Titolo fallback")
            .setContentText("Testo fallback")
            .setShortcutId("shortcut-42")
            .setStyle(
                Notification.MessagingStyle(localUser)
                    .addMessage("vecchio", 1L, oldSender)
                    .addMessage("ciao", 2L, latestSender),
            )
            .build()
            .also {
                it.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            }

        val snapshot = factory.from(
            notification = notification,
            packageName = "com.whatsapp",
            notificationKey = "sbn:1",
            postedAtMillis = 2L,
        )

        assertEquals("shortcut-42", snapshot.shortcutId)
        assertEquals("Moglie", snapshot.sender)
        assertEquals("ciao", snapshot.messageText)
        assertEquals("tel:+39002", snapshot.personUris.first())
        assertEquals(false, snapshot.isGroup)
        assertFalse(snapshot.isGroupSummary)
    }

    @Test
    fun `fallback extras never invent group metadata and people uri remains available`() {
        val person = Person.Builder().setName("Anna").setUri("mailto:anna@example.test").build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Anna")
            .setContentText("fallback")
            .addPerson(person)
            .build()

        val snapshot = factory.from(notification, "com.whatsapp", "sbn:2", 3L)

        assertEquals("Anna", snapshot.title)
        assertEquals("fallback", snapshot.fallbackText)
        assertNull(snapshot.messageText)
        assertNull(snapshot.isGroup)
        assertTrue("mailto:anna@example.test" in snapshot.personUris)
    }

    @Test
    fun `group summary flag is exposed for fail closed filtering`() {
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText("summary")
            .setGroup("group")
            .setGroupSummary(true)
            .build()

        val snapshot = factory.from(notification, "com.whatsapp", "sbn:3", 4L)

        assertTrue(snapshot.isGroupSummary)
    }
}
