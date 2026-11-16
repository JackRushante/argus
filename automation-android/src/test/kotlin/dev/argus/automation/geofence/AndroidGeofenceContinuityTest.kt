package dev.argus.automation.geofence

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidGeofenceContinuityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clear() {
        context.getSharedPreferences(AndroidGeofenceContinuity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun setBootCount(count: Int) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, count)
    }

    @Test
    fun `a first start is lapsed and a later process restart in the same boot is not`() {
        setBootCount(7)
        val first = AndroidGeofenceContinuity(context)
        assertTrue(first.registrationsMayHaveLapsed())
        first.markRegistered()
        assertFalse(first.registrationsMayHaveLapsed())

        // Nuovo processo, stesso avvio del telefono.
        assertFalse(AndroidGeofenceContinuity(context).registrationsMayHaveLapsed())
    }

    @Test
    fun `a reboot is lapsed until the registrations are restored`() {
        setBootCount(7)
        AndroidGeofenceContinuity(context).apply {
            registrationsMayHaveLapsed()
            markRegistered()
        }

        setBootCount(8)
        assertTrue(AndroidGeofenceContinuity(context).registrationsMayHaveLapsed())
        // Il processo muore prima di riallineare: il successivo resta «forse perse».
        val next = AndroidGeofenceContinuity(context)
        assertTrue(next.registrationsMayHaveLapsed())
        next.markRegistered()
        assertFalse(AndroidGeofenceContinuity(context).registrationsMayHaveLapsed())
    }
}
