package dev.argus.automation.directboot

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.automation.base.AndroidBaseActionSurface
import dev.argus.engine.directboot.DirectBootRunner
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant

/*
 * 0.8, rules before the first unlock. These two receivers are the ONLY direct-boot-aware components of Argus. They run
 * in their own process (`:locked`, see the manifest), where `ArgusApplication` skips its whole start-up: no Hilt graph,
 * no Room, no preferences, no Keystore, no Shizuku. Not exported: only the system (LOCKED_BOOT_COMPLETED, a protected
 * broadcast) and Argus's own explicit, immutable PendingIntent reach them; the action is checked anyway.
 */

/** Opens the locked session of this boot. */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        runLocked(context, "locked_boot") { it.onLockedBoot() }
    }
}

/** The session's alarm. */
class LockedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockedBoot.ACTION_ALARM) return
        runLocked(context, "locked_alarm") { it.onAlarm() }
    }
}

private fun BroadcastReceiver.runLocked(context: Context, label: String, block: suspend (LockedSession) -> Unit) {
    val pending = goAsync()
    val appContext = context.applicationContext
    Thread({
        try {
            runBlocking { block(LockedBoot.session(appContext)) }
        } catch (error: Exception) {
            Log.w(LockedBoot.TAG, "$label failed: ${error::class.java.simpleName}")
        } finally {
            pending.finish()
        }
    }, "argus-$label").start()
}

/** The process of the two receivers above (`android:process=":locked"` in the manifest). */
object LockedProcess {
    const val SUFFIX = ":locked"

    fun isCurrent(context: Context): Boolean = Application.getProcessName() == context.packageName + SUFFIX
}

/** Wiring of the locked path: only device-encrypted storage and system managers. */
object LockedBoot {
    internal const val TAG = "ArgusBeforeUnlock"
    const val ACTION_ALARM = "dev.argus.automation.action.BEFORE_UNLOCK_ALARM"

    fun session(context: Context): LockedSession {
        val clock = Clock.systemDefaultZone()
        val base = AndroidBaseActionExecutor(
            // Never starts an activity here: the process is in the background and the phone is locked.
            AndroidBaseActionSurface(context, isProcessForeground = { false }),
        )
        return LockedSession(
            storage = DirectBootStorage(context),
            validator = DraftValidator(knownTools = emptySet(), clock = clock),
            runner = DirectBootRunner(ConditionEvaluator(clock), LockedActionRunner(base)) { clock.millis() },
            alarms = AndroidLockedAlarms(context),
            isUserLocked = { isUserLocked(context) },
            bootCount = { bootCount(context) },
            now = { Instant.now(clock) },
        )
    }

    fun isUserLocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked != true

    fun bootCount(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrNull()?.takeIf { it >= 0 }
}

/** AlarmManager RTC_WAKEUP towards [LockedAlarmReceiver]; exact only when asked and allowed. */
class AndroidLockedAlarms(context: Context) : LockedAlarms {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(atMillis: Long, exact: Boolean) {
        val manager = alarmManager ?: return
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exact && exactAllowed) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                return
            } catch (_: SecurityException) {
                // Permission revoked meanwhile: inexact, like the normal runtime.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    override fun cancel() {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager?.cancel(operation)
        operation.cancel()
    }

    private companion object {
        const val REQUEST_CODE = 0

        fun intent(context: Context): Intent = Intent(context, LockedAlarmReceiver::class.java)
            .setAction(LockedBoot.ACTION_ALARM)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
}
