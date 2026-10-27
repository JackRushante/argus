package dev.argus.device

import android.content.Context
import android.provider.Settings
import dev.argus.engine.model.SettingNamespace

enum class DirectWriteResult {
    WRITTEN,

    /** Manca il permesso: `WRITE_SECURE_SETTINGS` (SECURE/GLOBAL) o «Modifica impostazioni di sistema» (SYSTEM). */
    NOT_PERMITTED,

    /** Il permesso c'è ma Android non ha accettato la scrittura. */
    FAILED,
}

/**
 * Scrittura diretta delle impostazioni col `ContentResolver` (0.5.0), senza shell e senza Shizuku:
 * possibile solo dopo i permessi permanenti. SYSTEM resta legato a «Modifica impostazioni di
 * sistema», SECURE e GLOBAL a `WRITE_SECURE_SETTINGS`.
 */
interface DirectSettings {
    /** Il permesso che serve a [namespace] è concesso ORA. */
    fun canWrite(namespace: SettingNamespace): Boolean

    fun write(namespace: SettingNamespace, key: String, value: String): DirectWriteResult
}

class AndroidDirectSettings(context: Context) : DirectSettings {
    private val appContext = context.applicationContext

    override fun canWrite(namespace: SettingNamespace): Boolean = when (namespace) {
        SettingNamespace.SYSTEM -> PermanentGrantStatus.writeSettings(appContext)
        SettingNamespace.SECURE, SettingNamespace.GLOBAL -> PermanentGrantStatus.secureSettings(appContext)
    }

    override fun write(namespace: SettingNamespace, key: String, value: String): DirectWriteResult {
        if (!canWrite(namespace)) return DirectWriteResult.NOT_PERMITTED
        val resolver = appContext.contentResolver
        return try {
            val accepted = when (namespace) {
                SettingNamespace.SYSTEM -> Settings.System.putString(resolver, key, value)
                SettingNamespace.SECURE -> Settings.Secure.putString(resolver, key, value)
                SettingNamespace.GLOBAL -> Settings.Global.putString(resolver, key, value)
            }
            if (accepted) DirectWriteResult.WRITTEN else DirectWriteResult.FAILED
        } catch (_: SecurityException) {
            DirectWriteResult.NOT_PERMITTED
        } catch (_: Exception) {
            DirectWriteResult.FAILED
        }
    }
}
