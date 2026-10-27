package dev.argus.shizuku

/**
 * Chi sta fornendo ORA i comandi privilegiati (0.5.0). Serve a Sistema per dirlo all'utente e ai
 * test per coprire ogni combinazione. Non decide nulla: la scelta del percorso è di
 * [RoutedPrivilegedShell].
 */
enum class PrivilegedBackend {
    /** Nessun backend: né Shizuku autorizzato né root verificato. */
    NONE,

    /** Shizuku autorizzato, identità del servizio non ancora nota. */
    SHIZUKU,

    /** Shizuku autorizzato e avviato da adb (uid shell, 2000). */
    SHIZUKU_SHELL,

    /** Shizuku autorizzato e avviato come root (uid 0). */
    SHIZUKU_ROOT,

    /** Sui: Shizuku integrato come modulo Magisk/KernelSU (gira come root). */
    SUI,

    /** Backend root via `su`, attivato dall'utente e verificato (uid 0). */
    ROOT,
    ;

    /** Vero se almeno un backend può eseguire comandi privilegiati. */
    val available: Boolean get() = this != NONE

    companion object {
        /**
         * Shizuku (e Sui, che passa dallo stesso gateway) ha la precedenza sul root `su`: con
         * Shizuku autorizzato il comportamento è quello di sempre. Il root conta solo se è attivo
         * E verificato ([rootActive]).
         */
        fun resolve(
            status: ShizukuGatewayStatus,
            identity: ShizukuIdentity?,
            sui: Boolean,
            rootActive: Boolean,
        ): PrivilegedBackend = when {
            status == ShizukuGatewayStatus.AUTHORIZED -> when {
                sui -> SUI
                identity == null -> SHIZUKU
                identity.runsAsRoot -> SHIZUKU_ROOT
                else -> SHIZUKU_SHELL
            }
            rootActive -> ROOT
            else -> NONE
        }
    }
}

/**
 * Sceglie il backend a ogni comando: Shizuku se autorizzato, altrimenti `su` se attivo e verificato,
 * altrimenti Shizuku, che fallisce con il solito `shizuku_unavailable`. Non ritenta su un altro
 * backend dopo un errore: un comando già partito non va rieseguito.
 */
class RoutedPrivilegedShell(
    private val shizuku: PrivilegedShell,
    private val root: PrivilegedShell,
    private val shizukuAvailable: () -> Boolean,
    private val rootActive: () -> Boolean,
) : PrivilegedShell {
    private fun pick(): PrivilegedShell = when {
        runCatching(shizukuAvailable).getOrDefault(false) -> shizuku
        runCatching(rootActive).getOrDefault(false) -> root
        else -> shizuku
    }

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = pick().run(command, priority, timeoutMillis, maxOutputBytes, executionId)

    override suspend fun runToFile(
        command: List<String>,
        destination: java.io.File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = pick().runToFile(
        command,
        destination,
        priority,
        timeoutMillis,
        maxOutputBytes,
        executionId,
    )
}
