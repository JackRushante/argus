package dev.argus.automation.privileged

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * NFC e launcher del telefono. Il launcher in uso si risolve con l'intent HOME (visibile grazie al
 * `<queries>` del manifest); se Android risponde col selettore di sistema (`android`, nessun launcher
 * predefinito) o non risponde, si proteggono tutti i launcher installati: meglio un rifiuto in più che
 * lasciare congelare l'unico modo di tornare alla Home.
 */
class AndroidPrivilegedActionEnvironment(context: Context) : PrivilegedActionEnvironment {
    private val packageManager = context.applicationContext.packageManager

    override fun hasNfc(): Boolean =
        runCatching { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) }.getOrDefault(false)

    override fun launcherPackages(): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != SYSTEM_RESOLVER }
        if (default != null) {
            setOf(default)
        } else {
            packageManager.queryIntentActivities(home, 0).mapNotNullTo(linkedSetOf()) { it.activityInfo?.packageName }
        }
    }.getOrDefault(emptySet())

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
