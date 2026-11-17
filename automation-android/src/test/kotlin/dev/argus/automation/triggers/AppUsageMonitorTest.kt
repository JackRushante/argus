package dev.argus.automation.triggers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import dev.argus.automation.connectivity.ConnectivitySentinelCoordinator
import dev.argus.device.ForegroundFromUsage
import dev.argus.device.UsageActivityEvent
import dev.argus.device.UsageEventsSource
import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: trigger «tempo d'uso di un'app», calcolo puro, monitor con orologio finto e stato persistito. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUsageMonitorTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"
    private val dayStart = Instant.parse("2026-11-15T23:00:00Z").toEpochMilli() // mezzanotte a Roma
    private fun at(minutes: Long) = dayStart + minutes * 60_000L

    private fun resumed(pkg: String, at: Long, cls: String = "Main") =
        UsageActivityEvent(pkg, cls, ForegroundFromUsage.RESUMED, at)

    private fun paused(pkg: String, at: Long, cls: String = "Main") =
        UsageActivityEvent(pkg, cls, ForegroundFromUsage.PAUSED, at)

    @Test
    fun `foreground time sums today's sessions and ignores screen changes inside the app`() {
        val events = listOf(
            // Aperta prima di mezzanotte: conta da mezzanotte.
            resumed(youtube, at(-30)),
            paused(youtube, at(10)),
            resumed(youtube, at(60)),
            // Cambio di schermata: nessuna interruzione.
            resumed(youtube, at(70), "Player"),
            paused(youtube, at(70), "Main"),
            paused(youtube, at(80), "Player"),
            // Ancora aperta adesso.
            resumed(youtube, at(100)),
            resumed(chrome, at(0)),
            paused(chrome, at(5)),
        )
        val totals = ForegroundTime.millisToday(events, setOf(youtube), dayStart, at(105))
        assertEquals((10 + 20 + 5) * 60_000L, totals[youtube])
        assertNull(totals[chrome], "solo le app osservate")
    }

    private class FakeSource(var events: List<UsageActivityEvent>) : UsageEventsSource {
        var reads = 0
        override fun read(fromMillis: Long, toMillis: Long): List<UsageActivityEvent> {
            reads++
            return events.filter { it.timeMillis in fromMillis until toMillis }
        }
    }

    private class MemoryFiredStore : AppUsageFiredStore {
        val days = mutableMapOf<AutomationId, Pair<String, ApprovalFingerprint>>()
        override fun firedDay(id: AutomationId, fingerprint: ApprovalFingerprint) =
            days[id]?.takeIf { it.second == fingerprint }?.first
        override fun markFired(id: AutomationId, fingerprint: ApprovalFingerprint, day: String) {
            days[id] = day to fingerprint
        }
        override fun retainOnly(ids: Set<AutomationId>) {
            days.keys.retainAll(ids)
        }
    }

    private val fp = ApprovalFingerprint("a".repeat(64))

    @Test
    fun `a rule fires once a day when today's use reaches its threshold`() = runTest {
        val source = FakeSource(listOf(resumed(youtube, at(0))))
        var now = at(29)
        val store = MemoryFiredStore()
        val delivered = mutableListOf<TriggerEnvelope>()
        val parser = DeviceEventParser()
        val watches = listOf(
            AppUsageWatch(AutomationId("thirty"), fp, youtube, 30),
            AppUsageWatch(AutomationId("hour"), fp, youtube, 60),
        )
        val monitor = AppUsageMonitor(
            scope = backgroundScope,
            source = source,
            nowMillis = { now },
            zone = { zone },
            watches = { watches },
            store = store,
            emit = { pkg, previous, current, atMillis ->
                delivered += requireNotNull(parser.appUsage(pkg, previous, current, atMillis))
            },
        )

        monitor.checkOnce()
        assertTrue(delivered.isEmpty())

        now = at(31)
        monitor.checkOnce()
        assertEquals(listOf(TriggerEvent.AppUsageChanged(youtube, 29, 30)), delivered.map { it.event })
        // Solo la regola da 30 minuti combacia con quell'evento.
        val matcher = TriggerMatcher()
        assertTrue(matcher.matches(Trigger.AppUsage(youtube, 30), delivered.single().event))
        assertTrue(!matcher.matches(Trigger.AppUsage(youtube, 60), delivered.single().event))

        now = at(45)
        monitor.checkOnce()
        assertEquals(1, delivered.size, "una volta al giorno")

        now = at(61)
        monitor.checkOnce()
        assertEquals(TriggerEvent.AppUsageChanged(youtube, 59, 60), delivered.last().event)

        // Tutte scattate: il controllo non legge più nemmeno gli eventi.
        val readsBefore = source.reads
        now = at(90)
        monitor.checkOnce()
        assertEquals(readsBefore, source.reads)

        // Il giorno dopo si ricomincia.
        source.events = listOf(resumed(youtube, at(24 * 60)))
        now = at(24 * 60 + 31)
        monitor.checkOnce()
        assertEquals(TriggerEvent.AppUsageChanged(youtube, 29, 30), delivered.last().event)
        assertEquals(3, delivered.size)
        // Stesso scatto lo stesso giorno = stesso id (il motore deduplica).
        assertTrue(delivered[0].id != delivered[2].id)
    }

    @Test
    fun `a restart the same day does not fire again and an edited rule starts over`() = runTest {
        val source = FakeSource(listOf(resumed(youtube, at(0))))
        val store = MemoryFiredStore()
        val delivered = mutableListOf<Int>()
        var watch = AppUsageWatch(AutomationId("r"), fp, youtube, 10)
        fun monitor() = AppUsageMonitor(
            scope = backgroundScope,
            source = source,
            nowMillis = { at(20) },
            zone = { zone },
            watches = { listOf(watch) },
            store = store,
            emit = { _, _, current, _ -> delivered += current },
        )
        monitor().checkOnce()
        monitor().checkOnce()
        assertEquals(listOf(10), delivered)

        watch = watch.copy(fingerprint = ApprovalFingerprint("b".repeat(64)))
        monitor().checkOnce()
        assertEquals(listOf(10, 10), delivered)
    }

    @Test
    fun `the prefs store keeps the day per revision and forgets disarmed rules`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = PrefsAppUsageFiredStore(context)
        val id = AutomationId("rule/1")
        assertNull(store.firedDay(id, fp))
        store.markFired(id, fp, "2026-11-16")
        assertEquals("2026-11-16", store.firedDay(id, fp))
        assertNull(store.firedDay(id, ApprovalFingerprint("b".repeat(64))))
        store.retainOnly(emptySet())
        assertNull(store.firedDay(id, fp))
    }

    @Test
    fun `armed usage rules turn the sentinel on and publish their thresholds`() = runTest {
        val demand = DeviceEventDemand()
        val rule = Automation(
            id = AutomationId("u"),
            name = "u",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.AppUsage(youtube, 45),
            actions = listOf(Action.ShowNotification("Argus", "basta")),
            enabled = true,
            approvalFingerprint = fp,
        )
        val report = ConnectivitySentinelCoordinator(
            OneRuleStore(rule),
            object : ConnectivitySentinelBackend {
                override suspend fun start() = true
                override suspend fun stop() = true
            },
            deviceEvents = demand,
        ).reconcile()

        assertEquals(listOf(rule.id), report.requiredBy)
        assertEquals(setOf(DeviceEventSource.APP_USAGE), demand.sources.value)
        assertEquals(listOf(AppUsageWatch(rule.id, fp, youtube, 45)), demand.appUsage.value)
    }

    private class OneRuleStore(private val rule: Automation) : AutomationStore {
        override suspend fun get(id: AutomationId): Automation? = rule.takeIf { it.id == id }
        override suspend fun all(): List<Automation> = listOf(rule)
        override fun observeAll(): Flow<List<Automation>> = flowOf(listOf(rule))
        override suspend fun armed(): List<Automation> = listOf(rule)
        override suspend fun delete(id: AutomationId) = Unit
        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun markNeedsReview(id: AutomationId) = Unit
        override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.NotEligible
        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = null
    }
}
