package dev.argus.automation.span

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SpanRecord
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.SpanValue
import dev.argus.engine.model.VolumeStream
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: l'intervallo in corso sopravvive a uno store nuovo (processo nuovo) ed è legato alla sua impronta. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSpanStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences(PrefsSpanStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val id = AutomationId("auto")
    private val fingerprint = ApprovalFingerprint("a".repeat(64))
    private val other = ApprovalFingerprint("b".repeat(64))
    private val volume = SpanRecord(SpanSlot.Volume(VolumeStream.MEDIA), SpanValue.Percent(30))

    @Before
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `a span and its values survive a fresh store`() = runTest {
        PrefsSpanStore(context).apply {
            val opened = open(id, fingerprint, 1_000)
            assertEquals(1_000, opened.startedAtMillis)
            assertTrue(record(id, fingerprint, volume.slot.key, volume))
            // Un valore illeggibile segna la chiave come vista, senza valore.
            assertFalse(record(id, fingerprint, SpanSlot.Dnd.key, null))
        }
        val reopened = PrefsSpanStore(context)
        val span = assertNotNull(reopened.get(id))
        assertEquals(fingerprint, span.approvalFingerprint)
        assertEquals(listOf(volume), span.records)
        assertEquals(setOf(volume.slot.key, SpanSlot.Dnd.key), span.handled)
        assertEquals(listOf(span), reopened.all())
    }

    @Test
    fun `a key is recorded once per span and only for its approval`() = runTest {
        val store = PrefsSpanStore(context)
        store.open(id, fingerprint, 1_000)
        assertTrue(store.record(id, fingerprint, volume.slot.key, volume))
        assertFalse(store.record(id, fingerprint, volume.slot.key, volume.copy(value = SpanValue.Percent(90))))
        assertFalse(
            store.record(id, other, SpanSlot.Dnd.key, SpanRecord(SpanSlot.Dnd, SpanValue.Dnd(DndMode.OFF))),
        )
        // Un secondo inizio non riapre: resta l'intervallo di prima, coi suoi valori.
        assertEquals(1_000, store.open(id, fingerprint, 5_000).startedAtMillis)
        assertEquals(listOf(volume), store.get(id)?.records)

        assertFalse(store.close(id, other))
        assertTrue(store.close(id, fingerprint))
        assertNull(PrefsSpanStore(context).get(id))
    }

    @Test
    fun `a damaged record is dropped, never restored`() = runTest {
        preferences.edit().putString("span.${id.value}", "{not json").commit()
        val store = PrefsSpanStore(context)
        assertNull(store.get(id))
        assertTrue(store.all().isEmpty())
        assertFalse(preferences.contains("span.${id.value}"))
    }

    @Test
    fun `the end actions have their own pause, kept after the span closes`() = runTest {
        val store = PrefsSpanStore(context)
        assertNull(store.claimExitCooldown(id, 10_000, 60_000))
        assertEquals(70_000, store.claimExitCooldown(id, 20_000, 60_000))
        assertNull(PrefsSpanStore(context).claimExitCooldown(id, 70_000, 60_000))
        // Senza pausa passa sempre.
        assertNull(store.claimExitCooldown(id, 70_001, 0))
    }
}
