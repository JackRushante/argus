package dev.argus

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * E2E host-driven del tier base (P3-3). `prepare` arma una one-shot DND con Shizuku attivo; l'host
 * ferma il daemon e attende AlarmManager; `verify` controlla che — essendo SetDnd una azione BASE
 * (NotificationManager, accesso «Non disturbare» implicito dal listener) — lo scatto AVVENGA lo
 * stesso con Shizuku fermo: nessun blocco policy, regola ancora ARMED, DND davvero a TOTAL. È il
 * complemento del vecchio contratto (che bloccava la regola): il tier base rende le azioni normali
 * indipendenti da Shizuku. L'host riavvia poi Shizuku e invoca `cleanup`, che ripristina il DND
 * prima di eliminare il marker. Senza fase esplicita il test è saltato.
 */
@RunWith(AndroidJUnit4::class)
class ArgusShizukuOutageE2EInstrumentedTest {
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
    fun shizukuOutagePhase(): Unit = runBlocking {
        when (val phase = arguments.getString(ARG_PHASE)) {
            PHASE_PREPARE -> prepare()
            PHASE_VERIFY -> verify()
            PHASE_CLEANUP -> cleanup()
            else -> assumeTrue(
                "Passare -e $ARG_PHASE prepare|verify|cleanup (ricevuto: $phase)",
                false,
            )
        }
    }

    private suspend fun prepare() {
        cleanup()
        assertEquals(ShizukuGatewayStatus.AUTHORIZED, services.shizukuGateway().status())
        assertTrue(
            "Il test outage richiede temporaneamente l'accesso exact-alarm",
            services.timeAlarmBackend().canScheduleExact(),
        )
        assertEquals(
            "Il test non deve modificare uno stato DND preesistente",
            "off",
            services.deviceStateSnapshotProvider().current().values[StateKeys.DND],
        )
        assertTrue(
            "Marker DND originale non persistito",
            marker.edit().putString(KEY_ORIGINAL_DND, DndMode.OFF.name).commit(),
        )

        try {
            val zone = ZoneId.systemDefault()
            val fireAt = LocalDateTime.now(zone).plusSeconds(FIRE_DELAY_SECONDS).withNano(0)
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "Bozza deterministica per outage Shizuku.",
                    draft = dev.argus.engine.model.AutomationDraft(
                        name = "Argus Shizuku outage E2E ${System.currentTimeMillis()}",
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
            assertTrue(
                "Marker outage non persistito",
                marker.edit().putString(KEY_AUTOMATION_ID, automation.id.value).commit(),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) { cleanup() }
            throw error
        }
    }

    private suspend fun verify() {
        val id = AutomationId(requireNotNull(marker.getString(KEY_AUTOMATION_ID, null)))
        assertEquals(
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            services.shizukuGateway().status(),
        )
        try {
            // Tier base: SetDnd esegue via NotificationManager, quindi con Shizuku fermo lo scatto
            // avviene lo stesso. Attendo il FIRED (non un blocco).
            val fired = withTimeout(AUDIT_TIMEOUT_MILLIS) {
                services.database().auditDao().observeLogForAutomation(id.value, 20)
                    .first { rows -> rows.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            val records = services.database().auditDao().forAutomation(id.value)
            assertFalse(
                "Una base come SetDnd non deve essere bloccata in outage Shizuku",
                records.any { it.kind == AuditKind.BLOCKED_POLICY },
            )
            val actions = services.database().executionJournalDao()
                .actions(requireNotNull(fired.executionId))
            assertEquals("set_dnd", actions.single().actionType)
            assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)
            assertTrue(
                "Il DND non è passato a TOTAL via il tier base",
                awaitDnd(DndMode.TOTAL),
            )
            // La regola resta ARMED: nessuna quarantena, la capability base è disponibile.
            assertEquals(
                AutomationStatus.ARMED,
                services.automationStore().get(id)?.status,
            )
        } finally {
            // Il daemon è volutamente spento: elimina la regola ma conserva il marker affinché
            // la fase cleanup, dopo il riavvio Shizuku, possa verificare/ripristinare il DND.
            withContext(NonCancellable) {
                assertTrue(
                    "Cleanup automazione outage non completato",
                    cleanupAutomation(),
                )
            }
        }
    }

    private suspend fun cleanup() {
        val automationClean = cleanupAutomation()
        val dndRestored = marker.getString(KEY_ORIGINAL_DND, null)?.let { rawMode ->
            runCatching {
                check(services.shizukuGateway().status() == ShizukuGatewayStatus.AUTHORIZED)
                val mode = DndMode.valueOf(rawMode)
                services.deviceController().setDnd(
                    mode,
                    ExecutionId("e2e-outage-restore"),
                )
                awaitDnd(mode)
            }.getOrDefault(false)
        } ?: true
        assertTrue("Cleanup automazione outage non completato", automationClean)
        assertTrue("DND originale non ripristinato; marker conservato", dndRestored)
        assertTrue("Marker outage non eliminato", marker.edit().clear().commit())
    }

    private suspend fun cleanupAutomation(): Boolean =
        marker.getString(KEY_AUTOMATION_ID, null)?.let { rawId ->
            runCatching {
                val id = AutomationId(rawId)
                services.automationStore().delete(id)
                services.timeAlarmRuntime().reconcile(ReconcileReason.CAPABILITY_CHANGED)
                services.automationStore().get(id) == null
            }.getOrDefault(false)
        } ?: true

    private suspend fun awaitDnd(expected: DndMode): Boolean = runCatching {
        withTimeout(DND_TIMEOUT_MILLIS) {
            while (
                services.deviceStateSnapshotProvider().current().values[StateKeys.DND] !=
                expected.name.lowercase()
            ) delay(250)
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val ARG_PHASE = "shizukuOutagePhase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val PHASE_CLEANUP = "cleanup"
        const val MARKER_PREFERENCES = "argus_shizuku_outage_e2e"
        const val KEY_AUTOMATION_ID = "automation_id"
        const val KEY_ORIGINAL_DND = "original_dnd"
        const val FIRE_DELAY_SECONDS = 45L
        const val AUDIT_TIMEOUT_MILLIS = 30_000L
        const val DND_TIMEOUT_MILLIS = 20_000L
    }
}
