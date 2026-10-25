package dev.argus.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.geofence.GeofenceReconcileReport
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.automation.phone.CallStateSnapshot
import dev.argus.automation.phone.CallStateStore
import dev.argus.automation.phone.PhoneEventIngress
import dev.argus.automation.sensor.SensorReconcileReport
import dev.argus.automation.sensor.SensorTriggerRuntime
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomJournalMaintenance
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ciclo di vita del runtime (0.3.6): recuperi in background, regole interrotte, sensori. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArgusRuntimeControllerLifecycleTest {
    @Test
    fun `app start does not wait for a long call recovery and does not redeliver it`() = withFixture { env ->
        val phoneEnvelope = requireNotNull(
            PhoneEventParser().parse(PhoneEvent.INCOMING_CALL, "+393201234567", smsText = null, atMillis = 100),
        )
        val state = PendingCall(phoneEnvelope)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val delivered = CopyOnWriteArrayList<TriggerEnvelope>()
        val geofence = CountingGeofence()
        val sensor = CountingSensor()
        val controller = env.controller(
            geofence = geofence,
            sensor = sensor,
            phoneIngress = PhoneEventIngress(PhoneEventParser(), state) {
                entered.complete(Unit)
                gate.await()
                delivered += it
            },
        )

        // Il dispatcher resta sospeso per tutto il test: il reconcile deve comunque terminare.
        withTimeout(5_000) { controller.reconcile(ReconcileReason.APP_START) }
        withTimeout(5_000) { entered.await() }

        assertTrue(geofence.calls.get() >= 1, "geofence riconciliato mentre il recupero è sospeso")
        assertTrue(sensor.calls.get() >= 1, "sensori riconciliati mentre il recupero è sospeso")
        assertEquals(emptyList(), delivered.toList())

        // Un secondo reconcile APP_START non riconsegna lo stesso evento e non si accoda dietro.
        withTimeout(5_000) { controller.reconcile(ReconcileReason.APP_START) }
        gate.complete(Unit)
        awaitUntil("recupero concluso") { delivered.size == 1 && state.pending() == null }
        delay(200)
        assertEquals(listOf(phoneEnvelope), delivered.toList())
    }

    @Test
    fun `app start does not wait for a long time rule recovery`() = withFixture { env ->
        val rule = timeRule()
        val dueAt = NOW.minusSeconds(3_600).toEpochMilli()
        val rules = FakeRules(listOf(rule))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val dispatched = CopyOnWriteArrayList<TriggerEnvelope>()
        val coordinator = TimeAlarmCoordinator(
            store = rules,
            state = FakeAlarmState(
                ScheduledTimeAlarm(
                    automationId = rule.id,
                    approvalFingerprint = requireNotNull(rule.approvalFingerprint),
                    eventAtMillis = dueAt,
                    wakeAtMillis = dueAt,
                    requestedPrecision = TimePrecision.FLEXIBLE,
                    scheduledMode = ScheduledAlarmMode.INEXACT,
                    updatedAtMillis = dueAt,
                ),
            ),
            backend = FakeAlarmBackend(),
            dispatcher = TimeEventDispatcher {
                entered.complete(Unit)
                gate.await()
                dispatched += it
            },
            now = { NOW },
            recoveryLauncher = RecoveryLauncher { block -> env.scope.launch { block() } },
        )
        val geofence = CountingGeofence()
        val sensor = CountingSensor()
        val controller = env.controller(
            scheduler = CoordinatorTimeAlarmRuntime(coordinator),
            geofence = geofence,
            sensor = sensor,
        )

        withTimeout(5_000) { controller.reconcile(ReconcileReason.APP_START) }
        withTimeout(5_000) { entered.await() }
        assertTrue(geofence.calls.get() >= 1)
        assertTrue(sensor.calls.get() >= 1)

        // Altri reconcile non restano in coda dietro al programma lungo né lo riconsegnano.
        withTimeout(5_000) { controller.reconcile(ReconcileReason.CAPABILITY_CHANGED) }
        gate.complete(Unit)
        awaitUntil("recupero concluso") { dispatched.size == 1 }
        delay(200)
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `app start disarms an armed immediate rule left half executed without rerunning it`() = withFixture { env ->
        val interrupted = immediateRule("interrupted")
        val untouched = immediateRule("never-fired")
        val rules = FakeRules(listOf(interrupted, untouched), lastFired = mapOf(interrupted.id to 500L))
        val audit = RecordingAudit()
        val controller = env.controller(store = rules, audit = audit)

        controller.reconcile(ReconcileReason.APP_START)

        assertEquals(AutomationStatus.DISABLED, rules.get(interrupted.id)?.status)
        assertEquals(AutomationStatus.ARMED, rules.get(untouched.id)?.status)
        val event = audit.events.single()
        assertEquals(interrupted.id, event.automationId)
        assertEquals(AuditKind.ERROR, event.kind)
        assertEquals("execution_interrupted", event.detail)
    }

    @Test
    fun `an immediate rule fired by this very process is not touched by the app start sweep`() = withFixture { env ->
        val running = immediateRule("running-now")
        val rules = FakeRules(listOf(running), lastFired = mapOf(running.id to 1_000L))
        val audit = RecordingAudit()
        val scheduler = CountingScheduler()
        val controller = env.controller(scheduler = scheduler, store = rules, audit = audit)

        controller.start()
        awaitUntil("bootstrap APP_START") { scheduler.calls.get() >= 1 }

        assertEquals(AutomationStatus.ARMED, rules.get(running.id)?.status)
        assertEquals(emptyList(), audit.events.toList())
    }

    @Test
    fun `removing the last sensor rule reconciles the sensor runtime`() = withFixture { env ->
        val sensorRule = sensorRule()
        val rules = FakeRules(listOf(sensorRule))
        val sensor = CountingSensor()
        val scheduler = CountingScheduler()
        val controller = env.controller(scheduler = scheduler, sensor = sensor, store = rules)

        controller.start()
        awaitUntil("bootstrap sensori") { sensor.calls.get() >= 1 }
        // Lascia al collector dello store il tempo di leggere lo stato iniziale (che scarta).
        delay(300)
        val before = sensor.calls.get()

        rules.remove(sensorRule.id)

        awaitUntil("sensore riconciliato dopo l'eliminazione") { sensor.calls.get() > before }
    }

    private fun withFixture(body: suspend (Env) -> Unit) {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = ArgusDatabase.inMemory(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                body(Env(scope, db))
            } finally {
                scope.cancel()
                db.close()
            }
        }
    }

    private class Env(val scope: CoroutineScope, private val db: ArgusDatabase) {
        fun controller(
            scheduler: TimeAlarmRuntime = CountingScheduler(),
            geofence: GeofenceTriggerRuntime = CountingGeofence(),
            sensor: SensorTriggerRuntime = CountingSensor(),
            phoneIngress: PhoneEventIngress? = null,
            store: AutomationStore? = null,
            audit: AuditSink = RecordingAudit(),
        ) = ArgusRuntimeController(
            scope = scope,
            scheduler = scheduler,
            capabilities = CapabilityReconciler(
                FakeRules(emptyList()),
                FirePolicySnapshotProvider { FirePolicySnapshot(emptySet(), emptySet(), emptySet()) },
            ),
            maintenance = RoomJournalMaintenance(db),
            shizukuStatus = MutableStateFlow(ShizukuGatewayStatus.AUTHORIZED),
            preferences = StaticPreferences(),
            replyRegistry = ActiveNotificationReplyRegistry(),
            geofence = geofence,
            phoneIngress = phoneIngress,
            sensor = sensor,
            nowMillis = { 1_000L },
            store = store,
            audit = audit,
        )
    }

    private suspend fun awaitUntil(label: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(10) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timeout in attesa di: $label", error)
        }
    }

    private fun timeRule(): Automation = signed(
        Automation(
            id = AutomationId("time-rule"),
            name = "t",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 9 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = true,
        ),
    )

    private fun immediateRule(id: String): Automation = signed(
        Automation(
            id = AutomationId(id),
            name = "i",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Immediate,
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = true,
        ),
    )

    private fun sensorRule(): Automation = signed(
        Automation(
            id = AutomationId("sensor-rule"),
            name = "s",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Sensor(kind = SensorKind.SIGNIFICANT_MOTION),
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = true,
        ),
    )

    private fun signed(unsigned: Automation): Automation =
        unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))

    private class CountingScheduler : TimeAlarmRuntime {
        val calls = AtomicInteger()
        override suspend fun onAlarm(
            automationId: AutomationId,
            approvalFingerprint: ApprovalFingerprint,
            eventAtMillis: Long,
        ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

        override suspend fun reconcile(reason: ReconcileReason): ReconcileReport {
            calls.incrementAndGet()
            return ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private class CountingGeofence : GeofenceTriggerRuntime {
        val calls = AtomicInteger()
        override suspend fun reconcile(recreateOsRegistrations: Boolean): GeofenceReconcileReport {
            calls.incrementAndGet()
            return GeofenceReconcileReport()
        }
    }

    private class CountingSensor : SensorTriggerRuntime {
        val calls = AtomicInteger()
        override suspend fun reconcile(): SensorReconcileReport {
            calls.incrementAndGet()
            return SensorReconcileReport(emptyList(), emptySet())
        }
    }

    private class RecordingAudit : AuditSink {
        val events = CopyOnWriteArrayList<AuditEvent>()
        override suspend fun record(e: AuditEvent) {
            events += e
        }
    }

    private class StaticPreferences : AppPreferencesStore {
        private val state = MutableStateFlow(AppPreferences(privacyAccepted = true, onboardingCompleted = true))
        override fun observe(): StateFlow<AppPreferences> = state.asStateFlow()
        override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean = true
        override suspend fun setOnboardingCompleted(completed: Boolean): Boolean = false
    }

    private class PendingCall(initial: TriggerEnvelope) : CallStateStore {
        private val pending = java.util.concurrent.atomic.AtomicReference<TriggerEnvelope?>(initial)
        override fun last(): CallStateSnapshot? = null
        override fun pending(): TriggerEnvelope? = pending.get()
        override fun record(snapshot: CallStateSnapshot, pending: TriggerEnvelope?) {
            this.pending.set(pending)
        }

        override fun complete(eventId: String) {
            pending.set(null)
        }
    }

    private class FakeAlarmState(vararg initial: ScheduledTimeAlarm) : TimeAlarmStateStore {
        private val values = initial.associateByTo(linkedMapOf()) { it.automationId }
        override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = values[automationId]
        override suspend fun all(): List<ScheduledTimeAlarm> = values.values.toList()
        override suspend fun upsert(alarm: ScheduledTimeAlarm) {
            values[alarm.automationId] = alarm
        }

        override suspend fun delete(automationId: AutomationId) {
            values.remove(automationId)
        }
    }

    private class FakeAlarmBackend : TimeAlarmBackend {
        override fun canScheduleExact(): Boolean = true
        override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode =
            ScheduledAlarmMode.INEXACT

        override fun cancel(automationId: AutomationId) = Unit
    }

    private class FakeRules(
        initial: List<Automation>,
        private val lastFired: Map<AutomationId, Long> = emptyMap(),
    ) : AutomationStore {
        private val values = MutableStateFlow(initial.associateBy { it.id })

        fun remove(id: AutomationId) {
            values.value -= id
        }

        override suspend fun get(id: AutomationId): Automation? = values.value[id]
        override suspend fun all(): List<Automation> = values.value.values.toList()
        override fun observeAll(): Flow<List<Automation>> =
            kotlinx.coroutines.flow.flow { values.collect { emit(it.values.toList()) } }

        override suspend fun armed(): List<Automation> =
            values.value.values.filter { it.status == AutomationStatus.ARMED && it.enabled }

        override suspend fun delete(id: AutomationId) {
            remove(id)
        }

        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean {
            val current = values.value[id] ?: return false
            if (current.status != AutomationStatus.ARMED || current.approvalFingerprint != fingerprint) {
                return false
            }
            values.value += id to current.copy(status = AutomationStatus.DISABLED, enabled = false)
            return true
        }

        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun markNeedsReview(id: AutomationId) = Unit
        override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.Claimed
        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = lastFired[id]
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T10:00:00Z")
    }
}
