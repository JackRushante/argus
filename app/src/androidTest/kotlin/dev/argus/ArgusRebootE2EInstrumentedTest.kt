package dev.argus

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ScheduledAlarmMode
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * E2E host-driven in tre fasi. `schedule` arma una regola sufficientemente lontana; l'host
 * riavvia davvero il device e, dopo aver ripristinato Shizuku/ADB, invoca `verify`. Il test prova
 * che BOOT_COMPLETED ha ricreato la registrazione AlarmManager confrontando `updatedAtMillis`:
 * APP_START lascia invariato un record equivalente, mentre ReconcileReason.BOOT forza l'upsert.
 * Senza fase esplicita una normale connectedAndroidTest resta sicura.
 */
@RunWith(AndroidJUnit4::class)
class ArgusRebootE2EInstrumentedTest {
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
    fun rebootRecoveryPhase(): Unit = runBlocking {
        when (val phase = arguments.getString(ARG_PHASE)) {
            PHASE_SCHEDULE -> schedule()
            PHASE_VERIFY -> verify()
            PHASE_CLEANUP -> cleanup()
            else -> assumeTrue(
                "Passare -e $ARG_PHASE schedule|verify|cleanup (ricevuto: $phase)",
                false,
            )
        }
    }

    private suspend fun schedule() {
        cleanup()
        assertEquals(ShizukuGatewayStatus.AUTHORIZED, services.shizukuGateway().status())
        assertTrue(
            "Il test reboot richiede temporaneamente l'accesso exact-alarm",
            services.timeAlarmBackend().canScheduleExact(),
        )
        val originalDnd = readDnd()
        val initialBootCount = bootCount()
        assertTrue("BOOT_COUNT non leggibile", initialBootCount >= 0)
        assertTrue(
            "Marker DND originale non persistito",
            marker.edit().putString(KEY_ORIGINAL_DND, originalDnd.name).commit(),
        )

        try {
            val zone = ZoneId.systemDefault()
            val fireAt = LocalDateTime.now(zone).plusHours(FIRE_DELAY_HOURS).withNano(0)
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "Bozza deterministica per recovery reboot.",
                    draft = AutomationDraft(
                        name = "$AUTOMATION_PREFIX ${System.currentTimeMillis()}",
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
            val snapshot = (submission as DraftSubmissionResult.Ready).review.draft.snapshot
            val arm = services.approvalFlow().arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
            assertTrue("Arm non riuscito: $arm", arm is FlowArmResult.Armed)
            val automation = (arm as FlowArmResult.Armed).automation
            val persisted = services.database().scheduledTimeAlarmDao().get(automation.id.value)
            assertNotNull("Record scheduler non persistito", persisted)
            requireNotNull(persisted)
            assertEquals(ScheduledAlarmMode.EXACT.name, persisted.scheduledMode)

            assertTrue(
                "Marker reboot non persistito",
                marker.edit()
                    .putString(KEY_AUTOMATION_ID, automation.id.value)
                    .putString(KEY_FINGERPRINT, persisted.approvalFingerprint)
                    .putLong(KEY_EVENT_AT, persisted.eventAtMillis)
                    .putLong(KEY_UPDATED_AT_BEFORE_REBOOT, persisted.updatedAtMillis)
                    .putInt(KEY_BOOT_COUNT, initialBootCount)
                    .putInt(KEY_SCHEDULE_PID, Process.myPid())
                    .commit(),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) { cleanup() }
            throw error
        }
    }

    private suspend fun verify() {
        val id = AutomationId(requireNotNull(marker.getString(KEY_AUTOMATION_ID, null)))
        val fingerprint = requireNotNull(marker.getString(KEY_FINGERPRINT, null))
        val eventAt = marker.getLong(KEY_EVENT_AT, -1L)
        val updatedBefore = marker.getLong(KEY_UPDATED_AT_BEFORE_REBOOT, -1L)
        val previousBootCount = marker.getInt(KEY_BOOT_COUNT, -1)
        val previousPid = marker.getInt(KEY_SCHEDULE_PID, -1)
        assertTrue("Marker reboot incompleto", eventAt > 0L && updatedBefore > 0L)
        assertTrue("BOOT_COUNT precedente non valido", previousBootCount >= 0)
        assertTrue(
            "Il device non risulta riavviato",
            bootCount() > previousBootCount,
        )
        assertNotEquals("Il processo target non è stato ricreato", previousPid, Process.myPid())
        assertEquals(ShizukuGatewayStatus.AUTHORIZED, services.shizukuGateway().status())

        try {
            val automation = services.automationStore().get(id)
            assertNotNull("Automazione persa dopo reboot", automation)
            requireNotNull(automation)
            assertEquals(AutomationStatus.ARMED, automation.status)
            assertEquals(fingerprint, automation.approvalFingerprint?.value)

            val persisted = services.database().scheduledTimeAlarmDao().get(id.value)
            assertNotNull("Registrazione scheduler persa dopo reboot", persisted)
            requireNotNull(persisted)
            assertEquals(fingerprint, persisted.approvalFingerprint)
            assertEquals(eventAt, persisted.eventAtMillis)
            assertEquals(ScheduledAlarmMode.EXACT.name, persisted.scheduledMode)
            assertTrue(
                "BOOT_COMPLETED non ha forzato la nuova registrazione AlarmManager",
                persisted.updatedAtMillis > updatedBefore,
            )
            val currentBootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            assertTrue(
                "La registrazione non appartiene al boot corrente",
                persisted.updatedAtMillis >= currentBootEpoch - BOOT_EPOCH_TOLERANCE_MILLIS,
            )
            assertTrue(
                "La regola futura non deve essere scattata durante il reboot",
                services.database().auditDao().forAutomation(id.value).isEmpty(),
            )
            assertEquals(
                "Il reboot non deve cambiare DND",
                marker.getString(KEY_ORIGINAL_DND, null),
                readDnd().name,
            )
        } finally {
            withContext(NonCancellable) { cleanup() }
        }
    }

    private suspend fun cleanup() {
        val matchingIds = services.automationStore().all()
            .filter { it.name.startsWith(AUTOMATION_PREFIX) }
            .map { it.id }
            .toMutableSet()
        marker.getString(KEY_AUTOMATION_ID, null)?.let { matchingIds += AutomationId(it) }

        matchingIds.forEach { id ->
            runCatching { services.timeAlarmBackend().cancel(id) }
            services.automationStore().delete(id)
        }
        services.timeAlarmRuntime().reconcile(ReconcileReason.CAPABILITY_CHANGED)
        assertTrue(
            "Cleanup automazione reboot non completato",
            services.automationStore().all().none { it.name.startsWith(AUTOMATION_PREFIX) },
        )
        assertFalse(
            "Record scheduler reboot non eliminato",
            services.database().scheduledTimeAlarmDao().all()
                .any { it.automationId in matchingIds.map(AutomationId::value) },
        )
        val dndRestored = marker.getString(KEY_ORIGINAL_DND, null)?.let { rawMode ->
            runCatching {
                val mode = DndMode.valueOf(rawMode)
                services.deviceController().setDnd(
                    mode,
                    dev.argus.engine.runtime.ExecutionId("e2e-reboot-restore"),
                )
                withTimeout(DND_TIMEOUT_MILLIS) {
                    while (readDnd() != mode) delay(250)
                }
            }.isSuccess
        } ?: true
        assertTrue("DND originale non ripristinato; marker conservato", dndRestored)
        assertTrue("Marker reboot non eliminato", marker.edit().clear().commit())
    }

    private suspend fun readDnd(): DndMode = when (
        val raw = services.deviceStateSnapshotProvider().current().values[StateKeys.DND]
    ) {
        "off" -> DndMode.OFF
        "priority" -> DndMode.PRIORITY
        "total" -> DndMode.TOTAL
        else -> error("Stato DND non leggibile: $raw")
    }

    private fun bootCount(): Int = Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.BOOT_COUNT,
        -1,
    )

    private companion object {
        const val ARG_PHASE = "rebootPhase"
        const val PHASE_SCHEDULE = "schedule"
        const val PHASE_VERIFY = "verify"
        const val PHASE_CLEANUP = "cleanup"
        const val MARKER_PREFERENCES = "argus_reboot_e2e"
        const val AUTOMATION_PREFIX = "Argus reboot E2E"
        const val KEY_AUTOMATION_ID = "automation_id"
        const val KEY_FINGERPRINT = "approval_fingerprint"
        const val KEY_EVENT_AT = "event_at"
        const val KEY_UPDATED_AT_BEFORE_REBOOT = "updated_at_before_reboot"
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_SCHEDULE_PID = "schedule_pid"
        const val KEY_ORIGINAL_DND = "original_dnd"
        const val FIRE_DELAY_HOURS = 2L
        const val BOOT_EPOCH_TOLERANCE_MILLIS = 5_000L
        const val DND_TIMEOUT_MILLIS = 20_000L
    }
}
