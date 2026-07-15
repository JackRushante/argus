package dev.argus.automation

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.automation.notification.NotificationReplyHandle
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomJournalMaintenance
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.automation.connectivity.ConnectivityReconcileReport
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.geofence.GeofenceReconcileReport
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArgusRuntimeControllerTest {
    @Test
    fun `privacy revocation clears reply handles and reconciles immediately while foreground`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = ArgusDatabase.inMemory(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val preferences = FakePreferences(privacyAccepted = true)
                val registry = ActiveNotificationReplyRegistry()
                registry.replace(handle(context))
                val scheduler = RecordingTimeAlarmRuntime()
                val connectivity = RecordingConnectivityRuntime()
                val geofence = RecordingGeofenceRuntime()
                val controller = ArgusRuntimeController(
                    scope = scope,
                    scheduler = scheduler,
                    capabilities = CapabilityReconciler(
                        EmptyAutomationStore(),
                        FirePolicySnapshotProvider {
                            FirePolicySnapshot(emptySet(), emptySet(), emptySet())
                        },
                    ),
                    maintenance = RoomJournalMaintenance(db),
                    shizukuStatus = MutableStateFlow(ShizukuGatewayStatus.AUTHORIZED),
                    preferences = preferences,
                    replyRegistry = registry,
                    connectivity = connectivity,
                    geofence = geofence,
                    nowMillis = { 1_000L },
                )

                controller.start()
                awaitUntil("bootstrap APP_START") { scheduler.reconcileCount() >= 1 }
                awaitUntil("bootstrap connectivity") { connectivity.reconcileCount() >= 1 }
                awaitUntil("bootstrap geofence") { geofence.recreateFlags().isNotEmpty() }
                assertEquals(true, geofence.recreateFlags().first())
                assertEquals(1, registry.size())
                val beforeRevoke = scheduler.reconcileCount()

                // revokePrivacy() avviene con l'app già foreground: niente ON_START successivo.
                preferences.setPrivacy(false)
                awaitUntil("registry ripulito alla revoca") { registry.size() == 0 }
                awaitUntil("reconcile immediato alla revoca") {
                    scheduler.reconcileCount() > beforeRevoke
                }
                awaitUntil("geofence riconciliato alla revoca") {
                    geofence.recreateFlags().size >= 2
                }
                assertEquals(false, geofence.recreateFlags().last())

                val beforeAccept = scheduler.reconcileCount()
                preferences.setPrivacy(true)
                awaitUntil("reconcile alla riaccettazione") {
                    scheduler.reconcileCount() > beforeAccept
                }
                assertEquals(0, registry.size())
            } finally {
                scope.cancel()
                db.close()
            }
        }
    }

    private suspend fun awaitUntil(
        label: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMillis) {
                while (!condition()) delay(10)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timeout in attesa di: $label", error)
        }
    }

    private fun handle(context: Context): NotificationReplyHandle = NotificationReplyHandle(
        packageName = "com.whatsapp",
        notificationKey = "sbn:test",
        conversationId = "shortcut:com.whatsapp:hash",
        eventId = TriggerEventId("notification:test"),
        isGroup = false,
        remoteInput = RemoteInput.Builder("reply_text").setAllowFreeFormInput(true).build(),
        pendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent("dev.argus.test.REPLY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ),
    )

    private class FakePreferences(privacyAccepted: Boolean) : AppPreferencesStore {
        private val state = MutableStateFlow(
            AppPreferences(privacyAccepted = privacyAccepted, onboardingCompleted = privacyAccepted),
        )

        fun setPrivacy(accepted: Boolean) {
            state.value = AppPreferences(
                privacyAccepted = accepted,
                onboardingCompleted = accepted && state.value.onboardingCompleted,
            )
        }

        override fun observe(): StateFlow<AppPreferences> = state.asStateFlow()

        override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean {
            setPrivacy(accepted)
            return true
        }

        override suspend fun setOnboardingCompleted(completed: Boolean): Boolean = false
    }

    private class RecordingTimeAlarmRuntime : TimeAlarmRuntime {
        private val reasons = CopyOnWriteArrayList<ReconcileReason>()

        fun reconcileCount(): Int = reasons.size

        override suspend fun onAlarm(
            automationId: AutomationId,
            approvalFingerprint: ApprovalFingerprint,
            eventAtMillis: Long,
        ): AlarmDeliveryResult = AlarmDeliveryResult.Ignored

        override suspend fun reconcile(reason: ReconcileReason): ReconcileReport {
            reasons += reason
            return ReconcileReport(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private class RecordingConnectivityRuntime : ConnectivityTriggerRuntime {
        private val calls = java.util.concurrent.atomic.AtomicInteger()
        fun reconcileCount(): Int = calls.get()
        override suspend fun reconcile(): ConnectivityReconcileReport {
            calls.incrementAndGet()
            return ConnectivityReconcileReport(emptyList(), active = false)
        }
    }

    private class RecordingGeofenceRuntime : GeofenceTriggerRuntime {
        private val flags = CopyOnWriteArrayList<Boolean>()
        fun recreateFlags(): List<Boolean> = flags.toList()
        override suspend fun reconcile(
            recreateOsRegistrations: Boolean,
        ): GeofenceReconcileReport {
            flags += recreateOsRegistrations
            return GeofenceReconcileReport()
        }
    }

    private class EmptyAutomationStore : AutomationStore {
        override suspend fun get(id: AutomationId): Automation? = null
        override suspend fun all(): List<Automation> = emptyList()
        override fun observeAll(): Flow<List<Automation>> = flowOf(emptyList())
        override suspend fun armed(): List<Automation> = emptyList()
        override suspend fun delete(id: AutomationId) = Unit
        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) =
            false
        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) =
            false
        override suspend fun markNeedsReview(id: AutomationId) = Unit
        override suspend fun markNeedsReviewIfApproved(
            id: AutomationId,
            fingerprint: ApprovalFingerprint,
        ): Boolean = false
        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
            FireClaimResult.NotEligible
        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = null
    }
}
