package dev.argus.device

import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.PrivilegedActionPolicy
import dev.argus.engine.runtime.ExecutionId
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuShellException
import java.io.File

/**
 * Azioni privilegiate tipizzate 0.5.1 (vocabolario chiuso al posto di `run_shell`). Ogni metodo è UN
 * comando fisso, costruito come argv e passato a [PrivilegedShell] (Shizuku, Sui o `su`): mai `sh -c`,
 * mai testo concatenato. Interfaccia separata da [DeviceController] perché è un'aggiunta: i doppi di
 * test dei toggle esistenti non devono implementarla.
 */
interface PrivilegedActionController {
    suspend fun appControl(pkg: String, op: AppControlOp, executionId: ExecutionId, priority: Int = 0)
    suspend fun setAirplane(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun setLocation(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun setNfc(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun setBatterySaver(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun lockScreen(executionId: ExecutionId, priority: Int = 0)

    /** Screenshot come PNG (`screencap -p` su stdout): il salvataggio è del chiamante, mai di uno shell. */
    suspend fun screenshot(): ByteArray

    suspend fun appPermission(
        pkg: String,
        permission: String,
        grant: Boolean,
        executionId: ExecutionId,
        priority: Int = 0,
    )
}

/**
 * Comandi (verificati su AOSP/Android 16, `cmd <servizio> help` e sorgenti dei comandi shell):
 * - `am force-stop --user 0 <pkg>`: senza `--user` AOSP usa USER_ALL (anche profili di lavoro/cloni);
 * - `pm disable-user --user 0 <pkg>` / `pm enable --user 0 <pkg>`: help `[--user USER_ID]`;
 * - `pm grant|revoke --user 0 <pkg> <permesso>`: help `[--user USER_ID] PACKAGE PERMISSION`;
 * - `cmd connectivity airplane-mode enable|disable`: passa dal ConnectivityService e spegne davvero le
 *   radio (scrivere `airplane_mode_on` + broadcast non basta su Android 16: nessun ripiego);
 * - `cmd location set-location-enabled true|false`: presente da Android 11 (minSdk 30), quindi nessun
 *   ripiego; senza `--user` vale l'utente corrente (la posizione dell'argomento `--user` cambia tra
 *   le versioni, così si evita del tutto);
 * - `svc nfc enable|disable`: esce 0 anche senza adattatore NFC, perciò la capacità si pubblica solo
 *   sui telefoni con NFC e l'esecutore ricontrolla;
 * - `cmd power set-mode 1|0`: 1 attiva il risparmio energetico, 0 lo spegne (`PowerManagerShellCommand`);
 * - `input keyevent KEYCODE_SLEEP`: spegne lo schermo (non ha effetto a schermo già spento); il blocco
 *   segue le impostazioni di blocco di Android;
 * - `screencap -p`: PNG su stdout (documentazione adb).
 */
class PrivilegedActionTools(
    private val shell: PrivilegedShell,
    cacheDirectory: File,
) : PrivilegedActionController {
    // Cattura e verifica PNG già coperte da DeviceTools.capture(): niente seconda copia.
    private val screen = DeviceTools(shell, cacheDirectory)

    override suspend fun appControl(pkg: String, op: AppControlOp, executionId: ExecutionId, priority: Int) {
        // Seconda barriera dopo validatore ed esecutore: forma del package e bersagli vietati statici.
        require(PrivilegedActionPolicy.appControlTarget(pkg) == PrivilegedActionPolicy.TargetVerdict.OK) {
            "Bersaglio non valido"
        }
        val command = when (op) {
            AppControlOp.FORCE_STOP -> listOf(AM, "force-stop", "--user", USER_ZERO, pkg)
            AppControlOp.FREEZE -> listOf(PM, "disable-user", "--user", USER_ZERO, pkg)
            AppControlOp.UNFREEZE -> listOf(PM, "enable", "--user", USER_ZERO, pkg)
        }
        runChecked("app_control", command, executionId, priority)
    }

    override suspend fun setAirplane(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked(
            "set_airplane",
            listOf(CMD, "connectivity", "airplane-mode", if (on) "enable" else "disable"),
            executionId,
            priority,
        )
    }

    override suspend fun setLocation(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked(
            "set_location",
            listOf(CMD, "location", "set-location-enabled", if (on) "true" else "false"),
            executionId,
            priority,
        )
    }

    override suspend fun setNfc(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked("set_nfc", listOf(SVC, "nfc", if (on) "enable" else "disable"), executionId, priority)
    }

    override suspend fun setBatterySaver(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked(
            "set_battery_saver",
            listOf(CMD, "power", "set-mode", if (on) "1" else "0"),
            executionId,
            priority,
        )
    }

    override suspend fun lockScreen(executionId: ExecutionId, priority: Int) {
        runChecked("lock_screen", listOf(INPUT, "keyevent", "KEYCODE_SLEEP"), executionId, priority)
    }

    override suspend fun screenshot(): ByteArray = screen.capture()

    override suspend fun appPermission(
        pkg: String,
        permission: String,
        grant: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) {
        require(PrivilegedActionPolicy.appPermissionTarget(pkg) == PrivilegedActionPolicy.TargetVerdict.OK) {
            "Bersaglio non valido"
        }
        require(PrivilegedActionPolicy.isRuntimePermission(permission)) { "Permesso non ammesso" }
        runChecked(
            "app_permission",
            listOf(PM, if (grant) "grant" else "revoke", "--user", USER_ZERO, pkg, permission),
            executionId,
            priority,
            // pm esce non zero se il permesso non è dichiarato dall'app o il package non c'è: un solo
            // codice leggibile al posto di `app_permission_exit_255`.
            failureCode = PERMISSION_NOT_APPLIED,
        )
    }

    private suspend fun runChecked(
        operation: String,
        command: List<String>,
        executionId: ExecutionId,
        priority: Int,
        failureCode: String? = null,
    ): ShellResult {
        val result = try {
            shell.run(command = command, priority = priority, executionId = executionId.value)
        } catch (error: ShizukuShellException) {
            throw DeviceToolException(error.code, error)
        }
        if (!result.successful || result.truncated) {
            val rejected = !result.timedOut && result.errorCode == null && result.exitCode != 0
            throw DeviceToolException(failureCode?.takeIf { rejected } ?: codeFor(operation, result))
        }
        return result
    }

    /** Stesso schema di [DeviceTools]: timeout, `<operazione>_exit_<n>` o `<operazione>_failed`. */
    private fun codeFor(operation: String, result: ShellResult): String = when {
        result.timedOut -> ShizukuShellException.TIMEOUT
        result.errorCode != null -> "${operation}_failed"
        result.exitCode in 1..MAX_SHELL_EXIT_CODE -> "${operation}_exit_${result.exitCode}"
        else -> "${operation}_failed"
    }

    companion object {
        /** Codice del Log quando `pm grant|revoke` non ha applicato il cambio. */
        const val PERMISSION_NOT_APPLIED = "permission_not_applied"

        private const val AM = "/system/bin/am"
        private const val PM = "/system/bin/pm"
        private const val CMD = "/system/bin/cmd"
        private const val SVC = "/system/bin/svc"
        private const val INPUT = "/system/bin/input"
        private const val USER_ZERO = "0"
        private const val MAX_SHELL_EXIT_CODE = 255
    }
}
