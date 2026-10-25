package dev.argus.automation

import android.content.Context
import android.os.Build

/**
 * Versione e origine dell'installazione di Argus. Sono valori semplici (non un `Context`) perché i
 * ViewModel che li usano restino testabili su JVM; [read] li legge una volta dal sistema.
 */
data class AppInstallInfo(
    val versionName: String?,
    /** `installingPackageName` di Android (null = sconosciuto, es. installazione via adb). */
    val installerPackage: String?,
    val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /** true se l'app è stata installata da un client F-Droid noto. */
    val fromFDroid: Boolean get() = installerPackage in FDROID_CLIENTS

    /** true se l'app è stata installata dallo store di sistema (Google Play). */
    val fromSystemStore: Boolean get() = installerPackage in SYSTEM_STORES

    /** Etichetta breve dell'origine, mostrata in Sistema accanto alla versione. */
    val sourceLabel: String get() = if (fromFDroid) "F-Droid" else "APK"

    /**
     * Su Android 13+ un'app installata da APK (né da un client F-Droid noto né dallo store di
     * sistema) può vedersi bloccare l'accesso alle notifiche con «Impostazione con restrizioni».
     */
    val mayHaveRestrictedSettings: Boolean
        get() = sdkInt >= 33 && !fromFDroid && !fromSystemStore

    companion object {
        val FDROID_CLIENTS: Set<String> = setOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
            "eu.bubu1.fdroidclassic",
        )

        val SYSTEM_STORES: Set<String> = setOf("com.android.vending")

        /** Legge versione e installer dal sistema; ogni errore degrada a «sconosciuto». */
        fun read(context: Context): AppInstallInfo {
            val packageManager = context.packageManager
            val version = runCatching {
                packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
            val installer = runCatching {
                packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
            return AppInstallInfo(versionName = version, installerPackage = installer)
        }
    }
}
