package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ScheduledAlarmMode
import dev.argus.brain.AndroidBridgeConfigurationStore
import dev.argus.data.dao.AuditLogRecord
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionId
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuPermissionResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * E2E di produzione: usa il bridge Hermes reale, Room reale, AlarmManager reale e Shizuku reale.
 * Il solo adattamento di test è spostare il trigger compilato a pochi secondi nel futuro, così il
 * test non dipende dall'ora del giorno; azione e condizioni devono invece coincidere col compile.
 */
@RunWith(AndroidJUnit4::class)
class ArgusProductionE2EInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun chatCompileReviewArmAlarmDndAndJournal(): Unit = runBlocking {
        val bearer = consumeBridgeToken()

        val configuration = services.bridgeConfigurationStore()
        val preferences = services.appPreferencesStore()
        val store = services.automationStore()
        val runtime = services.timeAlarmRuntime()
        val database = services.database()
        val device = services.deviceController()
        val originalConfigurationUrl = configuration.baseUrl()
        val originalBearer = configuration.bearerToken()
        val originalPreferences = preferences.observe().value
        var originalDnd: DndMode? = null
        var automationId: dev.argus.engine.model.AutomationId? = null

        try {
            assertTrue(
                configuration.saveConfiguration(
                    AndroidBridgeConfigurationStore.DEFAULT_BASE_URL,
                    bearer,
                ),
            )
            assertTrue(preferences.setPrivacyAccepted(true))
            assertTrue(preferences.setOnboardingCompleted(true))
            val shizuku = services.shizukuGateway()
            if (shizuku.status() != ShizukuGatewayStatus.AUTHORIZED) {
                assertEquals(
                    "Approva il dialog Shizuku sul dispositivo",
                    ShizukuPermissionResult.GRANTED,
                    withTimeout(SHIZUKU_PERMISSION_TIMEOUT_MILLIS) {
                        shizuku.requestPermission()
                    },
                )
            }
            assertEquals(ShizukuGatewayStatus.AUTHORIZED, shizuku.status())
            assertTrue(
                "Il test deterministico richiede temporaneamente l'accesso exact-alarm",
                services.timeAlarmBackend().canScheduleExact(),
            )

            originalDnd = readDnd()
            device.setDnd(DndMode.OFF, ExecutionId("e2e-setup-dnd"))
            awaitDnd(DndMode.OFF)

            val zone = ZoneId.systemDefault()
            val compileAt = LocalDateTime.now(zone).plusDays(1).withNano(0)
            val state = services.deviceStateSnapshotProvider().current()
            val manifest = services.capabilityProbe().probe(state)
            val compiled = services.brain().compile(
                "Crea una regola Time una tantum il ${compileAt.toLocalDate()} alle " +
                    "${compileAt.toLocalTime()} nel fuso ${zone.id}, con una sola azione: " +
                    "imposta Non disturbare su TOTAL. Non aggiungere condizioni, notifiche " +
                    "o altre azioni.",
                manifest,
                state,
            )
            val draft = requireNotNull(compiled.draft) {
                "Hermes non ha prodotto un draft: ${compiled.metaError ?: "nessun dettaglio"}"
            }
            assertTrue("Hermes deve compilare un trigger Time", draft.trigger is Trigger.Time)
            assertNotNull("Hermes deve compilare un trigger una tantum", (draft.trigger as Trigger.Time).at)
            assertEquals(listOf(Action.SetDnd(DndMode.TOTAL)), draft.actions)
            assertEquals(null, draft.conditions)

            val fireAt = LocalDateTime.now(zone).plusSeconds(FIRE_DELAY_SECONDS).withNano(0)
            val deterministicCompile = CompileResult(
                reply = compiled.reply,
                draft = draft.copy(
                    name = "$E2E_AUTOMATION_PREFIX ${System.currentTimeMillis()}",
                    trigger = Trigger.Time(
                        cron = null,
                        at = fireAt.toString(),
                        tz = zone.id,
                        precision = TimePrecision.EXACT,
                    ),
                ),
                metaError = null,
            )
            val submission = services.approvalFlow().submit(deterministicCompile)
            assertTrue("Il draft deve essere pronto alla review: $submission", submission is DraftSubmissionResult.Ready)
            val ready = submission as DraftSubmissionResult.Ready
            assertTrue("La review locale deve consentire l'arm", ready.review.canArm)
            val snapshot = ready.review.draft.snapshot

            val arm = services.approvalFlow().arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
            assertTrue("Arm non riuscito: $arm", arm is FlowArmResult.Armed)
            val armed = (arm as FlowArmResult.Armed).automation
            automationId = armed.id
            assertNotNull(armed.approvalFingerprint)
            assertEquals(AutomationStatus.ARMED, store.get(armed.id)?.status)
            assertEquals(
                "L'E2E richiede una registrazione AlarmManager realmente exact",
                ScheduledAlarmMode.EXACT.name,
                database.scheduledTimeAlarmDao().get(armed.id.value)?.scheduledMode,
            )

            val fired = withTimeout(FIRE_TIMEOUT_MILLIS) {
                database.auditDao().observeLogForAutomation(armed.id.value, 20)
                    .first { records -> records.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            awaitDnd(DndMode.TOTAL)
            assertJournalSucceeded(fired)
            assertEquals(AutomationStatus.DISABLED, store.get(armed.id)?.status)

            val duplicate = runtime.onAlarm(
                armed.id,
                requireNotNull(armed.approvalFingerprint),
                fireAt.atZone(zone).toInstant().toEpochMilli(),
            )
            assertEquals(AlarmDeliveryResult.Ignored, duplicate)
            assertEquals(
                1,
                database.auditDao().forAutomation(armed.id.value).count { it.kind == AuditKind.FIRED },
            )
        } finally {
            withContext(NonCancellable) {
                val automationClean = automationId?.let { id ->
                    runCatching {
                        store.delete(id)
                        runtime.reconcile(ReconcileReason.CAPABILITY_CHANGED)
                        store.get(id) == null
                    }.getOrDefault(false)
                } ?: true
                val dndRestored = originalDnd?.let { mode ->
                    runCatching {
                        device.setDnd(mode, ExecutionId("e2e-restore-dnd"))
                        awaitDnd(mode)
                    }.isSuccess
                } ?: true
                val configurationRestored = runCatching {
                    if (originalBearer == null) {
                        configuration.clearBearerToken() &&
                            configuration.saveBaseUrl(originalConfigurationUrl)
                    } else {
                        configuration.saveConfiguration(originalConfigurationUrl, originalBearer)
                    }
                }.getOrDefault(false)
                val preferencesRestored = runCatching {
                    if (originalPreferences.privacyAccepted) {
                        val privacySaved = preferences.setPrivacyAccepted(true)
                        val onboardingSaved = preferences.setOnboardingCompleted(
                            originalPreferences.onboardingCompleted,
                        )
                        privacySaved && onboardingSaved
                    } else {
                        val onboardingSaved = preferences.setOnboardingCompleted(false)
                        val privacySaved = preferences.setPrivacyAccepted(false)
                        onboardingSaved && privacySaved
                    }
                }.getOrDefault(false)
                assertTrue("Cleanup automazione E2E non completato", automationClean)
                assertTrue("DND originale non ripristinato", dndRestored)
                assertTrue("Configurazione bridge non ripristinata", configurationRestored)
                assertTrue("Preferenze privacy/onboarding non ripristinate", preferencesRestored)
            }
        }
    }

    /**
     * Cleanup host-driven da usare se ADB o l'instrumentation vengono interrotti prima che il
     * `finally` del test principale possa girare. È intenzionalmente limitato alle automazioni con
     * il prefisso E2E e ripristina anche configurazione privata e DND del package di laboratorio.
     */
    @Test
    fun cleanupInterruptedHostRun(): Unit = runBlocking {
        val store = services.automationStore()
        val backend = services.timeAlarmBackend()
        val runtime = services.timeAlarmRuntime()
        val configuration = services.bridgeConfigurationStore()
        val preferences = services.appPreferencesStore()
        val failures = mutableListOf<String>()

        store.all()
            .filter { it.name.startsWith(E2E_AUTOMATION_PREFIX) }
            .forEach { automation ->
                runCatching { backend.cancel(automation.id) }
                    .onFailure { failures += "cancel:${automation.id.value}" }
                runCatching { store.delete(automation.id) }
                    .onFailure { failures += "delete:${automation.id.value}" }
            }
        runCatching { runtime.reconcile(ReconcileReason.CAPABILITY_CHANGED) }
            .onFailure { failures += "reconcile" }
        runCatching {
            configuration.clearBearerToken() &&
                configuration.saveBaseUrl(AndroidBridgeConfigurationStore.DEFAULT_BASE_URL)
        }.onFailure { failures += "bridge" }
            .onSuccess { restored -> if (!restored) failures += "bridge" }
        runCatching {
            preferences.setOnboardingCompleted(false) && preferences.setPrivacyAccepted(false)
        }.onFailure { failures += "preferences" }
            .onSuccess { restored -> if (!restored) failures += "preferences" }
        runCatching {
            services.deviceController().setDnd(DndMode.OFF, ExecutionId("e2e-host-cleanup"))
            awaitDnd(DndMode.OFF)
        }.onFailure { failures += "dnd" }

        assertTrue(
            "Cleanup host E2E incompleto: ${failures.joinToString()}",
            failures.isEmpty(),
        )
        assertTrue(
            "Sono rimaste automazioni E2E",
            store.all().none { it.name.startsWith(E2E_AUTOMATION_PREFIX) },
        )
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
        val executionId = requireNotNull(record.executionId)
        val actions = services.database().executionJournalDao().actions(executionId)
        assertEquals(1, actions.size)
        assertEquals("set_dnd", actions.single().actionType)
        assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)
    }

    /**
     * Il bearer non deve transitare negli argomenti di `am instrument`: adbd registra l'intera
     * command line in logcat. Il runner lo prepara invece nei file privati dell'APK debug; il test
     * lo legge e lo elimina prima di effettuare qualsiasi chiamata di rete.
     */
    private fun consumeBridgeToken(): String {
        val context = instrumentation.targetContext
        val tokenResult = runCatching {
            context.openFileInput(BRIDGE_TOKEN_FILE).bufferedReader().use { it.readText().trim() }
        }
        if (tokenResult.isFailure) {
            context.deleteFile(BRIDGE_TOKEN_FILE)
            error("Token E2E assente: preparare il file privato $BRIDGE_TOKEN_FILE")
        }
        check(context.deleteFile(BRIDGE_TOKEN_FILE)) { "Token E2E privato non eliminato" }
        val token = tokenResult.getOrThrow()
        require(token.isNotBlank()) { "Token E2E vuoto" }
        return token
    }

    private companion object {
        const val E2E_AUTOMATION_PREFIX = "Argus E2E DND"
        const val BRIDGE_TOKEN_FILE = "argus-e2e-bridge-token"
        const val FIRE_DELAY_SECONDS = 45L
        const val FIRE_TIMEOUT_MILLIS = 120_000L
        const val DND_TIMEOUT_MILLIS = 20_000L
        const val SHIZUKU_PERMISSION_TIMEOUT_MILLIS = 60_000L
    }
}
