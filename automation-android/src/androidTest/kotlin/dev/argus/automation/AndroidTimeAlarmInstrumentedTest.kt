package dev.argus.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTimeAlarmInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun mergedManifestDeclaresExactAlarmSpecialAccess() {
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)
    }

    @Test
    fun flexibleAlarmSchedulesAndCancelsOnRealAlarmManager() {
        assertScheduleMode(
            id = AutomationId("instrumented-flexible"),
            precision = TimePrecision.FLEXIBLE,
            expected = ScheduledAlarmMode.INEXACT,
        )
    }

    @Test
    fun exactAlarmUsesCurrentSpecialAccessOrFallsBackSafely() {
        val backend = AndroidTimeAlarmBackend(context)
        assertScheduleMode(
            id = AutomationId("instrumented-exact"),
            precision = TimePrecision.EXACT,
            expected = if (backend.canScheduleExact()) {
                ScheduledAlarmMode.EXACT
            } else {
                ScheduledAlarmMode.INEXACT
            },
            backend = backend,
        )
    }

    private fun assertScheduleMode(
        id: AutomationId,
        precision: TimePrecision,
        expected: ScheduledAlarmMode,
        backend: AndroidTimeAlarmBackend = AndroidTimeAlarmBackend(context),
    ) {
        val wakeAt = System.currentTimeMillis() + 3_600_000L
        try {
            assertEquals(
                expected,
                backend.schedule(
                    TimeAlarmRegistration(
                        automationId = id,
                        approvalFingerprint = ApprovalFingerprint("d".repeat(64)),
                        eventAtMillis = wakeAt,
                        requestedPrecision = precision,
                    ),
                ),
            )
        } finally {
            backend.cancel(id)
        }
    }
}
