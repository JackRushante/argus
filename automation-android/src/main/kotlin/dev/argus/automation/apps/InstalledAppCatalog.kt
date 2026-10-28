package dev.argus.automation.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * Controllo «l'app esiste su questo telefono» (0.7.0). L'approvazione lo usa per avvisare, con un WARNING e
 * mai con un errore, quando una regola nomina un package non installato (`app_not_installed`).
 */
fun interface InstalledPackageCheck {
    /** I package di [packages] che NON risultano installati. Se non si riesce a dirlo, nessuno: meglio un avviso in meno. */
    suspend fun missing(packages: Collection<String>): Set<String>
}

/** Nessun controllo: ogni package si considera installato (default dei test e dei flussi senza telefono). */
object AssumeAllInstalled : InstalledPackageCheck {
    override suspend fun missing(packages: Collection<String>): Set<String> = emptySet()
}

/** Un'app avviabile installata: nome come lo vede l'utente e package. */
data class InstalledAppEntry(val label: String, val packageName: String)

/**
 * Elenco locale delle app avviabili, per «Cambia app» e per scrivere il NOME accanto a ogni package. Resta sul
 * telefono: non viene salvato, inviato al Brain né scritto nel Log.
 */
interface InstalledAppCatalog : InstalledPackageCheck {
    /** App con un'icona di avvio, in ordine alfabetico per nome. Vuoto se il sistema non risponde. */
    suspend fun launchableApps(): List<InstalledAppEntry>

    /** Il package esiste sul telefono (anche se non è avviabile). */
    suspend fun isInstalled(packageName: String): Boolean
}

object EmptyInstalledAppCatalog : InstalledAppCatalog {
    override suspend fun launchableApps(): List<InstalledAppEntry> = emptyList()
    override suspend fun isInstalled(packageName: String): Boolean = true
    override suspend fun missing(packages: Collection<String>): Set<String> = emptySet()
}

/**
 * Legge le app avviabili da `LauncherApps` (la stessa fonte del resolver per il compilatore) e controlla gli
 * altri package con `PackageManager`. Non serve `QUERY_ALL_PACKAGES`: sono visibili le app avviabili; un package
 * di sistema senza icona può risultare «non trovato», ed è per questo che il testo dell'avviso parla di app non
 * installata e propone di cambiarla, senza bloccare niente.
 */
class AndroidInstalledAppCatalog @Inject constructor(
    @ApplicationContext context: Context,
) : InstalledAppCatalog {
    private val appContext = context.applicationContext

    @Volatile
    private var cached: Pair<Long, List<InstalledAppEntry>>? = null

    override suspend fun launchableApps(): List<InstalledAppEntry> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - it.first < CACHE_MILLIS }?.let { return@withContext it.second }
        val apps = runCatching {
            val packageManager = appContext.packageManager
            appContext.getSystemService(LauncherApps::class.java)
                .getActivityList(null, Process.myUserHandle())
                .map { activity ->
                    InstalledAppEntry(
                        label = activity.applicationInfo.loadLabel(packageManager).toString(),
                        packageName = activity.applicationInfo.packageName,
                    )
                }
                .distinctBy(InstalledAppEntry::packageName)
                .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
        }.getOrDefault(emptyList())
        // Un elenco vuoto è più probabile un errore che un telefono senza app: non lo si ricorda.
        if (apps.isNotEmpty()) cached = now to apps
        apps
    }

    override suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.Default) {
        runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
    }

    override suspend fun missing(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val known = launchableApps().mapTo(hashSetOf()) { it.packageName }
        // Senza l'elenco non si può dire nulla: nessun avviso, mai un falso allarme.
        if (known.isEmpty()) return emptySet()
        return packages.filterTo(linkedSetOf()) { it !in known && !isInstalled(it) }
    }

    private companion object {
        const val CACHE_MILLIS = 10_000L
    }
}
