package dev.argus.automation.base

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.SettingsScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Il tocco della notifica «Tocca per aprire …» (B4, 0.4.1) apre davvero la destinazione, l'id è stabile
 * per destinazione e senza il permesso di notifica non si pubblica nulla. I testi della notifica stanno
 * nelle risorse del modulo, che i test JVM non caricano: il contenuto si verifica sul dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDeferredOpenNotifierTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `without the notification permission nothing is published and the call says so`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = application.getSystemService(NotificationManager::class.java)

        val published = AndroidDeferredOpenNotifier(application).publish(DeferredOpenTarget.App("com.spotify.music"))

        assertFalse(published)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `an app target opens the launcher of that package`() {
        val intent = deferredOpenIntent(DeferredOpenTarget.App("com.spotify.music"))

        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals("com.spotify.music", intent.`package`)
        assertTrue(Intent.CATEGORY_LAUNCHER in intent.categories)
    }

    @Test
    fun `a url target opens that url whole`() {
        val url = "https://example.org/" + "x".repeat(200) + "?a=1"

        val intent = deferredOpenIntent(DeferredOpenTarget.Url(url))

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
    }

    @Test
    fun `a settings target opens the same screen the executor would have opened`() {
        assertEquals(
            Settings.ACTION_WIFI_SETTINGS,
            deferredOpenIntent(DeferredOpenTarget.SettingsPage(SettingsScreen.WIFI, null)).action,
        )
        val details = deferredOpenIntent(DeferredOpenTarget.SettingsPage(SettingsScreen.APP_DETAILS, "com.example.app"))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertEquals("package:com.example.app", details.dataString)
    }

    @Test
    fun `the same destination keeps the same id and another destination gets another`() {
        val spotify = DeferredOpenTarget.App("com.spotify.music")
        val telegram = DeferredOpenTarget.App("org.telegram.messenger")

        assertEquals(deferredOpenNotificationId(spotify), deferredOpenNotificationId(DeferredOpenTarget.App("com.spotify.music")))
        assertNotEquals(deferredOpenNotificationId(spotify), deferredOpenNotificationId(telegram))
        assertNotEquals(
            deferredOpenNotificationId(DeferredOpenTarget.Url("https://example.org/a")),
            deferredOpenNotificationId(DeferredOpenTarget.Url("https://example.org/b")),
        )
    }

    @Test
    fun `a long url is shortened for the title and a short one is kept whole`() {
        assertEquals("https://example.org/a?b=1", shortenedForLabel("https://example.org/a?b=1"))
        val shortened = shortenedForLabel("https://example.org/" + "x".repeat(200))
        assertTrue(shortened.endsWith("…"))
        assertEquals(81, shortened.length)
    }
}
