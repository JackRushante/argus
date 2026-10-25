package dev.argus.automation.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WakeAwarePauseTest {
    private val system = RecordingPowerLock()
    private val resumeSystem = RecordingPowerLock()
    private val wakeLock = ExecutionWakeLock(system)
    private val alarms = FakeWakeAlarms()
    private val signals = WaitWakeSignals(resumeSystem)
    private var elapsed = 10_000L
    private var tokens = 0L

    private fun newPause() = WakeAwarePause(
        wakeLock = wakeLock,
        alarms = alarms,
        signals = signals,
        elapsedRealtime = { elapsed },
        nextToken = { ++tokens },
    )

    @Test
    fun `a thirty second wait is a plain delay with no alarm and an untouched counter`() = runTest {
        val pause = newPause()
        var finished = false

        launch {
            wakeLock.holding {
                pause.pause(30_000)
                finished = true
            }
        }
        runCurrent()

        assertEquals(1, wakeLock.activeHolders)
        assertEquals(emptyList(), alarms.scheduled)
        advanceTimeBy(29_999)
        runCurrent()
        assertFalse(finished)
        assertEquals(1, wakeLock.activeHolders)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(finished)
        assertEquals(emptyList(), alarms.scheduled)
        assertEquals(0, wakeLock.activeHolders)
    }

    @Test
    fun `a five minute wait gives up the wakelock and resumes on the alarm signal`() = runTest {
        val pause = newPause()
        var finished = false
        val start = elapsed

        launch {
            wakeLock.holding {
                pause.pause(300_000)
                finished = true
            }
        }
        runCurrent()

        // Durante l'attesa la quota è rilasciata: il processore può dormire.
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(listOf(1L to start + 300_000), alarms.scheduled)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}", "release"), system.events)

        // Il telefono ha dormito: scatta la sveglia, il `delay` (uptime) non è passato.
        elapsed = start + 300_000
        assertTrue(signals.wakeUp(1L))
        runCurrent()

        assertTrue(finished)
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(listOf(1L), alarms.cancelled)
        assertEquals(
            listOf(
                "acquire:${70 * 60 * 1_000L}",
                "release",
                "acquire:${70 * 60 * 1_000L}",
                "release",
            ),
            system.events,
        )
        // Il receiver ha tenuto sveglio il telefono per la ripresa con un lock breve.
        assertEquals(listOf("acquire:${WaitWakeSignals.RESUME_HOLD_MILLIS}"), resumeSystem.events)
        assertTrue(testScheduler.currentTime < 300_000, "la ripresa viene dalla sveglia, non dal delay")
    }

    @Test
    fun `without a signal the delay of the same duration ends the wait`() = runTest {
        val pause = newPause()
        var finished = false
        val start = elapsed

        launch {
            wakeLock.holding {
                pause.pause(120_000)
                finished = true
            }
        }
        runCurrent()
        assertEquals(0, wakeLock.activeHolders)

        // Il telefono è rimasto sveglio: il tempo di rete avanza insieme al delay.
        elapsed = start + 120_000
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(finished)
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(listOf(1L), alarms.cancelled)
    }

    @Test
    fun `a wakeup before the deadline waits for the remaining milliseconds`() = runTest {
        val pause = newPause()
        var finished = false
        val start = elapsed

        launch {
            wakeLock.holding {
                pause.pause(300_000)
                finished = true
            }
        }
        runCurrent()

        elapsed = start + 295_000
        signals.wakeUp(1L)
        runCurrent()
        assertFalse(finished, "mancano ancora 5 secondi secondo elapsedRealtime")
        // La quota è già stata riacquisita per aspettare il resto.
        assertEquals(1, wakeLock.activeHolders)

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(finished)
        assertEquals(0, wakeLock.activeHolders)
    }

    @Test
    fun `cancelling the wait cancels the alarm and keeps the counter balanced`() = runTest {
        val pause = newPause()

        val job = launch {
            wakeLock.holding { pause.pause(300_000) }
        }
        runCurrent()
        assertEquals(0, wakeLock.activeHolders)

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(listOf(1L), alarms.cancelled)
        assertEquals(0, wakeLock.activeHolders)
        assertEquals("release", system.events.last())
        // Una sveglia tardiva dello stesso token non trova più nessuno.
        assertFalse(signals.wakeUp(1L))
    }

    @Test
    fun `two programs in parallel release the wakelock only when both are done`() = runTest {
        val pause = newPause()
        var firstDone = false
        var secondDone = false

        launch { wakeLock.holding { pause.pause(30_000); firstDone = true } }
        launch { wakeLock.holding { pause.pause(50_000); secondDone = true } }
        runCurrent()
        assertEquals(2, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}"), system.events)

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(firstDone)
        assertFalse(secondDone)
        assertEquals(1, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}"), system.events)

        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(secondDone)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}", "release"), system.events)
    }

    @Test
    fun `a long wait of one program does not release the lock another program still needs`() = runTest {
        val pause = newPause()
        val start = elapsed
        var shortDone = false
        var longDone = false

        launch { wakeLock.holding { pause.pause(30_000); shortDone = true } }
        launch { wakeLock.holding { pause.pause(300_000); longDone = true } }
        runCurrent()
        // Il programma lungo ha ceduto la sua quota, quello breve no: il lock resta tenuto.
        assertEquals(1, wakeLock.activeHolders)
        assertEquals(listOf("acquire:${70 * 60 * 1_000L}"), system.events)

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(shortDone)
        assertEquals(0, wakeLock.activeHolders)

        elapsed = start + 300_000
        signals.wakeUp(1L)
        runCurrent()
        assertTrue(longDone)
        assertEquals(0, wakeLock.activeHolders)
    }

    @Test
    fun `an unknown token does nothing`() = runTest {
        assertFalse(signals.wakeUp(999L))

        assertEquals(emptyList(), resumeSystem.events)
    }

    @Test
    fun `a failing alarm backend degrades to the plain delay instead of failing the wait`() = runTest {
        val pause = newPause()
        alarms.failSchedule = true
        var finished = false

        launch {
            wakeLock.holding {
                pause.pause(120_000)
                finished = true
            }
        }
        runCurrent()
        elapsed += 120_000
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(finished)
        assertEquals(0, wakeLock.activeHolders)
    }

    @Test
    fun `a wait outside any dispatch never unbalances the counter`() = runTest {
        val pause = newPause()
        var finished = false
        val start = elapsed

        launch {
            pause.pause(300_000)
            finished = true
        }
        runCurrent()
        elapsed = start + 300_000
        signals.wakeUp(1L)
        runCurrent()

        assertTrue(finished)
        assertEquals(0, wakeLock.activeHolders)
        assertEquals(emptyList(), system.events)
    }

    private class FakeWakeAlarms : WakeAlarmBackend {
        val scheduled = mutableListOf<Pair<Long, Long>>()
        val cancelled = mutableListOf<Long>()
        var failSchedule = false

        override fun schedule(token: Long, triggerAtElapsedMillis: Long) {
            if (failSchedule) error("forced alarm failure")
            scheduled += token to triggerAtElapsedMillis
        }

        override fun cancel(token: Long) {
            cancelled += token
        }
    }
}
