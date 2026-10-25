package dev.argus.automation

import android.content.ClipData
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.runtime.ActionResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S7: la pulizia degli appunti sopravvive a background, schermo bloccato e morte del processo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardCleanupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val port = FakeClipboardPort()
    private val store = FakePendingCleanupStore()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private var processStart: (() -> Unit)? = null
    private var wallClock = 1_000_000L

    private val copier = AndroidClipboardCopier(
        context = context,
        expiryMillis = TTL,
        expiryScheduler = ClipboardExpiryScheduler { delay, block -> timers += delay to block },
        clipboard = port,
        cleanupStore = store,
        processStarts = ProcessStartSource { processStart = it },
        clock = { wallClock },
    )

    @Test
    fun `a write persists only the label and the wall clock expiry`() {
        assertEquals(ActionResult.Success, copier.copyLiteral("482913"))

        val pending = store.value
        assertEquals(PendingClipboardCleanup(port.label!!, wallClock + TTL), pending)
        assertTrue(pending!!.label.startsWith("argus:"))
        assertTrue("482913" !in pending.toString(), "il contenuto non va mai persistito")
        assertEquals(TTL, timers.single().first)
    }

    @Test
    fun `expiry on a readable clip clears it and drops the pending cleanup`() {
        copier.copyLiteral("482913")

        timers.single().second.invoke()

        assertNull(port.label)
        assertNull(store.value)
    }

    @Test
    fun `an unreadable clip at expiry keeps the pending cleanup and the next process start clears it`() {
        copier.copyLiteral("482913")
        port.readable = false

        // Background / telefono bloccato: la description non è leggibile, nulla viene cancellato.
        timers.single().second.invoke()
        assertEquals(0, port.cleared)
        assertEquals(port.label, store.value?.label)

        // L'app torna in primo piano oltre la scadenza: compare-and-clear sulla label di Argus.
        wallClock += TTL + 5_000
        port.readable = true
        processStart!!.invoke()

        assertNull(port.label)
        assertEquals(1, port.cleared)
        assertNull(store.value)
    }

    @Test
    fun `process start never clears a clip the user replaced and drops the pending cleanup`() {
        copier.copyLiteral("482913")
        port.readable = false
        timers.single().second.invoke()
        port.label = "user"
        port.readable = true
        wallClock += TTL + 1

        processStart!!.invoke()

        assertEquals("user", port.label)
        assertEquals(0, port.cleared)
        assertNull(store.value)
    }

    @Test
    fun `process start before the TTL reschedules the remaining time instead of clearing`() {
        copier.copyLiteral("482913")
        timers.clear() // il timer in-process è andato perso con il processo
        wallClock += 20_000

        processStart!!.invoke()

        assertEquals(0, port.cleared)
        assertEquals(TTL - 20_000, timers.single().first)
        wallClock += TTL
        timers.single().second.invoke()
        assertNull(port.label)
        assertNull(store.value)
    }

    @Test
    fun `process start retries a few times while the clip is still unreadable`() {
        copier.copyLiteral("482913")
        port.readable = false
        timers.clear()
        wallClock += TTL + 1

        processStart!!.invoke()
        // Tre ritenti ravvicinati (la finestra può non avere ancora il focus), poi si ferma.
        repeat(3) {
            assertEquals(1, timers.size, "ritento $it")
            timers.removeAt(0).second.invoke()
        }
        assertTrue(timers.isEmpty())
        assertEquals(0, port.cleared)
        assertTrue(store.value != null, "la pulizia resta in sospeso per il prossimo ON_START")

        port.readable = true
        processStart!!.invoke()
        assertNull(port.label)
        assertNull(store.value)
    }

    @Test
    fun `a pending cleanup older than a day is dropped without touching the clipboard`() {
        copier.copyLiteral("482913")
        wallClock += TTL + 25L * 60 * 60 * 1000

        processStart!!.invoke()

        assertEquals(0, port.cleared)
        assertNull(store.value)
    }

    @Test
    fun `an older expiry cannot clear or drop the cleanup of a newer Argus clip`() {
        copier.copyLiteral("primo")
        val firstLabel = port.label
        copier.copyLiteral("secondo")
        val secondLabel = port.label

        timers[0].second.invoke()

        assertEquals(secondLabel, port.label)
        assertEquals(0, port.cleared)
        assertEquals(secondLabel, store.value?.label)
        assertTrue(firstLabel != secondLabel)
    }

    @Test
    fun `without a pending cleanup process start does nothing`() {
        processStart!!.invoke()

        assertEquals(0, port.cleared)
        assertTrue(timers.isEmpty())
    }

    private companion object {
        const val TTL = 60_000L
    }
}

internal class FakeClipboardPort : ClipboardPort {
    var label: String? = null
    var readable = true
    var cleared = 0

    override fun write(clip: ClipData) {
        label = clip.description.label.toString()
    }

    override fun currentLabel(): String? = if (readable) label else null

    override fun clear() {
        label = null
        cleared += 1
    }
}

internal class FakePendingCleanupStore : PendingClipboardCleanupStore {
    var value: PendingClipboardCleanup? = null

    override fun save(pending: PendingClipboardCleanup) {
        value = pending
    }

    override fun load(): PendingClipboardCleanup? = value

    override fun clear(label: String) {
        if (value?.label == label) value = null
    }
}
