package dev.argus.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import dev.argus.shizuku.ShizukuGateway
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemAndroidCapabilityStateSourceTest {
    @Test
    fun `disabled app notifications are unavailable even with runtime permission`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val gateway = ShizukuGateway(context)
        val source = SystemAndroidCapabilityStateSource(context, gateway)

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(source.read().notificationsGranted)

        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(source.read().notificationsGranted)
        gateway.close()
    }

    @Test
    fun `location services switched off from the quick settings are reflected in the state`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val locationManager = context.getSystemService(LocationManager::class.java)
        val gateway = ShizukuGateway(context)
        val source = SystemAndroidCapabilityStateSource(context, gateway)

        shadowOf(locationManager).setLocationEnabled(false)
        assertFalse(source.read().locationEnabled)

        shadowOf(locationManager).setLocationEnabled(true)
        assertTrue(source.read().locationEnabled)
        gateway.close()
    }
}
