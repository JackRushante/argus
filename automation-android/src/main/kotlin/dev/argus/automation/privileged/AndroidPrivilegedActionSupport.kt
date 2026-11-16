package dev.argus.automation.privileged

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * NFC e app che `app_control` non può toccare su questo telefono (0.7.2):
 *  - TUTTI i gestori HOME installati (visibili grazie al `<queries>` del manifest), non solo quello
 *    predefinito: congelare il launcher di riserva o quello appena scelto lascia senza Home;
 *  - la tastiera predefinita (`Settings.Secure.DEFAULT_INPUT_METHOD`): congelata, non si scrive più;
 *  - il fornitore WebView in uso.
 *
 * Fallisce chiuso: se i gestori HOME non si leggono (eccezione o nessuno visibile) o la tastiera
 * predefinita non si interpreta, [protectedPackages] lancia e l'esecutore rifiuta l'azione.
 */
class AndroidPrivilegedActionEnvironment internal constructor(
    context: Context,
    private val webViewPackage: () -> String?,
) : PrivilegedActionEnvironment {
    constructor(context: Context) : this(context, { WebView.getCurrentWebViewPackage()?.packageName })

    private val packageManager = context.applicationContext.packageManager
    private val resolver = context.applicationContext.contentResolver

    override fun hasNfc(): Boolean =
        runCatching { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) }.getOrDefault(false)

    override fun protectedPackages(): Set<String> = linkedSetOf<String>().apply {
        addAll(launcherPackages())
        defaultInputMethodPackage()?.let(::add)
        webViewPackage()?.let(::add)
    }

    private fun launcherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val all = packageManager.queryIntentActivities(home, 0)
            .mapNotNullTo(linkedSetOf()) { it.activityInfo?.packageName }
        packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != SYSTEM_RESOLVER }
            ?.let(all::add)
        // Un telefono ha sempre almeno un gestore HOME: nessuno visibile vuol dire che la lettura non va.
        check(all.isNotEmpty()) { "no HOME handler visible" }
        return all
    }

    /** Package della tastiera predefinita; null se non ce n'è una impostata. */
    private fun defaultInputMethodPackage(): String? {
        val id = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (id.isNullOrBlank()) return null
        return checkNotNull(ComponentName.unflattenFromString(id)?.packageName) { "unreadable input method" }
    }

    private companion object {
        const val SYSTEM_RESOLVER = "android"
    }
}

/**
 * Salva lo screenshot nella galleria con MediaStore (`Pictures/Argus`): l'app scrive solo un file che
 * crea lei, quindi non serve alcun permesso di archiviazione (Android 10+). Il file resta "in attesa"
 * finché non è completo: un salvataggio interrotto non lascia un'immagine a metà.
 */
class MediaStoreScreenshotStore(context: Context) : ScreenshotStore {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun save(displayName: String, png: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + PrivilegedActionRunner.SCREENSHOT_FOLDER,
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext false
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("no stream")
            stream.use { it.write(png) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (error: CancellationException) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
