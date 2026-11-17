package dev.argus.automation.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.health.AndroidHealthAlertStore
import dev.argus.automation.health.AndroidHealthNotifier
import dev.argus.automation.health.CapabilityHealthMonitor
import dev.argus.automation.health.HealthAlertStore
import dev.argus.automation.health.HealthNotifier
import dev.argus.automation.health.HealthReportingExecutionJournal
import dev.argus.automation.health.RuleFailureMonitor
import dev.argus.automation.notification.ArgusNotificationListenerService
import dev.argus.automation.notification.NotificationListenerConnection
import dev.argus.automation.notification.NotificationListenerWatchdog
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomExecutionJournal
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.listenedTriggers
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Singleton

/** 0.7.2: avvisi di salute delle regole e ricollegamento del listener notifiche. */
@Module
@InstallIn(SingletonComponent::class)
object HealthModule {
    @Provides
    @Singleton
    fun healthAlertStore(@ApplicationContext context: Context): HealthAlertStore = AndroidHealthAlertStore(context)

    @Provides
    @Singleton
    fun healthNotifier(@ApplicationContext context: Context): HealthNotifier = AndroidHealthNotifier(context)

    @Provides
    @Singleton
    fun ruleFailureMonitor(
        database: ArgusDatabase,
        store: AutomationStore,
        alerts: HealthAlertStore,
        notifier: HealthNotifier,
    ): RuleFailureMonitor = RuleFailureMonitor(
        recentOutcomes = { id, limit -> database.executionJournalDao().recentOutcomes(id.value, limit) },
        ruleName = { id -> store.get(id)?.name },
        store = alerts,
        notifier = notifier,
        today = { localEpochDay() },
    )

    /** Il journal delle esecuzioni, che a ogni esito chiede in background se la regola fallisce di fila. */
    @Provides
    @Singleton
    fun healthReportingJournal(
        journal: RoomExecutionJournal,
        database: ArgusDatabase,
        @ApplicationScope scope: CoroutineScope,
        monitor: RuleFailureMonitor,
    ): HealthReportingExecutionJournal = HealthReportingExecutionJournal(journal, journal) { executionId ->
        scope.launch {
            try {
                val automationId = database.executionJournalDao().execution(executionId.value)?.automationId
                    ?: return@launch
                monitor.onExecutionConcluded(AutomationId(automationId))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // L'avviso è best-effort: non deve mai toccare l'esecuzione.
            }
        }
    }

    @Provides
    @Singleton
    fun capabilityHealthMonitor(
        @ApplicationScope scope: CoroutineScope,
        store: AutomationStore,
        probe: AndroidCapabilityProbe,
        alerts: HealthAlertStore,
        notifier: HealthNotifier,
    ): CapabilityHealthMonitor {
        lateinit var monitor: CapabilityHealthMonitor
        monitor = CapabilityHealthMonitor(
            armedRules = store::armed,
            snapshot = probe::healthSnapshot,
            store = alerts,
            notifier = notifier,
            nowMillis = System::currentTimeMillis,
            today = { localEpochDay() },
            // Un solo ricontrollo dopo l'attesa, non un ciclo: poi decidono i reconcile normali.
            scheduleRecheck = { delayMillis ->
                scope.launch {
                    delay(delayMillis)
                    try {
                        monitor.check()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Best-effort, come il controllo nel reconcile.
                    }
                }
            },
        )
        return monitor
    }

    @Provides
    @Singleton
    fun notificationListenerWatchdog(
        @ApplicationContext context: Context,
        store: AutomationStore,
    ): NotificationListenerWatchdog = NotificationListenerWatchdog(
        notificationRulesArmed = { store.armed().any { rule -> rule.listenedTriggers.any { it is Trigger.Notification } } },
        accessGranted = {
            runCatching {
                context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            }.getOrDefault(false)
        },
        connected = { NotificationListenerConnection.isConnected },
        requestRebind = { ArgusNotificationListenerService.rebind(context) },
    )

    private fun localEpochDay(): Long = LocalDate.now().toEpochDay()
}
