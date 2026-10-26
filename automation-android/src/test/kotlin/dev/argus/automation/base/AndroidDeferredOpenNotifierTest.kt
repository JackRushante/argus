package dev.argus.automation.base

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.SettingsScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La notifica «Tocca per aprire …» (B4, 0.4.1): contenuto, tocco che apre la destinazione, id stabile per
 * destinazione, e nessuna notifica senza il permesso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDeferredOpenNotifierTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = application.getSystemService(NotificationManager::class.java)

    private fun posted() = shadowOf(manager).allNotifications

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun `without the notification permission nothing is published and the call says so`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidDeferredOpenNotifier(application)

        assertFalse(notifier.publish(DeferredOpenTarget.App("com.spotify.music")))

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `an app notification names the app and opens its launcher on tap`() {
        grant()

        assertTrue(AndroidDeferredOpenNotifier(application).publish(DeferredOpenTarget.App("com.spotify.music")))

        val notification = posted().single()
        // Il pacchetto non è installato nel test: l'etichetta ripiega sul nome del pacchetto.
        assertEquals("Tap to open com.spotify.music", notification.title())
        assertNotNull(notification.contentIntent)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals("com.spotify.music", intent.`package`)
        assertTrue(Intent.CATEGORY_LAUNCHER in intent.categories)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `a url notification opens the url and shows it whole when short`() {
        grant()

        AndroidDeferredOpenNotifier(application).publish(DeferredOpenTarget.Url("https://example.org/a?b=1"))

        val notification = posted().single()
        assertEquals("Tap to open https://example.org/a?b=1", notification.title())
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/a?b=1", intent.dataString)
    }

    @Test
    fun `a very long url is shortened in the title but not in the intent`() {
        grant()
        val url = "https://example.org/" + "x".repeat(200)

        AndroidDeferredOpenNotifier(application).publish(DeferredOpenTarget.Url(url))

        val notification = posted().single()
        val title = notification.title().orEmpty()
        assertTrue(title.endsWith("…"), title)
        assertTrue(title.length < 120, title)
        assertEquals(url, shadowOf(notification.contentIntent).savedIntent.dataString)
    }

    @Test
    fun `a settings notification opens that settings screen`() {
        grant()

        AndroidDeferredOpenNotifier(application)
            .publish(DeferredOpenTarget.SettingsPage(SettingsScreen.WIFI, null))

        val notification = posted().single()
        assertEquals("Tap to open Wi-Fi settings", notification.title())
        assertEquals(
            android.provider.Settings.ACTION_WIFI_SETTINGS,
            shadowOf(notification.contentIntent).savedIntent.action,
        )
    }

    @Test
    fun `the same destination replaces its notification and another destination adds one`() {
        grant()
        val notifier = AndroidDeferredOpenNotifier(application)

        repeat(3) { notifier.publish(DeferredOpenTarget.App("com.spotify.music")) }
        assertEquals(1, posted().size)

        notifier.publish(DeferredOpenTarget.App("org.telegram.messenger"))
        assertEquals(2, posted().size)
    }

    @Test
    fun `the notification carries the destination only, never message text`() {
        grant()

        AndroidDeferredOpenNotifier(application).publish(DeferredOpenTarget.App("com.whatsapp"))

        val notification = posted().single()
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("Android does not let Argus open it by itself from the background.", body)
    }
}
