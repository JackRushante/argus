package dev.argus

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.data.dao.AuditLogRecord
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionId
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * E2E in due invocazioni host. `schedule` termina prima dell'allarme; l'host uccide il processo
 * con `am kill` (mai force-stop), attende il receiver e invoca `verify` per controllare Room/DND.
 * Senza argomento il test viene saltato, quindi una normale connectedAndroidTest resta sicura.
 */
@RunWith(AndroidJUnit4::class)
class ArgusProcessDeathE2EInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )
    private val marker by lazy {
        context.getSharedPreferences(MARKER_PREFERENCES, Context.MODE_PRIVATE)
    }

    @Test
    fun processDeathRecoveryPhase(): Unit = runBlocking {
        when (val phase = arguments.getString(ARG_PHASE)) {
            PHASE_SCHEDULE -> schedule()
            PHASE_VERIFY -> verify()
            PHASE_CLEANUP -> cleanup()
            else -> assumeTrue("Passare -e $ARG_PHASE schedule|verify|cleanup (ricevuto: $phase)", false)
        }
    }

    private suspend fun schedule() {
        cleanup()
        assertEquals(ShizukuGatewayStatus.AUTHORIZED, services.shizukuGateway().status())
        assertTrue(
            "Il test process-death richiede temporaneamente l'accesso exact-alarm",
            services.timeAlarmBackend().canScheduleExact(),
        )

        val originalDnd = readDnd()
        assertTrue(
            "Marker DND originale non persistito",
            marker.edit().putString(KEY_ORIGINAL_DND, originalDnd.name).commit(),
        )
        try {
            services.deviceController().setDnd(DndMode.OFF, ExecutionId("e2e-process-setup"))
            awaitDnd(DndMode.OFF)

            val zone = ZoneId.systemDefault()
            val fireAt = LocalDateTime.now(zone).plusSeconds(FIRE_DELAY_SECONDS).withNano(0)
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "Bozza deterministica per recovery process-death.",
                    draft = AutomationDraft(
                        name = "Argus process-death E2E ${System.currentTimeMillis()}",
                        trigger = Trigger.Time(
                            cron = null,
                            at = fireAt.toString(),
                            tz = zone.id,
                            precision = TimePrecision.EXACT,
                        ),
                        actions = listOf(Action.SetDnd(DndMode.TOTAL)),
                    ),
                    metaError = null,
                ),
            )
            assertTrue("La bozza deve essere pronta: $submission", submission is DraftSubmissionResult.Ready)
            val ready = submission as DraftSubmissionResult.Ready
            val snapshot = ready.review.draft.snapshot
            val armed = services.approvalFlow().arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
            assertTrue("Arm non riuscito: $armed", armed is FlowArmResult.Armed)
            val automation = (armed as FlowArmResult.Armed).automation
            val fingerprint = requireNotNull(automation.approvalFingerprint)
            assertEquals(AutomationStatus.ARMED, services.automationStore().get(automation.id)?.status)

            assertTrue(
                "Marker process-death non persistito",
                marker.edit()
                    .putString(KEY_AUTOMATION_ID, automation.id.value)
                    .putString(KEY_FINGERPRINT, fingerprint.value)
                    .putLong(KEY_EVENT_AT, fireAt.atZone(zone).toInstant().toEpochMilli())
                    .putInt(KEY_SCHEDULE_PID, android.os.Process.myPid())
                    .commit(),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) { cleanup() }
            throw error
        }
    }

    private suspend fun verify() {
        val id = AutomationId(requireNotNull(marker.getString(KEY_AUTOMATION_ID, null)))
        val fingerprint = ApprovalFingerprint(requireNotNull(marker.getString(KEY_FINGERPRINT, null)))
        val eventAt = marker.getLong(KEY_EVENT_AT, -1L)
        val schedulePid = marker.getInt(KEY_SCHEDULE_PID, -1)
        assertTrue("Marker eventAt non valido", eventAt > 0L)
        assertNotEquals("Il processo target non è stato ricreato", schedulePid, android.os.Process.myPid())

        try {
            val fired = withTimeout(FIRE_TIMEOUT_MILLIS) {
                services.database().auditDao().observeLogForAutomation(id.value, 20)
                    .first { records -> records.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            awaitDnd(DndMode.TOTAL)
            assertJournalSucceeded(fired)
            assertEquals(AutomationStatus.DISABLED, services.automationStore().get(id)?.status)

            assertEquals(
                AlarmDeliveryResult.Ignored,
                services.timeAlarmRuntime().onAlarm(id, fingerprint, eventAt),
            )
            assertEquals(
                1,
                services.database().auditDao().forAutomation(id.value)
                    .count { it.kind == AuditKind.FIRED },
            )
        } finally {
            withContext(NonCancellable) { cleanup() }
        }
    }

    private suspend fun cleanup() {
        val automationClean = marker.getString(KEY_AUTOMATION_ID, null)?.let { rawId ->
            runCatching {
                val id = AutomationId(rawId)
                services.automationStore().delete(id)
                services.timeAlarmRuntime().reconcile(ReconcileReason.CAPABILITY_CHANGED)
                services.automationStore().get(id) == null
            }.getOrDefault(false)
        } ?: true
        val dndRestored = marker.getString(KEY_ORIGINAL_DND, null)?.let { rawMode ->
            runCatching {
                val mode = DndMode.valueOf(rawMode)
                services.deviceController().setDnd(
                    mode,
                    ExecutionId("e2e-process-restore"),
                )
                awaitDnd(mode)
            }.isSuccess
        } ?: true
        assertTrue("Cleanup automazione process-death non completato", automationClean)
        assertTrue("DND originale non ripristinato; marker conservato", dndRestored)
        assertTrue("Marker process-death non eliminato", marker.edit().clear().commit())
    }

    private suspend fun readDnd(): DndMode {
        val raw = services.deviceStateSnapshotProvider().current().values[StateKeys.DND]
        return when (raw) {
            "off" -> DndMode.OFF
            "priority" -> DndMode.PRIORITY
            "total" -> DndMode.TOTAL
            else -> error("Stato DND non leggibile: $raw")
        }
    }

    private suspend fun awaitDnd(expected: DndMode) {
        withTimeout(DND_TIMEOUT_MILLIS) {
            while (readDnd() != expected) delay(250)
        }
    }

    private suspend fun assertJournalSucceeded(record: AuditLogRecord) {
        val actions = services.database().executionJournalDao()
            .actions(requireNotNull(record.executionId))
        assertEquals(1, actions.size)
        assertEquals("set_dnd", actions.single().actionType)
        assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)
    }

    private companion object {
        const val ARG_PHASE = "processDeathPhase"
        const val PHASE_SCHEDULE = "schedule"
        const val PHASE_VERIFY = "verify"
        const val PHASE_CLEANUP = "cleanup"
        const val MARKER_PREFERENCES = "argus_process_death_e2e"
        const val KEY_AUTOMATION_ID = "automation_id"
        const val KEY_FINGERPRINT = "approval_fingerprint"
        const val KEY_EVENT_AT = "event_at"
        const val KEY_ORIGINAL_DND = "original_dnd"
        const val KEY_SCHEDULE_PID = "schedule_pid"
        const val FIRE_DELAY_SECONDS = 45L
        const val FIRE_TIMEOUT_MILLIS = 90_000L
        const val DND_TIMEOUT_MILLIS = 20_000L
    }
}
