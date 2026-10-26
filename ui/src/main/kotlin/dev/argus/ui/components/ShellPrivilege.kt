package dev.argus.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Con quali privilegi girano i comandi shell delle regole (0.3.6, D10). Shizuku avviato da adb dà
 * i permessi shell (UID 2000); su un telefono con root può girare come root. Finché il servizio
 * privilegiato non è collegato non lo sappiamo: [UNKNOWN] non afferma né l'uno né l'altro.
 */
enum class ShellPrivilege { UNKNOWN, SHELL, ROOT }

/**
 * Privilegio corrente, letto da `ShellCommandBlock` in approvazione e nel Dettaglio. Lo fornisce
 * l'host di navigazione una volta sola, così lo vedono tutte le schermate. Senza fornitore vale
 * [ShellPrivilege.UNKNOWN].
 */
val LocalShellPrivilege = staticCompositionLocalOf { ShellPrivilege.UNKNOWN }

/** `null` = identità non nota, `false` = shell (UID 2000), `true` = root. */
fun shellPrivilegeOf(runsAsRoot: Boolean?): ShellPrivilege = when (runsAsRoot) {
    null -> ShellPrivilege.UNKNOWN
    false -> ShellPrivilege.SHELL
    true -> ShellPrivilege.ROOT
}
