package dev.argus.automation.triggers

import dev.argus.device.ForegroundFromUsage
import dev.argus.device.UsageActivityEvent
import dev.argus.device.UsageEventsSource
import dev.argus.engine.model.AppEventKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lettura degli eventi di utilizzo (0.5.1, F15) con un orologio virtuale e una sorgente finta: transizioni,
 * doppioni fra letture vicine, pausa a schermo spento, ripresa, ordine di consegna.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppEventPollerTest {
    private val base = 1_000_000_000L
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"
    private val launcher = "com.android.launcher"

    private class FakeSource : UsageEventsSource {
        val events = mutableListOf<UsageActivityEvent>()
        var available = true
        val reads = mutableListOf<Pair<Long, Long>>()

        override fun read(fromMillis: Long, toMillis: Long): List<UsageActivityEvent>? {
            reads += fromMillis to toMillis
            if (!available) return null
            return events.filter { it.timeMillis in fromMillis until toMillis }.sortedBy { it.timeMillis }
        }
    }

    private val delivered = mutableListOf<AppTransition>()

    private fun resumed(pkg: String, at: Long) = UsageActivityEvent(pkg, "Main", ForegroundFromUsage.RESUMED, base + at)
    private fun paused(pkg: String, at: Long) = UsageActivityEvent(pkg, "Main", ForegroundFromUsage.PAUSED, base + at)
    private fun opened(pkg: String, at: Long) = AppTransition(pkg, AppEventKind.OPENED, base + at)
    private fun closed(pkg: String, at: Long) = AppTransition(pkg, AppEventKind.CLOSED, base + at)

    private fun TestScope.poller(
        source: FakeSource,
        watched: () -> Set<String> = { setOf(youtube, chrome, launcher) },
        emit: suspend (AppTransition) -> Unit = { delivered += it },
    ) = AppEventPoller(
        scope = this,
        source = source,
        nowMillis = { base + testScheduler.currentTime },
        watched = watched,
        emit = emit,
    )

    private fun TestScope.pass(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `what is already open when the reading starts is only a starting point`() = runTest {
        val source = FakeSource()
        source.events += resumed(youtube, -60_000)
        val poller = poller(source)

        poller.start(screenOn = true)
        runCurrent()
        pass(10_000)

        assertEquals(emptyList(), delivered)

        // Da qui contano solo le novità: YouTube viene lasciato per Chrome.
        source.events += paused(youtube, 12_000)
        source.events += resumed(chrome, 12_040)
        pass(6_000)

        assertEquals(listOf(closed(youtube, 12_000), opened(chrome, 12_040)), delivered)
        poller.close()
    }

    @Test
    fun `an app opened a while ago is known from the longer window and its close is delivered`() = runTest {
        val source = FakeSource()
        // Nessun evento negli ultimi 10 minuti, ma YouTube è in primo piano da un'ora.
        source.events += resumed(youtube, -3_600_000)
        val poller = poller(source)

        poller.start(screenOn = true)
        runCurrent()
        source.events += paused(youtube, 4_000)
        source.events += resumed(launcher, 4_050)
        pass(8_000)

        assertEquals(listOf(closed(youtube, 4_000), opened(launcher, 4_050)), delivered)
        poller.close()
    }

    @Test
    fun `the same transition seen by overlapping readings is delivered once`() = runTest {
        val source = FakeSource()
        val poller = poller(source)
        poller.start(screenOn = true)
        runCurrent()

        source.events += resumed(youtube, 1_000)
        // Una decina di letture: la finestra di ogni lettura si sovrappone alla precedente.
        pass(30_000)

        assertEquals(listOf(opened(youtube, 1_000)), delivered)
        assertTrue(source.reads.size >= 10, "letture: ${source.reads.size}")
        // Ogni lettura riparte da poco prima della fine della precedente, mai da zero.
        source.reads.zipWithNext().drop(1).forEach { (previous, next) ->
            assertEquals(previous.second - AppEventPoller.LOOKBACK_MILLIS, next.first)
        }
        poller.close()
    }

    @Test
    fun `reading stops with the screen off, the close is collected, and nothing is lost on return`() = runTest {
        val source = FakeSource()
        source.events += resumed(youtube, -1_000)
        val poller = poller(source)
        poller.start(screenOn = true)
        runCurrent()
        pass(2_500)

        // Si spegne lo schermo: YouTube va in pausa poco dopo.
        poller.onScreenOff()
        source.events += paused(youtube, 2_700)
        pass(AppEventPoller.FINAL_READ_DELAY_MILLIS)
        assertEquals(listOf(closed(youtube, 2_700)), delivered)

        // Per tutto il tempo a schermo spento non si legge più.
        val readsWhenOff = source.reads.size
        pass(595_000)
        assertEquals(readsWhenOff, source.reads.size, "letture a schermo spento")

        // Si riaccende lo schermo e YouTube riprende: l'apertura arriva una volta sola.
        source.events += resumed(youtube, 600_000)
        poller.onScreenOn()
        runCurrent()
        pass(10_000)

        assertEquals(listOf(closed(youtube, 2_700), opened(youtube, 600_000)), delivered)
        poller.close()
    }

    @Test
    fun `events that happened while the screen was off come out in order on the next reading`() = runTest {
        val source = FakeSource()
        val poller = poller(source)
        poller.start(screenOn = false)
        runCurrent()
        poller.onScreenOn()
        runCurrent()
        pass(2_500)
        poller.onScreenOff()
        pass(AppEventPoller.FINAL_READ_DELAY_MILLIS + 1_000)

        // Una chiamata sveglia lo schermo per un attimo: Chrome si apre e si chiude da solo.
        source.events += resumed(chrome, 30_000)
        source.events += paused(chrome, 31_000)
        source.events += resumed(launcher, 31_020)
        pass(60_000)
        assertEquals(emptyList(), delivered, "a schermo spento nessuna lettura")

        poller.onScreenOn()
        runCurrent()
        pass(5_000)

        assertEquals(
            listOf(opened(chrome, 30_000), closed(chrome, 31_000), opened(launcher, 31_020)),
            delivered,
        )
        poller.close()
    }

    @Test
    fun `only the apps with an armed rule are delivered`() = runTest {
        val source = FakeSource()
        val poller = poller(source, watched = { setOf(youtube) })
        poller.start(screenOn = true)
        runCurrent()

        source.events += resumed(chrome, 1_000)
        source.events += paused(chrome, 3_000)
        source.events += resumed(youtube, 3_050)
        source.events += paused(youtube, 8_000)
        source.events += resumed(launcher, 8_040)
        pass(15_000)

        assertEquals(listOf(opened(youtube, 3_050), closed(youtube, 8_000)), delivered)
        poller.close()
    }

    @Test
    fun `the watched apps are read at delivery time, so a new rule takes effect at once`() = runTest {
        val source = FakeSource()
        var watched = setOf(chrome)
        val poller = poller(source, watched = { watched })
        poller.start(screenOn = true)
        runCurrent()

        source.events += resumed(youtube, 1_000)
        pass(4_000)
        assertEquals(emptyList(), delivered)

        watched = setOf(chrome, youtube)
        source.events += paused(youtube, 6_000)
        source.events += resumed(launcher, 6_040)
        pass(5_000)

        assertEquals(listOf(closed(youtube, 6_000)), delivered)
        poller.close()
    }

    @Test
    fun `an unreadable source loses nothing, the readings resume from where they stopped`() = runTest {
        val source = FakeSource()
        val poller = poller(source)
        poller.start(screenOn = true)
        runCurrent()
        pass(5_000)

        // L'accesso ai dati di utilizzo sparisce per un po'; intanto YouTube si apre e si chiude.
        source.available = false
        source.events += resumed(youtube, 6_000)
        source.events += paused(youtube, 20_000)
        source.events += resumed(launcher, 20_040)
        pass(30_000)
        assertEquals(emptyList(), delivered)

        source.available = true
        pass(10_000)

        assertEquals(
            listOf(opened(youtube, 6_000), closed(youtube, 20_000), opened(launcher, 20_040)),
            delivered,
        )
        poller.close()
    }

    @Test
    fun `after a very long pause the old state is dropped and nothing stale is delivered`() = runTest {
        val source = FakeSource()
        source.events += resumed(youtube, -1_000)
        val poller = poller(source)
        poller.start(screenOn = true)
        runCurrent()
        poller.onScreenOff()
        pass(AppEventPoller.FINAL_READ_DELAY_MILLIS)

        // Tredici ore dopo: nel frattempo Chrome è stato aperto e chiuso, ma l'intervallo è troppo lungo
        // per fidarsi dello stato noto. Quello che c'è già è solo un nuovo punto di partenza.
        val wake = 13L * 60 * 60 * 1_000
        source.events += paused(youtube, 3_000)
        source.events += resumed(chrome, wake - 5_000)
        advanceTimeBy(wake - AppEventPoller.FINAL_READ_DELAY_MILLIS)
        poller.onScreenOn()
        runCurrent()
        assertEquals(emptyList(), delivered)

        source.events += paused(chrome, wake + 4_000)
        source.events += resumed(launcher, wake + 4_040)
        pass(8_000)

        assertEquals(listOf(closed(chrome, wake + 4_000), opened(launcher, wake + 4_040)), delivered)
        poller.close()
    }

    @Test
    fun `a slow delivery does not stop the readings and keeps the order`() = runTest {
        val source = FakeSource()
        val slow = mutableListOf<AppTransition>()
        val poller = poller(source) { transition ->
            delay(20_000)
            slow += transition
        }
        poller.start(screenOn = true)
        runCurrent()

        source.events += resumed(youtube, 1_000)
        source.events += paused(youtube, 3_000)
        source.events += resumed(chrome, 3_040)
        pass(8_000)
        val readsDuringDelivery = source.reads.size
        pass(10_000)
        assertTrue(source.reads.size > readsDuringDelivery, "la lettura non deve aspettare la consegna")

        pass(120_000)
        assertEquals(
            listOf(opened(youtube, 1_000), closed(youtube, 3_000), opened(chrome, 3_040)),
            slow,
        )
        poller.close()
    }

    @Test
    fun `closing the poller stops the readings for good`() = runTest {
        val source = FakeSource()
        val poller = poller(source)
        poller.start(screenOn = true)
        runCurrent()
        pass(5_000)
        poller.close()

        val reads = source.reads.size
        source.events += resumed(youtube, 6_000)
        pass(60_000)
        poller.onScreenOn()
        pass(60_000)

        assertEquals(reads, source.reads.size)
        assertEquals(emptyList(), delivered)
    }
}
