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
    fun `an unknown state survives fresh store instances and keeps sequence and pending edge`() {
        val id = AutomationId("home-unknown")
        val fingerprint = ApprovalFingerprint("d".repeat(64))
        val registration = GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f)

        PrefsGeofenceStateStore(context).apply {
            prepare(registration)
            activate(id, fingerprint)
            val first = requireNotNull(beginTransition(id, fingerprint, Transition.ENTER))
            completeTransition(id, fingerprint, first.sequence)
            // Un EXIT accettato ma non ancora confermato: resta pendente.
            assertEquals(2, beginTransition(id, fingerprint, Transition.EXIT)?.sequence)

            markUnknown(id, fingerprint)
        }

        PrefsGeofenceStateStore(context).apply {
            val restored = requireNotNull(get(id))
            assertNull(restored.lastTransition)
            assertEquals(1, restored.sequence)
            assertEquals(Transition.EXIT, restored.pendingTransition)
            assertEquals(2, restored.pendingSequence)
            assertTrue(restored.active)
        }
    }

    @Test
    fun `from an unknown state both edges are delivered and nothing is a duplicate`() {
        val id = AutomationId("home-edges")
        val fingerprint = ApprovalFingerprint("c".repeat(64))
        val store = PrefsGeofenceStateStore(context)
        store.prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
        store.activate(id, fingerprint)
        store.beginTransition(id, fingerprint, Transition.ENTER)
            ?.let { store.completeTransition(id, fingerprint, it.sequence) }
        assertNull(store.beginTransition(id, fingerprint, Transition.ENTER))

        store.markUnknown(id, fingerprint)

        val enter = requireNotNull(store.beginTransition(id, fingerprint, Transition.ENTER))
        store.completeTransition(id, fingerprint, enter.sequence)
        store.markUnknown(id, fingerprint)
        assertEquals(
            Transition.EXIT,
            store.beginTransition(id, fingerprint, Transition.EXIT)?.transition,
        )
    }

    @Test
    fun `forgetting the state of another revision or of an unknown rule does nothing`() {
        val id = AutomationId("home-stale")
        val fingerprint = ApprovalFingerprint("b".repeat(64))
        val store = PrefsGeofenceStateStore(context)
        store.prepare(GeofenceRegistration(id, fingerprint, 45.0, 9.0, 150f))
        store.activate(id, fingerprint)
        store.beginTransition(id, fingerprint, Transition.ENTER)
            ?.let { store.completeTransition(id, fingerprint, it.sequence) }

        store.markUnknown(id, ApprovalFingerprint("a".repeat(64)))
        store.markUnknown(AutomationId("missing"), fingerprint)

        assertEquals(Transition.ENTER, requireNotNull(store.get(id)).lastTransition)
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
