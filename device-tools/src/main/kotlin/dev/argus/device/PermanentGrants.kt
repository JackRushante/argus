package dev.argus.device

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShizukuShellException
import kotlinx.coroutines.CancellationException

/**
 * «Shizuku una volta sola» (0.5.0): permessi che Argus si concede da sé con un backend privilegiato
 * (Shizuku o root) e che restano dopo il riavvio. L'accesso alle notifiche (`allow_listener`) NON è
 * in elenco di proposito: resta la schermata di sistema, è il permesso più delicato.
 */
enum class PermanentGrant(val id: String) {
    /** `write_setting` su SECURE e GLOBAL con scrittura diretta, senza Shizuku. */
    WRITE_SECURE_SETTINGS("write_secure_settings"),

    /** «Modifica impostazioni di sistema»: luminosità e rotazione senza il passaggio manuale. */
    WRITE_SETTINGS("write_settings"),

    /** Accesso ai dati di utilizzo: la condizione `app_in_foreground` senza Shizuku. */
    USAGE_STATS("usage_stats"),

    /** Accesso a Non disturbare. */
    DND_ACCESS("dnd_access"),

    /**
     * 0.5.1: il servizio di Accessibilità di Argus, per le macro sullo schermo. A differenza degli
     * altri NON si concede da solo con un comando: il dialogo lo propone spuntato a mano, e il
     * controller prima legge l'elenco dei servizi già attivi e AGGIUNGE il proprio ([command] è solo
     * l'ultimo passo, l'interruttore generale).
     */
    ACCESSIBILITY("accessibility"),
    ;

    /** Il comando esatto (argv, senza shell) da eseguire col backend privilegiato. */
    fun command(packageName: String): List<String> = when (this) {
        WRITE_SECURE_SETTINGS ->
            listOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS")
        WRITE_SETTINGS -> listOf("appops", "set", packageName, "WRITE_SETTINGS", "allow")
        USAGE_STATS -> listOf("appops", "set", packageName, "GET_USAGE_STATS", "allow")
        DND_ACCESS -> listOf("cmd", "notification", "allow_dnd", packageName)
        ACCESSIBILITY -> listOf("settings", "put", "secure", AccessibilityEnablement.SETTING_ENABLED, "1")
    }

    companion object {
        fun fromId(id: String): PermanentGrant? = entries.firstOrNull { it.id == id }
    }
}

enum class GrantOutcome {
    /** Il comando è andato a buon fine (l'effetto reale si rilegge con [PermanentGrantStatus]). */
    SENT,

    /** Il comando è partito ma ha fallito. */
    FAILED,

    /** Nessun backend privilegiato ha risposto. */
    UNAVAILABLE,
}

class PermanentGrantsController(
    private val shell: PrivilegedShell,
    private val packageName: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Package non valido" }
    }

    /** Esegue, nell'ordine fisso di [PermanentGrant.entries], solo le voci scelte. */
    suspend fun grant(selected: Set<PermanentGrant>): Map<PermanentGrant, GrantOutcome> {
        val outcomes = linkedMapOf<PermanentGrant, GrantOutcome>()
        for (grant in PermanentGrant.entries) {
            if (grant !in selected) continue
            outcomes[grant] = try {
                if (grant == PermanentGrant.ACCESSIBILITY) {
                    enableAccessibility()
                } else {
                    val result = shell.run(grant.command(packageName), timeoutMillis = timeoutMillis)
                    if (result.successful) GrantOutcome.SENT else GrantOutcome.FAILED
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ShizukuShellException) {
                GrantOutcome.UNAVAILABLE
            } catch (_: Exception) {
                GrantOutcome.FAILED
            }
        }
        return outcomes
    }

    /**
     * Accende il servizio di Accessibilità di Argus SENZA toccare gli altri: legge l'elenco dei servizi
     * già attivi, ci aggiunge il proprio in coda (mai una sovrascrittura), poi accende l'interruttore
     * generale. Se l'elenco letto contiene caratteri inattesi non scrive niente.
     */
    private suspend fun enableAccessibility(): GrantOutcome {
        val read = shell.run(
            listOf("settings", "get", "secure", AccessibilityEnablement.SETTING_SERVICES),
            timeoutMillis = timeoutMillis,
        )
        if (!read.successful) return GrantOutcome.FAILED
        val updated = AccessibilityEnablement.withService(read.stdoutText, packageName)
        if (!AccessibilityEnablement.isSafeValue(updated)) return GrantOutcome.FAILED
        if (updated != read.stdoutText.trim()) {
            val put = shell.run(
                listOf("settings", "put", "secure", AccessibilityEnablement.SETTING_SERVICES, updated),
                timeoutMillis = timeoutMillis,
            )
            if (!put.successful) return GrantOutcome.FAILED
        }
        val enable = shell.run(PermanentGrant.ACCESSIBILITY.command(packageName), timeoutMillis = timeoutMillis)
        return if (enable.successful) GrantOutcome.SENT else GrantOutcome.FAILED
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** Letture dello stato REALE di ogni permesso: l'unica verità per il probe e per Sistema. */
object PermanentGrantStatus {
    fun secureSettings(context: Context): Boolean = runCatching {
        context.applicationContext.checkSelfPermission(SECURE_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun writeSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context.applicationContext) }.getOrDefault(false)

    fun usageStats(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val appOps = app.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName,
        )
        if (mode == AppOpsManager.MODE_DEFAULT) {
            app.checkSelfPermission(USAGE_PERMISSION) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }.getOrDefault(false)

    fun dndAccess(context: Context): Boolean = runCatching {
        context.applicationContext.getSystemService(NotificationManager::class.java)
            .isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    /** Il servizio di Accessibilità di Argus è nell'elenco di sistema e l'interruttore generale è acceso. */
    fun accessibility(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val resolver = app.contentResolver
        AccessibilityEnablement.isEnabled(
            servicesValue = Settings.Secure.getString(resolver, AccessibilityEnablement.SETTING_SERVICES),
            accessibilityEnabled = Settings.Secure.getInt(resolver, AccessibilityEnablement.SETTING_ENABLED, 0),
            packageName = app.packageName,
        )
    }.getOrDefault(false)

    fun read(context: Context): Map<PermanentGrant, Boolean> = mapOf(
        PermanentGrant.WRITE_SECURE_SETTINGS to secureSettings(context),
        PermanentGrant.WRITE_SETTINGS to writeSettings(context),
        PermanentGrant.USAGE_STATS to usageStats(context),
        PermanentGrant.DND_ACCESS to dndAccess(context),
        PermanentGrant.ACCESSIBILITY to accessibility(context),
    )

    private const val SECURE_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    private const val USAGE_PERMISSION = "android.permission.PACKAGE_USAGE_STATS"
}
