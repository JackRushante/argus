package dev.argus.automation.health

import dev.argus.automation.BlockCause
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityHealthMonitorTest {
    private val minute = 60_000L
    private var now = 1_000_000L
    private var day = 50L
    private var shizukuUp = true
    private val rules = mutableListOf(rule("wifi-1"), rule("wifi-2"))
    private val rechecks = mutableListOf<Long>()
    private val store = FakeHealthAlertStore()
    private val notifier = RecordingHealthNotifier()
    private val monitor = CapabilityHealthMonitor(
        armedRules = { rules.toList() },
        snapshot = { required ->
            CapabilityHealthSnapshot(
                causes = if (shizukuUp) emptyMap() else required.associateWith { BlockCause.SHIZUKU_NOT_RUNNING },
                exactAlarmsGranted = true,
            )
        },
        store = store,
        notifier = notifier,
        nowMillis = { now },
        today = { day },
        scheduleRecheck = { rechecks += it },
        graceMillis = 3 * minute,
    )

    @Test
    fun `a loss is notified once with the number of affected rules after the grace period`() = runTest {
        monitor.check()
        assertTrue(notifier.lost.isEmpty())

        shizukuUp = false
        monitor.check()
        assertTrue(notifier.lost.isEmpty(), "appena perso: si aspetta (Shizuku può arrivare dopo il riavvio)")
        assertEquals(listOf(3 * minute), rechecks)

        now += 3 * minute
        monitor.check()
        assertEquals(listOf(HealthCapability.PRIVILEGED_BACKEND to 2), notifier.lost)

        now += 10 * minute
        monitor.check()
        assertEquals(1, notifier.lost.size, "la stessa perdita non si ripete")
    }

    @Test
    fun `a loss that recovers within the grace period is never notified`() = runTest {
        shizukuUp = false
        monitor.check()
        shizukuUp = true
        now += minute
        monitor.check()
        now += 5 * minute
        monitor.check()
        assertTrue(notifier.lost.isEmpty())
    }

    @Test
    fun `recovery clears the notification and a later loss counts again, at most once a day`() = runTest {
        shizukuUp = false
        monitor.check()
        now += 3 * minute
        monitor.check()
        assertEquals(1, notifier.lost.size)

        shizukuUp = true
        monitor.check()
        assertEquals(listOf(HealthCapability.PRIVILEGED_BACKEND), notifier.clearedCapabilities)

        // Persa di nuovo lo stesso giorno: registrata ma non notificata una seconda volta.
        shizukuUp = false
        monitor.check()
        now += 3 * minute
        monitor.check()
        assertEquals(1, notifier.lost.size)

        // Torna e si riperde il giorno dopo: nuovo avviso.
        shizukuUp = true
        monitor.check()
        day += 1
        shizukuUp = false
        monitor.check()
        now += 3 * minute
        monitor.check()
        assertEquals(2, notifier.lost.size)
    }

    @Test
    fun `no armed rule needing it means no alert`() = runTest {
        rules.clear()
        rules += rule("vibrate", Action.Vibrate(100)).copy(requiredCapabilities = emptySet())
        shizukuUp = false
        monitor.check()
        now += 5 * minute
        monitor.check()
        assertTrue(notifier.lost.isEmpty())
        assertTrue(rechecks.isEmpty())
    }

    @Test
    fun `switched off means no alert`() = runTest {
        store.setEnabled(false)
        shizukuUp = false
        monitor.check()
        now += 5 * minute
        monitor.check()
        assertTrue(notifier.lost.isEmpty())
    }

    @Test
    fun `only one recheck is pending at a time`() = runTest {
        shizukuUp = false
        monitor.check()
        now += minute
        monitor.check()
        assertEquals(listOf(3 * minute), rechecks, "il reconcile intermedio non aggiunge ricontrolli")

        now += 2 * minute
        monitor.check()
        assertEquals(1, notifier.lost.size)
        assertEquals(1, rechecks.size)
    }

    private fun rule(id: String, action: Action = Action.SetWifi(false)) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = Trigger.Manual,
        actions = listOf(action),
    )
}
