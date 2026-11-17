package dev.argus.automation.telephony

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.argus.automation.base.ActivityStartBlockedException
import dev.argus.automation.base.isProcessInForeground
import dev.argus.device.DeviceToolException
import dev.argus.engine.model.Action
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.runtime.FireContext
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShizukuShellException

/** L'Intent Android di un `send_intent` approvato, già controllato. */
internal fun Action.SendIntent.toAndroidIntent(): Intent {
    val intent = Intent()
    action?.let(intent::setAction)
    val target = pkg
    val component = cls
    when {
        target != null && component != null -> intent.component = ComponentName(target, component)
        target != null -> intent.setPackage(target)
    }
    val uri = data?.let(Uri::parse)
    when {
        uri != null && mimeType != null -> intent.setDataAndType(uri, mimeType)
        uri != null -> intent.data = uri
        mimeType != null -> intent.type = mimeType
    }
    extras.forEach { extra ->
        when (extra.type) {
            IntentExtraType.STRING -> intent.putExtra(extra.name, extra.value)
            IntentExtraType.INT -> intent.putExtra(extra.name, extra.value.toInt())
            IntentExtraType.LONG -> intent.putExtra(extra.name, extra.value.toLong())
            IntentExtraType.BOOLEAN -> intent.putExtra(extra.name, extra.value.toBooleanStrict())
            IntentExtraType.FLOAT -> intent.putExtra(extra.name, extra.value.toFloat())
        }
    }
    return intent
}

/** Argus ha il ruolo «filtro chiamate» (Android 10+): solo allora Android chiama il suo servizio di filtro. */
fun callScreeningRoleHeld(context: Context): Boolean = runCatching {
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
}.getOrDefault(false)

/** La chiamata a [number] come la avvierebbe il telefono (permesso `CALL_PHONE`). */
internal fun callIntent(number: String): Intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))

class AndroidTelephonySurface(context: Context) : TelephonySurface {
    private val appContext = context.applicationContext

    override val ownPackage: String = appContext.packageName

    override fun hasTelephony(): Boolean = hasTelephony(appContext)

    override fun canSendSms(): Boolean = granted(Manifest.permission.SEND_SMS)

    override fun canCall(): Boolean = granted(Manifest.permission.CALL_PHONE)

    override fun sendSms(number: String, text: String) {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: throw DeviceToolException(TelephonyActionRunner.TELEPHONY_UNAVAILABLE)
        val parts = manager.divideMessage(text)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            manager.sendTextMessage(number, null, text, null, null)
        }
    }

    override fun placeCall(number: String) {
        if (!isProcessInForeground()) throw ActivityStartBlockedException()
        try {
            appContext.startActivity(callIntent(number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: SecurityException) {
            throw DeviceToolException(TelephonyActionRunner.CALL_PERMISSION_MISSING, error)
        } catch (error: Exception) {
            throw ActivityStartBlockedException(error)
        }
    }

    override fun receivers(intent: Action.SendIntent): Set<String> {
        val android = intent.toAndroidIntent()
        val packages = appContext.packageManager
        val resolved = when (intent.kind) {
            IntentKind.BROADCAST -> packages.queryBroadcastReceivers(android, 0).map { it.activityInfo.packageName }
            IntentKind.ACTIVITY -> packages.queryIntentActivities(android, 0).map { it.activityInfo.packageName }
            IntentKind.SERVICE -> packages.queryIntentServices(android, 0).map { it.serviceInfo.packageName }
        }.toMutableSet()
        if (reachesOwnRuntimeReceiver(intent)) resolved += ownPackage
        return resolved
    }

    override fun sendBroadcast(intent: Action.SendIntent) {
        appContext.sendBroadcast(intent.toAndroidIntent())
    }

    override fun startActivity(intent: Action.SendIntent): Boolean {
        if (!isProcessInForeground()) throw ActivityStartBlockedException()
        return try {
            appContext.startActivity(intent.toAndroidIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (error: Exception) {
            throw ActivityStartBlockedException(error)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Le azioni dei receiver che Argus registra a runtime (`registerReceiver`): il PackageManager non li
         * conosce, quindi un broadcast senza package con una di queste azioni si considera rivolto ad Argus.
         * Chi aggiunge un `registerReceiver` aggiunge qui le sue azioni (OwnRuntimeReceiversTest lo ricorda).
         */
        val OWN_RUNTIME_RECEIVER_ACTIONS: Set<String> = setOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
            AudioManager.RINGER_MODE_CHANGED_ACTION,
        )

        /** Un broadcast senza package raggiungerebbe un receiver runtime di Argus (azione confrontata per intero). */
        fun reachesOwnRuntimeReceiver(intent: Action.SendIntent): Boolean =
            intent.kind == IntentKind.BROADCAST && intent.pkg == null &&
                OWN_RUNTIME_RECEIVER_ACTIONS.any { it.equals(intent.action, ignoreCase = true) }

        fun hasTelephony(context: Context): Boolean = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }.getOrDefault(false)
    }
}

/**
 * `am` col backend privilegiato. `am start` può uscire con 0 anche quando non trova l'activity: si legge
 * l'uscita e «Error» diventa `intent_unresolved`. Codici senza contenuti, come DeviceTools.
 */
class ShellPrivilegedAm(private val shell: PrivilegedShell) : PrivilegedAm {
    override suspend fun run(operation: String, argv: List<String>, ctx: FireContext) {
        val result = try {
            shell.run(command = argv, priority = ctx.priority, executionId = ctx.executionId.value)
        } catch (error: ShizukuShellException) {
            throw DeviceToolException(error.code, error)
        }
        when {
            result.timedOut -> throw DeviceToolException(ShizukuShellException.TIMEOUT)
            !result.successful || result.truncated -> throw DeviceToolException("${operation}_failed")
            ERROR_MARKERS.any { it in result.stdoutText || it in result.stderrText } ->
                throw DeviceToolException(TelephonyActionRunner.INTENT_UNRESOLVED)
        }
    }

    private companion object {
        val ERROR_MARKERS = listOf("Error:", "Error type", "Exception")
    }
}
