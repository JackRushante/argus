package dev.argus.data.backup

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.SecureRandom
import java.time.ZoneId

/** Un file nella cartella del backup automatico. [uri] identifica il documento per il provider. */
data class BackupFolderFile(val name: String, val uri: String)

/**
 * La cartella scelta dall'utente (albero SAF). Gli URI sono stringhe: la logica che la usa resta pura e
 * si prova sul JVM. Ogni metodo lancia [IOException] (o un'eccezione di runtime del provider) se la
 * cartella non si raggiunge.
 */
interface BackupFolder {
    /** Prende il permesso persistente sulla cartella e ne restituisce il nome da mostrare. */
    suspend fun adopt(folderUri: String): String

    /** Lascia il permesso persistente (cambio di cartella). Non lancia. */
    fun release(folderUri: String)

    /** I file direttamente nella cartella. */
    suspend fun list(folderUri: String): List<BackupFolderFile>

    /** Crea un file vuoto; il nome restituito è quello che il provider ha davvero dato. */
    suspend fun create(folderUri: String, name: String, mimeType: String): BackupFolderFile

    /** Sostituisce il contenuto del file. */
    suspend fun write(file: BackupFolderFile, bytes: ByteArray)

    suspend fun delete(file: BackupFolderFile)
}

/**
 * Un'esecuzione del backup automatico: export completo, cifrato con la chiave salvata, scritto in un file
 * nuovo nella cartella; poi tiene i [AutoBackupRetention.KEEP] file più recenti scritti da Argus e cancella
 * gli altri suoi. I file che Argus non ha scritto non si toccano mai.
 */
class AutoBackupRunner(
    private val build: suspend () -> BuiltBackup,
    private val folder: BackupFolder,
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
) {
    /** L'esito e l'elenco aggiornato dei file scritti da Argus nella cartella. */
    data class Result(val outcome: AutoBackupOutcome, val writtenNames: Set<String>)

    suspend fun run(state: AutoBackupState, key: BackupKey?, nowMillis: Long, zone: ZoneId): Result {
        fun failed(failure: AutoBackupFailure) = Result(AutoBackupOutcome.Failed(failure), state.writtenNames)

        val folderUri = state.folderUri ?: return failed(AutoBackupFailure.FOLDER_NOT_SET)
        if (key == null) return failed(AutoBackupFailure.KEY_UNAVAILABLE)

        val built = when (val result = build()) {
            is BuiltBackup.Ready -> result
            is BuiltBackup.Failed -> return when (result.failure) {
                BackupFailure.NOTHING_TO_EXPORT -> Result(AutoBackupOutcome.NothingToBackUp, state.writtenNames)
                BackupFailure.EXPORT_TOO_LARGE -> failed(AutoBackupFailure.TOO_LARGE)
                else -> failed(AutoBackupFailure.READ_FAILED)
            }
        }

        val present = guarded { folder.list(folderUri) } ?: return failed(AutoBackupFailure.FOLDER_UNREACHABLE)
        val presentNames = present.mapTo(linkedSetOf()) { it.name }
        // Solo i nomi ancora presenti: un file tolto a mano esce dall'elenco.
        val written = state.writtenNames.filterTo(linkedSetOf()) { it in presentNames }

        val sealed = key.keyCopy().let { keyBytes ->
            try {
                EncryptedBackupFormat.seal(
                    plaintext = built.bytes,
                    key = keyBytes,
                    salt = key.salt,
                    iv = randomBytes(EncryptedBackupFormat.IV_BYTES),
                )
            } finally {
                keyBytes.fill(0)
            }
        }

        val name = AutoBackupRetention.fileName(nowMillis, zone)
        // Due esecuzioni nello stesso minuto: si riscrive il file di Argus, mai uno dell'utente.
        val reused = present.firstOrNull { it.name == name && it.name in written }
        val target = reused ?: guarded { folder.create(folderUri, name, EncryptedBackupFormat.MIME_TYPE) }
            ?: return failed(AutoBackupFailure.WRITE_FAILED)
        val writeOk = guarded { folder.write(target, sealed) } != null
        if (!writeOk) {
            // Un file nuovo scritto a metà non serve a nessuno: si toglie (se si riesce).
            if (reused == null) guarded { folder.delete(target) }
            return Result(AutoBackupOutcome.Failed(AutoBackupFailure.WRITE_FAILED), written)
        }
        written += target.name

        var deleted = 0
        val byName = present.associateBy { it.name }
        for (old in AutoBackupRetention.toDelete(presentNames + target.name, written)) {
            val file = byName[old] ?: continue
            if (guarded { folder.delete(file) } != null) {
                written -= old
                deleted++
            }
        }
        return Result(AutoBackupOutcome.Written(built.exported, target.name, deleted), written)
    }

    /** Il risultato, o null se la cartella ha risposto con un errore. La cancellazione passa. */
    private suspend fun <T : Any> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // SecurityException (permesso revocato), IllegalArgumentException/IllegalStateException del provider.
        null
    }
}
