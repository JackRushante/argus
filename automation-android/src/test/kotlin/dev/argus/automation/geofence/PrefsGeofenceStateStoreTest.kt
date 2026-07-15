package dev.argus.automation.geofence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
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
class PrefsGeofenceStateStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences(PrefsGeofenceStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @Before
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `registration transition and delete survive fresh store instances`() {
        val id = AutomationId("home")
        val fingerprint = ApprovalFingerprint("e".repeat(64))
        val registration = GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f)

        PrefsGeofenceStateStore(context).apply {
            prepare(registration)
            activate(id, fingerprint)
            assertEquals(1, beginTransition(id, fingerprint, Transition.ENTER)?.sequence)
        }

        PrefsGeofenceStateStore(context).apply {
            val pending = requireNotNull(pending(id, fingerprint))
            assertEquals(Transition.ENTER, pending.transition)
            assertEquals(1, pending.sequence)
            completeTransition(id, fingerprint, pending.sequence)
            val restored = requireNotNull(get(id))
            assertTrue(restored.active)
            assertEquals(Transition.ENTER, restored.lastTransition)
            assertEquals(1, restored.sequence)
            assertNull(beginTransition(id, fingerprint, Transition.ENTER))
            delete(id)
        }
        assertFalse(id in PrefsGeofenceStateStore(context).knownIds())
    }

    @Test
    fun `corrupt record retains cleanup identity and prepare repairs it`() {
        val id = AutomationId("recover-corrupt")
        preferences.edit()
            .putStringSet(PrefsGeofenceStateStore.KEY_KNOWN_IDS, setOf(id.value))
            .commit()
        val store = PrefsGeofenceStateStore(context)

        assertTrue(id in store.knownIds())
        assertNull(store.get(id))

        val fingerprint = ApprovalFingerprint("f".repeat(64))
        store.prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
        assertEquals(fingerprint, requireNotNull(store.get(id)).approvalFingerprint)
    }
}
