package dev.argus.automation

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTimeAlarmBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetAlarms() {
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun `backend registers exact alarm and cancellation removes it`() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)
        val backend = AndroidTimeAlarmBackend(context)
        val registration = TimeAlarmRegistration(
            automationId = AutomationId("exact"),
            approvalFingerprint = ApprovalFingerprint("b".repeat(64)),
            eventAtMillis = 1_900_000_000_000,
            requestedPrecision = TimePrecision.EXACT,
        )

        assertEquals(ScheduledAlarmMode.EXACT, backend.schedule(registration))
        assertEquals(1_900_000_000_000, shadow.scheduledAlarms.single().triggerAtMs)
        assertTrue(shadow.scheduledAlarms.single().isAllowWhileIdle)

        backend.cancel(registration.automationId)
        assertEquals(emptyList(), shadow.scheduledAlarms)
    }

    @Test
    fun `denied exact access falls back to inexact allow while idle`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)
        val backend = AndroidTimeAlarmBackend(context)

        val mode = backend.schedule(
            TimeAlarmRegistration(
                automationId = AutomationId("fallback"),
                approvalFingerprint = ApprovalFingerprint("c".repeat(64)),
                eventAtMillis = 1_900_000_000_000,
                requestedPrecision = TimePrecision.EXACT,
            ),
        )

        assertEquals(ScheduledAlarmMode.INEXACT, mode)
        assertEquals(1, shadow.scheduledAlarms.size)
        assertTrue(shadow.scheduledAlarms.single().isAllowWhileIdle)
    }

    @Test
    fun `signal parser binds uri id fingerprint and occurrence`() {
        val id = AutomationId("rule/with space")
        val fingerprint = ApprovalFingerprint("a".repeat(64))
        val intent = AndroidTimeAlarmBackend.identityIntent(context, id).apply {
            putExtra(AndroidTimeAlarmBackend.EXTRA_AUTOMATION_ID, id.value)
            putExtra(AndroidTimeAlarmBackend.EXTRA_APPROVAL_FINGERPRINT, fingerprint.value)
            putExtra(AndroidTimeAlarmBackend.EXTRA_EVENT_AT_MILLIS, 1234L)
        }

        assertEquals(TimeAlarmSignal(id, fingerprint, 1234L), AndroidTimeAlarmBackend.parseSignal(intent))

        intent.putExtra(AndroidTimeAlarmBackend.EXTRA_AUTOMATION_ID, "other")
        assertNull(AndroidTimeAlarmBackend.parseSignal(intent))
    }

    @Test
    fun `reconcile actions map only known system broadcasts`() {
        assertEquals(ReconcileReason.BOOT, reconcileReason(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(ReconcileReason.TIME_CHANGED, reconcileReason(Intent.ACTION_TIME_CHANGED))
        assertEquals(
            ReconcileReason.EXACT_ALARM_PERMISSION_CHANGED,
            reconcileReason(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
        )
        assertNull(reconcileReason("com.example.UNTRUSTED"))
    }
}
