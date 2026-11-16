package dev.argus.automation.privileged

import dev.argus.device.PrivilegedActionController
import dev.argus.engine.model.Action
import dev.argus.engine.model.PrivilegedActionPolicy
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import kotlinx.coroutines.CancellationException
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

    /**
     * Package che `app_control` non può toccare su questo telefono, oltre all'elenco fisso: tutti i
     * launcher installati, la tastiera predefinita, il fornitore WebView in uso. Lancia se non si
     * riescono a leggere: l'esecutore allora rifiuta l'azione (fallisce chiuso).
     */
    fun protectedPackages(): Set<String>
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
        is Action.AppControl -> appControl(action, ctx)
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
            val at = clock()
            val zoneId = zone()
            if (screenshots.save(screenshotName(at, zoneId), png)) {
                // Il nome del file arriva al Log come codice chiuso (data e ora), mai come testo libero.
                ActionResult.SuccessWithDetail(ScreenshotLogDetail.code(at, zoneId))
            } else {
                ActionResult.Failure(SCREENSHOT_SAVE_FAILED)
            }
        }
        else -> ActionResult.Failure(ACTION_INVALID)
    }

    /**
     * Prima l'elenco fisso, poi quello letto sul telefono. Se il telefono non dice quali app proteggere
     * l'azione non parte: meglio un rifiuto che congelare il launcher o la tastiera.
     */
    private suspend fun appControl(action: Action.AppControl, ctx: FireContext): ActionResult {
        when (PrivilegedActionPolicy.appControlTarget(action.pkg)) {
            PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID -> return ActionResult.Failure(ACTION_INVALID)
            PrivilegedActionPolicy.TargetVerdict.FORBIDDEN -> return ActionResult.Failure(TARGET_FORBIDDEN)
            PrivilegedActionPolicy.TargetVerdict.OK -> Unit
        }
        val deviceProtected = try {
            environment.protectedPackages()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActionResult.Failure(PROTECTED_APPS_UNKNOWN)
        }
        if (PrivilegedActionPolicy.appControlTarget(action.pkg, deviceProtected) != PrivilegedActionPolicy.TargetVerdict.OK) {
            return ActionResult.Failure(TARGET_FORBIDDEN)
        }
        tools.appControl(action.pkg, action.op, ctx.executionId, ctx.priority)
        return ActionResult.Success
    }

    companion object {
        /** Cartella visibile all'utente (MediaStore `Pictures/Argus`). */
        const val SCREENSHOT_FOLDER = "Argus"

        const val TARGET_FORBIDDEN = "target_forbidden"
        const val PROTECTED_APPS_UNKNOWN = "protected_apps_unknown"
        const val NFC_UNAVAILABLE = "nfc_unavailable"
        const val SCREENSHOT_SAVE_FAILED = "screenshot_save_failed"
        private const val ACTION_INVALID = "action_invalid"

        private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        /** `Argus-20261028-153012.png`: data e ora locali, nessun percorso e nessun dato della regola. */
        fun screenshotName(epochMillis: Long, zone: ZoneId): String =
            "Argus-" + NAME_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) + ".png"
    }
}

/**
 * Il nome del file di uno screenshot nel Log. Il journal conserva solo codici chiusi (`snake_case`), quindi il
 * nome `Argus-20261028-153012.png` viaggia come `screenshot_20261028_153012` (la stessa data e ora, nessun
 * percorso e nessun dato della regola) e il Log lo ricostruisce con [fileName].
 */
object ScreenshotLogDetail {
    private const val PREFIX = "screenshot_"
    private val CODE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    private val CODE = Regex("""^screenshot_(\d{8})_(\d{6})${'$'}""")

    /** Codice del journal per lo screenshot salvato all'istante [epochMillis] nel fuso [zone]. */
    fun code(epochMillis: Long, zone: ZoneId): String =
        PREFIX + CODE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Nome del file salvato in Pictures/Argus, oppure null se [code] non e' un codice di screenshot. */
    fun fileName(code: String?): String? {
        val match = code?.let(CODE::matchEntire) ?: return null
        return "Argus-${match.groupValues[1]}-${match.groupValues[2]}.png"
    }

    /** Percorso mostrato nel Log: cartella visibile all'utente piu' nome del file. */
    fun displayPath(code: String?): String? =
        fileName(code)?.let { "Pictures/" + PrivilegedActionRunner.SCREENSHOT_FOLDER + "/" + it }
}
