package dev.argus.shizuku

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Piano di avvio di un comando privilegiato (S8). Al timeout `destroyForcibly()` uccide solo il
 * processo che abbiamo avviato (`sh`), non i figli che ha generato: un `sleep 3600` o un loop
 * lanciato dalla shell sopravviverebbe al timeout. Per poterlo uccidere come ALBERO, il comando
 * parte in una nuova sessione/gruppo di processi tramite `setsid` (toybox): dopo `setsid()` il
 * processo avviato è leader del proprio gruppo e il suo PID è il pgid.
 *
 * [ownGroup] è `true` solo se il comando è stato davvero preceduto da `setsid`; senza, il kill
 * resta quello di prima (solo il processo avviato).
 */
internal class ShellLaunchPlan(val argv: List<String>, val ownGroup: Boolean)

internal object ProcessTreeKiller {
    const val SETSID_PATH = "/system/bin/setsid"

    /** Sceglie il piano: `setsid` davanti al comando se esiste, altrimenti il comando invariato. */
    fun plan(command: List<String>, setsidExists: Boolean): ShellLaunchPlan =
        if (setsidExists) {
            ShellLaunchPlan(listOf(SETSID_PATH) + command, ownGroup = true)
        } else {
            ShellLaunchPlan(command, ownGroup = false)
        }

    fun setsidExists(): Boolean = runCatching { File(SETSID_PATH).canExecute() }.getOrDefault(false)

    /**
     * Termina il comando scaduto. Con un gruppo proprio uccide l'intero gruppo (`kill(-pgid)`);
     * se il PID non è ricavabile o il kill di gruppo fallisce ripiega su `destroyForcibly()`, che
     * viene comunque chiamato per far chiudere sicuramente il `Process` avviato.
     */
    fun terminate(
        process: Process,
        plan: ShellLaunchPlan,
        pidOf: (Process) -> Int? = ::reflectedPid,
        killGroup: (Int) -> Unit = ::killProcessGroup,
    ) {
        if (plan.ownGroup) {
            val pid = runCatching { pidOf(process) }.getOrNull()
            // `kill(-1)` colpirebbe ogni processo raggiungibile: il pgid deve essere un PID reale.
            if (pid != null && pid > 1) {
                runCatching { killGroup(pid) }
            }
        }
        process.destroyForcibly()
    }

    /** PID del processo avviato: `Process.pid()` (API 33+) se c'è, altrimenti il campo `pid`. */
    fun reflectedPid(process: Process): Int? {
        runCatching {
            val method = Process::class.java.getMethod("pid")
            return (method.invoke(process) as Long).toInt()
        }
        return runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrNull()
    }

    private fun killProcessGroup(pgid: Int) {
        Os.kill(-pgid, OsConstants.SIGKILL)
    }
}
