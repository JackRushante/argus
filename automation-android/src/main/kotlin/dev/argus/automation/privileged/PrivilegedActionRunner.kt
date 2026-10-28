package dev.argus.automation.privileged

import dev.argus.device.PrivilegedActionController
import dev.argus.engine.model.Action
import dev.argus.engine.model.PrivilegedActionPolicy
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dove finisce uno screenshot: in una cartella dell'app visibile all'utente (Pictures/Argus). */
fun interface ScreenshotStore {
    /** Salva [png] con il nome visibile [displayName]. Vero se il file è stato scritto per intero. */
    suspend fun save(displayName: String, png: ByteArray): Boolean
}

/** Ciò che solo il telefono sa e che i controlli a runtime delle azioni privilegiate richiedono. */
interface PrivilegedActionEnvironment {
    /** Il telefono ha l'NFC (`FEATURE_NFC`). */
    fun hasNfc(): Boolean

    /** Package del launcher in uso (o, se non è determinabile, di tutti i launcher installati). */
    fun launcherPackages(): Set<String>
}

/**
 * Esegue le otto azioni privilegiate tipizzate 0.5.1 dopo un'ultima verifica sul telefono: bersagli
 * vietati (Argus, package di sistema essenziali, launcher in uso), NFC presente, permesso nell'elenco
 * chiuso. I comandi sono quelli di [PrivilegedActionController]: sempre via `PrivilegedShell`.
 *
 * Le eccezioni dei comandi ([dev.argus.device.DeviceToolException] e simili) non si catturano qui: le
 * traduce in codici del Log il confine di [dev.argus.automation.ShizukuActionExecutor].
 */
class PrivilegedActionRunner(
    private val tools: PrivilegedActionController,
    private val screenshots: ScreenshotStore,
    private val environment: PrivilegedActionEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun execute(action: Action, ctx: FireContext): ActionResult = when (action) {
        is Action.AppControl -> when (PrivilegedActionPolicy.appControlTarget(action.pkg, environment.launcherPackages())) {
            PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID -> ActionResult.Failure(ACTION_INVALID)
            PrivilegedActionPolicy.TargetVerdict.FORBIDDEN -> ActionResult.Failure(TARGET_FORBIDDEN)
            PrivilegedActionPolicy.TargetVerdict.OK -> {
                tools.appControl(action.pkg, action.op, ctx.executionId, ctx.priority)
                ActionResult.Success
            }
        }
        is Action.AppPermission -> when {
            !PrivilegedActionPolicy.isRuntimePermission(action.permission) -> ActionResult.Failure(ACTION_INVALID)
            else -> when (PrivilegedActionPolicy.appPermissionTarget(action.pkg)) {
                PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID -> ActionResult.Failure(ACTION_INVALID)
                PrivilegedActionPolicy.TargetVerdict.FORBIDDEN -> ActionResult.Failure(TARGET_FORBIDDEN)
                PrivilegedActionPolicy.TargetVerdict.OK -> {
                    tools.appPermission(action.pkg, action.permission, action.grant, ctx.executionId, ctx.priority)
                    ActionResult.Success
                }
            }
        }
        is Action.SetAirplane -> {
            tools.setAirplane(action.on, ctx.executionId, ctx.priority)
            ActionResult.Success
        }
        is Action.SetLocation -> {
            tools.setLocation(action.on, ctx.executionId, ctx.priority)
            ActionResult.Success
        }
        is Action.SetNfc -> if (!environment.hasNfc()) {
            ActionResult.Failure(NFC_UNAVAILABLE)
        } else {
            tools.setNfc(action.on, ctx.executionId, ctx.priority)
            ActionResult.Success
        }
        is Action.SetBatterySaver -> {
            tools.setBatterySaver(action.on, ctx.executionId, ctx.priority)
            ActionResult.Success
        }
        is Action.LockScreen -> {
            tools.lockScreen(ctx.executionId, ctx.priority)
            ActionResult.Success
        }
        is Action.Screenshot -> {
            val png = tools.screenshot()
            if (screenshots.save(screenshotName(clock(), zone()), png)) {
                ActionResult.Success
            } else {
                ActionResult.Failure(SCREENSHOT_SAVE_FAILED)
            }
        }
        else -> ActionResult.Failure(ACTION_INVALID)
    }

    companion object {
        /** Cartella visibile all'utente (MediaStore `Pictures/Argus`). */
        const val SCREENSHOT_FOLDER = "Argus"

        const val TARGET_FORBIDDEN = "target_forbidden"
        const val NFC_UNAVAILABLE = "nfc_unavailable"
        const val SCREENSHOT_SAVE_FAILED = "screenshot_save_failed"
        private const val ACTION_INVALID = "action_invalid"

        private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        /** `Argus-20261028-153012.png`: data e ora locali, nessun percorso e nessun dato della regola. */
        fun screenshotName(epochMillis: Long, zone: ZoneId): String =
            "Argus-" + NAME_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) + ".png"
    }
}
