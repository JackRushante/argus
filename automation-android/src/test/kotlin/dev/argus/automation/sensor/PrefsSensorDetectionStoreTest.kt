package dev.argus.automation.sensor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSensorDetectionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = AutomationId("a")
    private val fingerprint = ApprovalFingerprint("a".repeat(64))
    private val kind = SensorKind.SIGNIFICANT_MOTION

    private fun store() = PrefsSensorDetectionStore(context)

    @Test
    fun `a pending detection survives a fresh store instance and reuses the sequence`() {
        val first = store().beginDetection(id, fingerprint, kind)

        val reopened = store().pending(id, fingerprint)
        assertEquals(first, reopened)
    }

    @Test
    fun `a completed detection is no longer pending`() {
        val store = store()
        val detection = store.beginDetection(id, fingerprint, kind)
        store.completeDetection(id, fingerprint, detection.sequence)

        assertNull(store().pending(id, fingerprint))
    }

    @Test
    fun `the next detection gets a strictly greater sequence`() {
        val store = store()
        val first = store.beginDetection(id, fingerprint, kind)
        store.completeDetection(id, fingerprint, first.sequence)
        val second = store.beginDetection(id, fingerprint, kind)

        assertTrue(second.sequence > first.sequence)
    }

    @Test
    fun `a pending of a superseded fingerprint does not match the new revision`() {
        store().beginDetection(id, fingerprint, kind)

        assertNull(store().pending(id, ApprovalFingerprint("b".repeat(64))))
    }

    @Test
    fun `forget removes the record and its index entry`() {
        val store = store()
        store.beginDetection(id, fingerprint, kind)
        store.forget(id)

        assertTrue(store().knownIds().isEmpty())
        assertNull(store().pending(id, fingerprint))
    }
}
