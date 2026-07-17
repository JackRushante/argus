package dev.argus.automation.budget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.brain.ProviderId
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
class BudgetSettingsStoreTest {
    private lateinit var context: Context

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("argus_budget_private", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `default tutto illimitato e soglia 80`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        val settings = store.observe().value
        assertEquals(BudgetLimits(), settings.global)
        assertTrue(settings.perProvider.isEmpty())
        assertEquals(80, settings.softThresholdPct)
    }

    @Test
    fun `limiti globali persistono e si rileggono da store nuovo`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(
            store.setGlobalLimits(
                BudgetLimits(maxCallsPerHour = 20, maxCallsPerDay = 100, maxCostPerMonthMicros = 5_000_000),
            ),
        )
        val restored = AndroidBudgetSettingsStore(context)
        assertEquals(
            BudgetLimits(maxCallsPerHour = 20, maxCallsPerDay = 100, maxCostPerMonthMicros = 5_000_000),
            restored.observe().value.global,
        )
    }

    @Test
    fun `limiti per provider persistono per wireName`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setProviderLimits(ProviderId.OPENAI, BudgetLimits(maxCallsPerHour = 10)))
        val restored = AndroidBudgetSettingsStore(context)
        assertEquals(
            BudgetLimits(maxCallsPerHour = 10),
            restored.observe().value.perProvider["openai"],
        )
    }

    @Test
    fun `zero normalizzato a illimitato`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setGlobalLimits(BudgetLimits(maxCallsPerHour = 0)))
        assertNull(store.observe().value.global.maxCallsPerHour)
    }

    @Test
    fun `valori negativi rifiutati`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setGlobalLimits(BudgetLimits(maxCallsPerHour = 20)))
        assertFalse(store.setGlobalLimits(BudgetLimits(maxCallsPerHour = -1)))
        assertEquals(20, store.observe().value.global.maxCallsPerHour)
    }

    @Test
    fun `limite token mensile persiste globale e per provider`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setGlobalLimits(BudgetLimits(maxTokensPerMonth = 1_000_000)))
        assertTrue(store.setProviderLimits(ProviderId.HERMES, BudgetLimits(maxTokensPerMonth = 250_000)))

        val restored = AndroidBudgetSettingsStore(context)
        assertEquals(1_000_000L, restored.observe().value.global.maxTokensPerMonth)
        assertEquals(250_000L, restored.observe().value.perProvider["hermes"]?.maxTokensPerMonth)
    }

    @Test
    fun `token zero normalizzato a illimitato e negativo rifiutato`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setGlobalLimits(BudgetLimits(maxTokensPerMonth = 0)))
        assertNull(store.observe().value.global.maxTokensPerMonth)

        assertTrue(store.setGlobalLimits(BudgetLimits(maxTokensPerMonth = 500)))
        assertFalse(store.setGlobalLimits(BudgetLimits(maxTokensPerMonth = -1)))
        assertEquals(500L, store.observe().value.global.maxTokensPerMonth)
    }

    @Test
    fun `softThresholdPct fuori intervallo valido rifiutato`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertFalse(store.setSoftThresholdPct(0))
        assertFalse(store.setSoftThresholdPct(101))
        assertEquals(80, store.observe().value.softThresholdPct)
        assertTrue(store.setSoftThresholdPct(50))
        assertEquals(50, store.observe().value.softThresholdPct)
    }
}
