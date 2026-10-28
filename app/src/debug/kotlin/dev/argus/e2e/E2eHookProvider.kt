package dev.argus.e2e

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dagger.hilt.android.EntryPointAccessors
import dev.argus.ArgusApplicationEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Aggancio degli E2E (0.6.0): esiste SOLO nel source set `debug`, quindi nessuna release lo contiene
 * (lo prova `E2eReleaseExclusionTest`). Si raggiunge dalla shell di adb:
 *
 *     content call --uri content://dev.argus.e2e --method <load|fire|journal|reset|...> --arg <base64>
 *
 * Ogni chiamata verifica l'uid del chiamante ([E2eCallerPolicy]): solo la shell (2000) e root (0),
 * qualsiasi altro uid riceve una `SecurityException`. Le operazioni vere sono in [E2eOperations].
 */
class E2eHookProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (!E2eCallerPolicy.allows(uid)) {
            throw SecurityException("e2e: uid $uid non ammesso")
        }
        val appContext = context?.applicationContext ?: return null
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            throw SecurityException("e2e: disponibile solo nella build debug")
        }
        val result: JsonObject = try {
            val request = E2eWire.decodeRequest(arg)
            val services = EntryPointAccessors.fromApplication(appContext, ArgusApplicationEntryPoint::class.java)
            runBlocking { operations(services).dispatch(method, request) }
        } catch (error: Exception) {
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(false),
                    "stage" to JsonPrimitive("hook"),
                    "code" to JsonPrimitive("hook_exception"),
                    "detail" to JsonPrimitive(error::class.java.simpleName),
                ),
            )
        }
        return Bundle().apply { putString(E2eWire.RESULT_KEY, E2eWire.encodeResult(result)) }
    }

    @Volatile
    private var cached: E2eOperations? = null

    private fun operations(services: ArgusApplicationEntryPoint): E2eOperations =
        cached ?: synchronized(this) { cached ?: E2eOperations(services).also { cached = it } }

    // Nessun dato da leggere o scrivere: il provider serve solo per `call`.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
