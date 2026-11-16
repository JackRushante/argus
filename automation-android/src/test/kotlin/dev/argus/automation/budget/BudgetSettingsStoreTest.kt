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
        // Cancella anche il file: un file vuoto vuol dire «salvato senza limiti» (utente legacy).
        context.deleteSharedPreferences("argus_budget_private")
    }

    @Test
    fun `mai salvato vale il default di 200 chiamate al giorno e soglia 80`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        val settings = store.observe().value
        assertEquals(BudgetLimits(maxCallsPerDay = 200), settings.global)
        assertTrue(settings.usingDefaults)
        assertTrue(settings.perProvider.isEmpty())
        assertEquals(80, settings.softThresholdPct)
    }

    @Test
    fun `il default si toglie salvando illimitato e resta tolto`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setGlobalLimits(BudgetLimits()))
        assertEquals(BudgetLimits(), store.observe().value.global)
        assertFalse(store.observe().value.usingDefaults)

        val restored = AndroidBudgetSettingsStore(context).observe().value
        assertEquals(BudgetLimits(), restored.global)
        assertFalse(restored.usingDefaults)
    }

    @Test
    fun `il default si cambia come ogni altro limite`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setGlobalLimits(BudgetLimits(maxCallsPerDay = 50)))
        assertEquals(BudgetLimits(maxCallsPerDay = 50), AndroidBudgetSettingsStore(context).observe().value.global)
    }

    @Test
    fun `cambiare solo la soglia o un provider conserva il default giornaliero`() = runTest {
        val store = AndroidBudgetSettingsStore(context)
        assertTrue(store.setSoftThresholdPct(50))
        val afterSoft = AndroidBudgetSettingsStore(context).observe().value
        assertEquals(BudgetLimits(maxCallsPerDay = 200), afterSoft.global)
        assertFalse(afterSoft.usingDefaults)

        context.deleteSharedPreferences("argus_budget_private")
        assertTrue(AndroidBudgetSettingsStore(context).setProviderLimits(ProviderId.OPENAI, BudgetLimits(maxCallsPerHour = 5)))
        assertEquals(BudgetLimits(maxCallsPerDay = 200), AndroidBudgetSettingsStore(context).observe().value.global)
    }

    @Test
    fun `chi aveva salvato limiti con una versione precedente li conserva tali e quali`() = runTest {
        // Scrittura come la faceva 0.7.1: nessun marcatore, solo le chiavi dei limiti.
        context.getSharedPreferences("argus_budget_private", Context.MODE_PRIVATE)
            .edit().putInt("global.maxCallsPerHour", 20).commit()
        val settings = AndroidBudgetSettingsStore(context).observe().value
        assertEquals(BudgetLimits(maxCallsPerHour = 20), settings.global)
        assertFalse(settings.usingDefaults)
    }

    @Test
    fun `chi aveva salvato tutto illimitato con una versione precedente resta illimitato`() = runTest {
        // 0.7.1 rimuoveva le chiavi a zero: resta solo il file, vuoto.
        context.getSharedPreferences("argus_budget_private", Context.MODE_PRIVATE)
            .edit().remove("global.maxCallsPerDay").commit()
        val settings = AndroidBudgetSettingsStore(context).observe().value
        assertEquals(BudgetLimits(), settings.global)
        assertFalse(settings.usingDefaults)
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
