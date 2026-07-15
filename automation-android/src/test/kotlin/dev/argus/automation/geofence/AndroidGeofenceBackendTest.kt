package dev.argus.automation.geofence

import android.app.PendingIntent
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidGeofenceBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `pending intent is explicit targeted mutable and parser binds uri to extras`() {
        val registration = GeofenceRegistration(
            automationId = AutomationId("home/rule"),
            approvalFingerprint = ApprovalFingerprint("d".repeat(64)),
            latitude = 45.0,
            longitude = 9.0,
            radiusM = 150f,
        )
        val operation = AndroidGeofenceBackend.operation(context, registration)
        val saved = savedIntent(operation).apply {
            putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        }

        assertFalse(operation.isImmutable)
        assertEquals(context.packageName, operation.creatorPackage)
        assertEquals(GeofenceTransitionReceiver::class.java.name, saved.component?.className)
        assertEquals(
            GeofenceSignal(
                registration.automationId,
                registration.approvalFingerprint,
                Transition.EXIT,
            ),
            AndroidGeofenceBackend.parseSignal(saved),
        )

        saved.putExtra(AndroidGeofenceBackend.EXTRA_AUTOMATION_ID, "other")
        assertNull(AndroidGeofenceBackend.parseSignal(saved))
    }

    private fun savedIntent(pendingIntent: PendingIntent) = shadowOf(pendingIntent).savedIntent
}
