package dev.argus.automation.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
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
class AndroidHealthAlertStoreTest {
    private lateinit var context: Context

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("argus_health_alerts")
    }

    @Test
    fun `alerts are on by default and the switch persists`() = runTest {
        val store = AndroidHealthAlertStore(context)
        assertTrue(store.enabled.value)

        assertTrue(store.setEnabled(false))
        assertFalse(store.enabled.value)
        assertFalse(AndroidHealthAlertStore(context).enabled.value)
    }

    @Test
    fun `rule marks persist and a recovery keeps the day`() {
        val store = AndroidHealthAlertStore(context)
        assertFalse(store.ruleAlerted("r1"))
        assertNull(store.ruleAlertDay("r1"))

        store.markRuleAlerted("r1", 42)
        val restored = AndroidHealthAlertStore(context)
        assertTrue(restored.ruleAlerted("r1"))
        assertEquals(42L, restored.ruleAlertDay("r1"))
        assertFalse(restored.ruleAlerted("r2"))

        restored.clearRuleAlerted("r1")
        assertFalse(restored.ruleAlerted("r1"))
        assertEquals(42L, restored.ruleAlertDay("r1"), "il giorno resta: niente secondo avviso oggi")
    }

    @Test
    fun `capability marks persist per capability`() {
        val store = AndroidHealthAlertStore(context)
        store.markCapabilityLost(HealthCapability.PRIVILEGED_BACKEND, alertedDay = 7)
        store.markCapabilityLost(HealthCapability.LOCATION, alertedDay = null)

        val restored = AndroidHealthAlertStore(context)
        assertTrue(restored.capabilityLost(HealthCapability.PRIVILEGED_BACKEND))
        assertEquals(7L, restored.capabilityAlertDay(HealthCapability.PRIVILEGED_BACKEND))
        assertTrue(restored.capabilityLost(HealthCapability.LOCATION))
        assertNull(restored.capabilityAlertDay(HealthCapability.LOCATION))
        assertFalse(restored.capabilityLost(HealthCapability.ACCESSIBILITY))

        restored.markCapabilityRestored(HealthCapability.PRIVILEGED_BACKEND)
        assertFalse(restored.capabilityLost(HealthCapability.PRIVILEGED_BACKEND))
        assertEquals(7L, restored.capabilityAlertDay(HealthCapability.PRIVILEGED_BACKEND))
    }
}
