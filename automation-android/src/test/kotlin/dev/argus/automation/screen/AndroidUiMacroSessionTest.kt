package dev.argus.automation.screen

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * La notifica «Ferma» della macro (0.7.2): senza una notifica visibile la macro non parte. I testi della
 * notifica stanno nelle risorse del modulo, che i test JVM non caricano: qui si provano i rifiuti, che
 * arrivano prima di leggere qualunque testo; la notifica mostrata si verifica sul dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUiMacroSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun grantNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `a macro channel the user blocked refuses the macro instead of running it without stop`() {
        grantNotifications()
        // L'utente ha spento il canale delle macro: Android tiene la sua scelta anche se l'app lo ricrea.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Macro", NotificationManager.IMPORTANCE_NONE),
        )
        val session = AndroidUiMacroSession(context)

        assertFalse(session.begin())
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `notifications switched off for the app refuse the macro`() {
        grantNotifications()
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(AndroidUiMacroSession(context).begin())
    }

    private companion object {
        const val CHANNEL_ID = "argus_screen_macro"
    }
}
