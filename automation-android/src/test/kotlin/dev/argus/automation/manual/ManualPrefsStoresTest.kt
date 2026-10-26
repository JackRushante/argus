package dev.argus.automation.manual

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.AutomationId
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Preferenze dell'avvio manuale (F8) e della pausa (F21): sopravvivono al riavvio del processo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualPrefsStoresTest {
    private lateinit var context: Context

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        listOf("argus_pause", "argus_manual_tokens", "argus_manual_tile").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    // --- Pausa ---

    @Test
    fun `the pause starts off, follows the switch and survives a restart`() {
        val first = PrefsAutomationPauseStore(context)
        assertFalse(first.isPaused())
        assertFalse(first.paused.value)

        first.setPaused(true)
        assertTrue(first.isPaused())
        assertTrue(first.paused.value)

        // Un altro processo (o un riavvio del telefono) rilegge lo stesso valore: la pausa non si toglie da sola.
        val restored = PrefsAutomationPauseStore(context)
        assertTrue(restored.isPaused())

        restored.setPaused(false)
        assertFalse(PrefsAutomationPauseStore(context).isPaused())
    }

    @Test
    fun `setting the same value twice changes nothing`() {
        val store = PrefsAutomationPauseStore(context)
        store.setPaused(true)
        store.setPaused(true)
        assertTrue(store.paused.value)
        store.setPaused(false)
        store.setPaused(false)
        assertFalse(store.paused.value)
    }

    // --- Token ---

    @Test
    fun `a token is created once per rule, stable, long and different between rules`() {
        val store = PrefsManualTokenStore(context)
        val first = store.tokenFor(AutomationId("m1"))

        assertEquals(PrefsManualTokenStore.TOKEN_BYTES * 2, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertEquals(first, store.tokenFor(AutomationId("m1")))
        assertNotEquals(first, store.tokenFor(AutomationId("m2")))
        // Dopo un riavvio la scorciatoia già fissata vale ancora.
        assertEquals(first, PrefsManualTokenStore(context).tokenFor(AutomationId("m1")))
    }

    @Test
    fun `only the exact token of that rule is valid`() {
        val store = PrefsManualTokenStore(context)
        val token = store.tokenFor(AutomationId("m1"))
        store.tokenFor(AutomationId("m2"))

        assertTrue(store.isValid(AutomationId("m1"), token))
        assertFalse(store.isValid(AutomationId("m2"), token))
        assertFalse(store.isValid(AutomationId("unknown"), token))
        assertFalse(store.isValid(AutomationId("m1"), null))
        assertFalse(store.isValid(AutomationId("m1"), ""))
        assertFalse(store.isValid(AutomationId("m1"), token.uppercase()))
        assertFalse(store.isValid(AutomationId("m1"), token.dropLast(1)))
        assertFalse(store.isValid(AutomationId("m1"), token + "0"))
    }

    @Test
    fun `a forgotten token is gone and a new one is issued`() {
        val store = PrefsManualTokenStore(context)
        val old = store.tokenFor(AutomationId("m1"))

        store.forget(AutomationId("m1"))

        assertFalse(store.isValid(AutomationId("m1"), old))
        assertNotEquals(old, store.tokenFor(AutomationId("m1")))
    }

    @Test
    fun `a corrupted stored token is replaced instead of being trusted`() {
        context.getSharedPreferences("argus_manual_tokens", Context.MODE_PRIVATE)
            .edit().putString("token.m1", "short").commit()
        val store = PrefsManualTokenStore(context)

        assertFalse(store.isValid(AutomationId("m1"), "short"))
        val fresh = store.tokenFor(AutomationId("m1"))
        assertEquals(PrefsManualTokenStore.TOKEN_BYTES * 2, fresh.length)
        assertTrue(store.isValid(AutomationId("m1"), fresh))
    }

    // --- Regola del riquadro ---

    @Test
    fun `the tile rule is remembered across restarts and can be cleared`() {
        val selection = PrefsManualTileSelection(context)
        assertNull(selection.ruleId.value)

        selection.select("m1")
        assertEquals("m1", selection.ruleId.value)
        assertEquals("m1", PrefsManualTileSelection(context).ruleId.value)

        selection.select(null)
        assertNull(selection.ruleId.value)
        assertNull(PrefsManualTileSelection(context).ruleId.value)

        selection.select("   ")
        assertNull(selection.ruleId.value)
    }

    // --- Intent della scorciatoia e del widget ---

    @Test
    fun `the run intent is explicit, stays in this package and carries rule and token`() {
        val intent = ManualIntents.runIntent(context, "m1", "tok")

        assertEquals(ManualIntents.ACTION_RUN, intent.action)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(ManualIntents.RUN_ACTIVITY_CLASS, intent.component?.className)
        assertEquals("m1", intent.getStringExtra(ManualIntents.EXTRA_RULE_ID))
        assertEquals("tok", intent.getStringExtra(ManualIntents.EXTRA_TOKEN))
    }
}
